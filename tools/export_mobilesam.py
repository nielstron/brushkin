#!/usr/bin/env python3
"""Exports MobileSAM (Apache-2.0, https://github.com/ChaoningZhang/MobileSAM) to the two TFLite
files the FOSS build downloads on first use of object selection:

  mobilesam_encoder.tflite  image [1,1024,1024,3] (normalized RGB, zero padded) -> embeddings
  mobilesam_decoder.tflite  embeddings + 2 prompt points -> 4 candidate masks [256×256] + scores
  mobilesam_encoder_gpu.tflite  the encoder for LiteRT's GPU backend (float weights)

Weights are stored as 8-bit integers (math stays float32), about 20 MB for both files.
The decoder takes exactly two prompt points: a tap plus SAM's padding point (label -1), or a box
(labels 2 and 3). Its masks cover the padded 1024×1024 input; the app crops and upscales them.

Usage (Python 3.11; pip install litert-torch==0.10.0 ai-edge-quantizer==0.10.1 timm):
  tools/export_mobilesam.py OUT_DIR
The files are attached to the GitHub shared release "models-2"; their SHA-256 hashes are
pinned in app/src/main/.../ml/ObjectSelectClient.kt.
"""
import os, subprocess, sys, tempfile, hashlib, warnings

COMMIT = "f706ad9c4eb7f219c00d9050e46328518ffb65d2"

out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
os.makedirs(out, exist_ok=True)
src = os.path.join(tempfile.gettempdir(), "MobileSAM-" + COMMIT[:8])
if not os.path.exists(src):
    subprocess.check_call(["git", "clone", "-q", "https://github.com/ChaoningZhang/MobileSAM.git", src])
    subprocess.check_call(["git", "-C", src, "checkout", "-q", COMMIT])
sys.path.insert(0, src)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import torch
import litert_torch
from ai_edge_quantizer import quantizer, recipe
from mobile_sam import sam_model_registry
from mobile_sam.utils.onnx import SamOnnxModel

warnings.filterwarnings("ignore")
sam = sam_model_registry["vit_t"](checkpoint=os.path.join(src, "weights", "mobile_sam.pt"))
sam.eval()


class Encoder(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.enc = m.image_encoder

    def forward(self, image):
        return self.enc(image)


class Decoder(torch.nn.Module):
    """SAM's prompt decoder without the resize-to-image post-processing (fixed shapes)."""

    def __init__(self, m):
        super().__init__()
        self.helper = SamOnnxModel(m, return_single_mask=False)
        self.m = m

    def forward(self, image_embeddings, point_coords, point_labels):
        sparse = self.helper._embed_points(point_coords, point_labels)
        dense = self.m.prompt_encoder.no_mask_embed.weight.reshape(1, -1, 1, 1).expand(1, -1, 64, 64)
        masks, scores = self.m.mask_decoder.predict_masks(
            image_embeddings=image_embeddings, image_pe=self.m.prompt_encoder.get_dense_pe(),
            sparse_prompt_embeddings=sparse, dense_prompt_embeddings=dense)
        return masks, scores


with torch.no_grad():
    sample_enc = (torch.randn(1, 3, 1024, 1024),)
    sample_dec = (torch.randn(1, 256, 64, 64), torch.rand(1, 2, 2) * 1024, torch.tensor([[1.0, -1.0]]))
    for name, module, sample in (("mobilesam_encoder", Encoder(sam), sample_enc), ("mobilesam_decoder", Decoder(sam), sample_dec)):
        p = os.path.join(out, name + ".tflite")
        raw = os.path.join(out, name + "_fp32.tflite")
        litert_torch.convert(module.eval(), sample).export(raw)
        # 8-bit weights, float32 math: 2.5× smaller, masks match the float model (IoU > 0.99).
        q = quantizer.Quantizer(raw)
        q.load_quantization_recipe(recipe.weight_only_wi8_afp32())
        q.quantize().export_model(p, overwrite=True)
        os.remove(raw)
        print(name + ".tflite", os.path.getsize(p), hashlib.sha256(open(p, "rb").read()).hexdigest())

    # GPU variant of the encoder: same function with GPU-friendly window attention (see
    # mobilesam_gpu.py), full-precision weights (the GPU backend doesn't take 8-bit weights).
    import mobilesam_gpu
    mobilesam_gpu.patch(sam)
    p = os.path.join(out, "mobilesam_encoder_gpu.tflite")
    litert_torch.convert(Encoder(sam).eval(), sample_enc).export(p)
    print("mobilesam_encoder_gpu.tflite", os.path.getsize(p), hashlib.sha256(open(p, "rb").read()).hexdigest())
