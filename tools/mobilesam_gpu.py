"""GPU-friendly forward passes for MobileSAM's TinyViT encoder (used by export_mobilesam.py).

LiteRT's GPU backend (ML Drift) handles tensors of up to 4 dimensions with batch 1. TinyViT's
window attention uses 6-D reshapes and folds the windows into the batch, so nearly the whole
encoder fell back to the CPU. These replacements compute exactly the same function with 4-D
tensors: windows are laid out along an axis instead of the batch, and attention heads are
unrolled into separate matrix multiplications. The attention bias is added on a flattened
view to work around a matmul+add fusion bug in the GPU backend.
"""
import torch
import torch.nn.functional as F


def attention_4d(self, x):
    """x: (1, windows, N, C) -> (1, windows, N, C). Same math as TinyViT's Attention.forward."""
    x = self.norm(x)
    qkv = self.qkv(x)
    kd, d = self.key_dim, self.d
    per = 2 * kd + d  # qkv is laid out per head as [q | k | v]
    heads = []
    for h in range(self.num_heads):
        b = h * per
        q, k, v = qkv[..., b:b + kd], qkv[..., b + kd:b + 2 * kd], qkv[..., b + 2 * kd:b + per]
        a = torch.matmul(q, k.transpose(-1, -2)) * self.scale
        # Adding the bias right after the matmul hits a fusion bug in LiteRT's GPU backend (wrong
        # results); adding it on a flattened view keeps the two ops apart and is exact.
        w, n = a.shape[1], a.shape[2]
        a = (a.reshape(1, w, n * n) + self.ab[h].reshape(1, 1, n * n)).reshape(1, w, n, n)
        heads.append(torch.matmul(a.softmax(dim=-1), v))
    return self.proj(torch.cat(heads, dim=-1))


def block_forward(self, x):
    """TinyViTBlock.forward with 4-D window partitioning (x: (1, L, C))."""
    H, W = self.input_resolution
    _, L, C = x.shape
    ws = self.window_size
    res = x
    if H == ws and W == ws:
        x = attention_4d(self.attn, x.reshape(1, 1, L, C)).reshape(1, L, C)
    else:
        x = x.reshape(1, H, W, C)
        pad_b, pad_r = (ws - H % ws) % ws, (ws - W % ws) % ws
        if pad_b or pad_r:
            x = F.pad(x, (0, 0, 0, pad_r, 0, pad_b))
        pH, pW = H + pad_b, W + pad_r
        nH, nW = pH // ws, pW // ws
        # (1, pH, nW, ws·C) -> (1, nW, pH, ws·C) -> one window per row of axis 1.
        x = x.reshape(1, pH, nW, ws * C).permute(0, 2, 1, 3).reshape(1, nW * nH, ws * ws, C)
        x = attention_4d(self.attn, x)
        x = x.reshape(1, nW, pH, ws * C).permute(0, 2, 1, 3).reshape(1, pH, pW, C)
        if pad_b or pad_r:
            x = x[:, :H, :W, :]
        x = x.reshape(1, L, C)
    x = res + x
    x = x.transpose(1, 2).reshape(1, C, H, W)
    x = self.local_conv(x)
    x = x.reshape(1, C, L).transpose(1, 2)
    return x + self.mlp(x)


def patch(sam):
    """Switches every TinyViT block of [sam] to the GPU-friendly forward."""
    from mobile_sam.modeling.tiny_vit_sam import TinyViTBlock
    n = 0
    for m in sam.image_encoder.modules():
        if isinstance(m, TinyViTBlock):
            m.forward = block_forward.__get__(m)
            n += 1
    return n
