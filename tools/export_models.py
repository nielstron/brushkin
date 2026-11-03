#!/usr/bin/env python3
"""Exports the image models the app downloads on first use, as LiteRT (.tflite) files with fixed
input shapes. Every model is fully convolutional, so the app runs it over tiles of larger images.

  zipdepth_512.tflite      ZipDepth (MIT, ECCV 2026)          depth for the depth-of-field blur
                           image [1,3,512,512] RGB 0…1 -> relative depth [1,1,512,512]
  plksr_x4_128.tflite      PLKSR-Rep (MIT, NTIRE 2026 mobile real-world SR winner)  upscaling
                           image [1,3,128,128] RGB 0…1 -> [1,3,512,512]
  nafnet_denoise_256.tflite  NAFNet-SIDD-width32 (MIT)          denoising
                           image [1,3,256,256] RGB 0…1 -> same
  nafnet_deblur_384.tflite   NAFNet-GoPro-width32 (MIT)         deblurring
                           image [1,3,384,384] RGB 0…1 -> same
  migan_512.tflite         MI-GAN Places2 (MIT, weights MIT)  object removal (inpainting)
                           [1,4,512,512] (known-mask - 0.5, RGB·mask in -1…1) -> RGB -1…1
  mobilesam_decoder_refine.tflite  MobileSAM's prompt decoder (Apache-2.0): embeddings, prompt points
                           [1,n,2] + labels [1,n], the previous mask's logits [1,1,256,256] and a flag
                           whether to use them. One signature "t<n>" per prompt count n = 2…9 (shared
                           weights): SAM's results depend on the number of padding points (label -1),
                           and taps get exactly one, as in SAM's own ONNX export.

Usage (Python 3.11; pip install litert-torch==0.10.0 ai-edge-quantizer==0.10.1 timm gdown):
  tools/export_models.py OUT_DIR [model ...]
Sources and weights are pinned by commit and SHA-256 below; the outputs are attached to the GitHub
release "models-2" and their hashes pinned in app/src/main/.../ml/ImageModels.kt and ObjectSelectClient.kt.
"""
import hashlib, importlib.util, os, subprocess, sys, tempfile, types, urllib.request, warnings

warnings.filterwarnings("ignore")
import torch
import torch.nn.functional as F
from torch import nn

REPOS = {
    "zipdepth": ("https://github.com/fabiotosi92/ZipDepth.git", "91f3fd21e131641f51e8d35736d1958350180e3a"),
    "nafnet": ("https://github.com/megvii-research/NAFNet.git", "2b4af71ebe098a92a75910c233a3965a3e93ede4"),
    "migan": ("https://github.com/Picsart-AI-Research/MI-GAN.git", "2b793c5ece43f4253e32d4afc257120a5deed6f5"),
    "plksr": ("https://github.com/Innocentlove321/ntire2026-mobile-realworld-sr-vipsl19.git", "ef4442ca57d7037593a1e608be294c351d0dc3d9"),
    "mobilesam": ("https://github.com/ChaoningZhang/MobileSAM.git", "f706ad9c4eb7f219c00d9050e46328518ffb65d2"),
}
WEIGHTS = {
    # (url or "repo:<path>" or "gdrive:<id>", sha256)
    "zipdepth": ("repo:checkpoints/zipdepth_base_npu.pth", "627c04fda584133ead4310074884a4a037061b4c01ba86e73e492ea30fab570d"),
    "plksr": ("https://github.com/Innocentlove321/ntire2026-mobile-realworld-sr-vipsl19/releases/download/ntire2026-final/net_g_1000.pth",
              "2990afbf1460cf4b6cb6b34a5c2994ec6f4d3a64328565c87bd2fa85b23484af"),
    # The official copies are on Google Drive; these mirrors have the same bytes (hash-checked).
    "nafnet_sidd": ("https://huggingface.co/mikestealth/nafnet-models/resolve/main/NAFNet-SIDD-width32.pth",
                    "89c70e808d1783b6c07911306e106aaf0d4f7f3da8c61078b99ff7f8929a26f4"),
    "nafnet_gopro": ("https://huggingface.co/mikestealth/nafnet-models/resolve/main/NAFNet-GoPro-width32.pth",
                     "19394e6155d12ef6371d1d57496f87f0ec88f92bdffa27c0792690722d5d1a5c"),
    "migan": ("gdrive:migan_512_places2.pt@1xNtvN2lto0p5yFKOEEg9RioMjGrYM74w", "1d6087eee0aac8923ad2606be5d8caeb4824d3e4de331995e420c74e124a466a"),
    "mobilesam": ("repo:weights/mobile_sam.pt", None),
}
CACHE = os.path.join(tempfile.gettempdir(), "brushkin-models")


def sha256(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()


def repo(name):
    url, commit = REPOS[name]
    d = os.path.join(CACHE, name + "-" + commit[:8])
    if not os.path.exists(d):
        subprocess.check_call(["git", "clone", "-q", url, d])
        subprocess.check_call(["git", "-C", d, "checkout", "-q", commit])
    return d


def weights(name, repo_name=None):
    src, sha = WEIGHTS[name]
    if src.startswith("repo:"):
        p = os.path.join(repo(repo_name or name), src[5:])
    else:
        os.makedirs(CACHE, exist_ok=True)
        if src.startswith("gdrive:"):
            fname, folder = src[7:].split("@")
            p = os.path.join(CACHE, fname)
            if not os.path.exists(p):
                import gdown
                d = os.path.join(CACHE, "gdrive-" + folder)
                gdown.download_folder(id=folder, output=d, quiet=True)
                for root, _, files in os.walk(d):
                    if fname in files:
                        os.replace(os.path.join(root, fname), p)
        else:
            p = os.path.join(CACHE, os.path.basename(src))
            if not os.path.exists(p):
                urllib.request.urlretrieve(src, p)
    if sha and sha256(p) != sha:
        raise SystemExit(f"{p}: unexpected SHA-256")
    return p


def load_file(modname, path):
    spec = importlib.util.spec_from_file_location(modname, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[modname] = m
    spec.loader.exec_module(m)
    return m


def stub(*names, **attrs):
    """Empty modules standing in for the training frameworks the architectures import from."""
    for n in names:
        m = types.ModuleType(n)
        m.__path__ = []
        sys.modules.setdefault(n, m)
    for k, v in attrs.items():
        mod, attr = k.rsplit(".", 1) if "." in k else (k, None)
        setattr(sys.modules[mod.replace("__", ".")], attr, v)


class Registry:
    def register(self, *a, **k):
        return lambda cls: cls


# ---------------------------------------------------------------- models

def zipdepth():
    d = repo("zipdepth")
    sys.path.insert(0, d)
    from zipdepth.model.architecture import create_model
    from zipdepth.utils.model_utils import fuse_remaining_conv_bn, strip_state_dict_prefixes
    m = create_model(variant="base", global_mode="balanced", upsample_unfold=False)
    sd = torch.load(weights("zipdepth"), map_location="cpu", weights_only=True)
    m.load_state_dict(strip_state_dict_prefixes(sd.get("model_state_dict", sd)), strict=False)
    m.eval().fuse_for_inference()
    fuse_remaining_conv_bn(m)
    # Static-shape forms of the adaptive pooling (as the project's own ONNX export does).
    H = W = 512
    s16 = (H // 16, W // 16)
    for mod in m.modules():
        n = type(mod).__name__
        if n == "GlobalContextBlock":
            mod.forward = types.MethodType(lambda self, x: x + self.transform(F.avg_pool2d(x, kernel_size=s16)), mod)
        elif n == "StripPoolingAttention":
            mod.forward = types.MethodType(lambda self, x: x * self.gate_conv(x.mean(dim=3, keepdim=True) + x.mean(dim=2, keepdim=True)), mod)
    cs = m.encoder.cross_scale

    def cs_fwd(self, x_high, x_low):
        lo = F.interpolate(self.low_to_high(x_low), size=s16, mode="nearest")
        hi = F.avg_pool2d(self.high_to_low(x_high), 2, 2)
        return x_high + lo * 0.3, x_low + hi * 0.3
    cs.forward = types.MethodType(cs_fwd, cs)
    return "zipdepth_512", m, (torch.rand(1, 3, H, W),)


def plksr():
    d = repo("plksr")
    stub("basicsr", "basicsr.utils", "basicsr.utils.registry", "basicsr.archs", "basicsr.archs.arch_util", "plksr_archs")
    sys.modules["basicsr.utils.registry"].ARCH_REGISTRY = Registry()
    sys.modules["basicsr.archs.arch_util"].trunc_normal_ = nn.init.trunc_normal_
    base = load_file("plksr_archs.plksr_arch", os.path.join(d, "plksr/archs/plksr_arch.py"))
    rep = load_file("plksr_archs.plksr_rep_arch", os.path.join(d, "plksr/archs/plksr_rep_arch.py"))
    m = rep.PLKSR_Rep(dim=64, n_blocks=12, upscaling_factor=4, ccm_type="DCCM", kernel_size=17, split_ratio=0.25, use_ea=True).eval()
    ck = torch.load(weights("plksr"), map_location="cpu", weights_only=True)
    m.load_state_dict(ck.get("params_ema", ck.get("params", ck)), strict=True)
    for mod in list(m.modules()):
        if isinstance(mod, rep.RepConv):
            mod.switch_to_deploy()
    base.convert_plk_forward_for_coreml(m)  # slicing form of the partial large-kernel conv
    # The residual repeats each input channel 16× and pixel-shuffles the sum. Shuffling is linear
    # and shuffling repeated channels is a nearest ×4 upscale, which LiteRT's GPU backend can
    # compile (its repeat_interleave can't).
    m.forward = types.MethodType(lambda self, x: self.to_img(self.feats(x)) + F.interpolate(x, scale_factor=4, mode="nearest"), m)
    return "plksr_x4_128", m, (torch.rand(1, 3, 128, 128),)


def _nafnet(cfg, wname, out, side):
    d = repo("nafnet")

    class LayerNorm2d(nn.Module):
        def __init__(self, channels, eps=1e-6):
            super().__init__()
            self.weight = nn.Parameter(torch.ones(channels)); self.bias = nn.Parameter(torch.zeros(channels)); self.eps = eps

        def forward(self, x):
            mu = x.mean(1, keepdim=True)
            var = (x - mu).pow(2).mean(1, keepdim=True)
            return self.weight.view(1, -1, 1, 1) * ((x - mu) / (var + self.eps).sqrt()) + self.bias.view(1, -1, 1, 1)

    stub("basicsr", "basicsr.models", "basicsr.models.archs", "basicsr.models.archs.arch_util", "basicsr.models.archs.local_arch")
    sys.modules["basicsr.models.archs.arch_util"].LayerNorm2d = LayerNorm2d
    sys.modules["basicsr.models.archs.local_arch"].Local_Base = type("Local_Base", (), {})
    arch = load_file("nafnet_arch", os.path.join(d, "basicsr/models/archs/NAFNet_arch.py"))
    m = arch.NAFNet(img_channel=3, width=32, **cfg).eval()
    ck = torch.load(weights(wname), map_location="cpu", weights_only=True)
    m.load_state_dict(ck.get("params", ck), strict=True)
    # Tiles at most 1.5× the training crops (256), where the original's local (TLC) pooling equals
    # the global pooling used here.
    return out, m, (torch.rand(1, 3, side, side),)


def nafnet_denoise():
    return _nafnet(dict(enc_blk_nums=[2, 2, 4, 8], middle_blk_num=12, dec_blk_nums=[2, 2, 2, 2]), "nafnet_sidd", "nafnet_denoise_256", 256)


def nafnet_deblur():
    return _nafnet(dict(enc_blk_nums=[1, 1, 1, 28], middle_blk_num=1, dec_blk_nums=[1, 1, 1, 1]), "nafnet_gopro", "nafnet_deblur_384", 384)


def migan():
    d = repo("migan")
    arch = load_file("migan_inference", os.path.join(d, "lib/model_zoo/migan_inference.py"))
    m = arch.Generator(resolution=512).eval()
    m.load_state_dict(torch.load(weights("migan"), map_location="cpu", weights_only=True))
    return "migan_512", m, (torch.rand(1, 4, 512, 512) * 2 - 1,)


POINTS = 8


def mobilesam_refine():
    d = repo("mobilesam")
    sys.path.insert(0, d)
    from mobile_sam import sam_model_registry
    from mobile_sam.utils.onnx import SamOnnxModel
    sam = sam_model_registry["vit_t"](checkpoint=weights("mobilesam")).eval()

    class Decoder(nn.Module):
        """SAM's prompt decoder with point/box prompts and an optional previous mask, fixed shapes."""

        def __init__(self):
            super().__init__()
            self.helper = SamOnnxModel(sam, return_single_mask=False)

        def forward(self, image_embeddings, point_coords, point_labels, mask_input, has_mask_input):
            sparse = self.helper._embed_points(point_coords, point_labels)
            dense = self.helper._embed_masks(mask_input, has_mask_input)
            masks, scores = sam.mask_decoder.predict_masks(
                image_embeddings=image_embeddings, image_pe=sam.prompt_encoder.get_dense_pe(),
                sparse_prompt_embeddings=sparse, dense_prompt_embeddings=dense)
            return masks, scores

    def sample(n):
        labels = torch.full((1, n), -1.0); labels[0, 0] = 1
        return (torch.randn(1, 256, 64, 64), torch.rand(1, n, 2) * 1024, labels, torch.zeros(1, 1, 256, 256), torch.zeros(1))
    return "mobilesam_decoder_refine", Decoder().eval(), {f"t{n}": sample(n) for n in range(2, POINTS + 2)}


MODELS = {f.__name__: f for f in (zipdepth, plksr, nafnet_denoise, nafnet_deblur, migan, mobilesam_refine)}


def check(path, module, sample):
    """Compare the LiteRT model with PyTorch on the sample input (each signature's)."""
    from ai_edge_litert.interpreter import Interpreter
    if isinstance(sample, dict):
        errs = []
        it = Interpreter(model_path=path)
        for sig, s in sample.items():
            r = it.get_signature_runner(sig)
            # Inputs matched by shape (all differ).
            names = {tuple(d["shape"]): n for n, d in r.get_input_details().items()}
            out = r(**{names[tuple(x.shape)]: x.numpy() for x in s})
            with torch.no_grad():
                ref = module(*s)
            for x in ref:
                o = next(v for v in out.values() if v.shape == tuple(x.shape))
                errs.append(round(float(abs(torch.from_numpy(o) - x).max() / (x.abs().max() + 1e-6)), 4))
        return errs
    it = Interpreter(model_path=path)
    it.allocate_tensors()
    ins = sorted(it.get_input_details(), key=lambda t: t["index"])
    for t, x in zip(ins, sample):
        it.set_tensor(t["index"], x.numpy())
    it.invoke()
    with torch.no_grad():
        ref = module(*sample)
    ref = ref if isinstance(ref, (tuple, list)) else (ref,)
    outs = it.get_output_details()
    errs = []
    for r in ref:
        o = next(it.get_tensor(t["index"]) for t in outs if tuple(t["shape"]) == tuple(r.shape))
        errs.append(float(abs(torch.from_numpy(o) - r).max() / (r.abs().max() + 1e-6)))
    return errs


if __name__ == "__main__":
    import litert_torch
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    os.makedirs(out, exist_ok=True)
    for name in sys.argv[2:] or MODELS:
        fname, module, sample = MODELS[name]()
        p = os.path.join(out, fname + ".tflite")
        with torch.no_grad():
            if isinstance(sample, dict):  # several signatures over the same weights
                conv = None
                for sig, s in sample.items():
                    conv = (conv or litert_torch).signature(sig, module, s)
                conv.convert().export(p)
            else:
                litert_torch.convert(module, sample).export(p)
        if name == "mobilesam_refine":
            # Runs on the CPU per tap: 8-bit weights (float math) like the original decoder.
            from ai_edge_quantizer import quantizer, recipe
            q = quantizer.Quantizer(p)
            q.load_quantization_recipe(recipe.weight_only_wi8_afp32())
            q.quantize().export_model(p, overwrite=True)
        print(f"{fname}.tflite {os.path.getsize(p)} {sha256(p)} rel.err {check(p, module, sample)}", flush=True)
