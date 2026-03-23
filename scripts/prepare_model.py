"""
Download Depth Anything V2 Base dynamic ONNX model, bake to fixed 770x770,
optimize transformer graph, convert to FP16, and prepare for Android deployment.

Requirements: pip install onnxruntime onnx onnxconverter-common
"""

import os
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
ASSETS_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")
CACHE_DIR = os.path.join(SCRIPT_DIR, ".cache")

DYNAMIC_MODEL_URL = "https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vitb_dynamic.onnx"
DYNAMIC_MODEL_FILE = os.path.join(CACHE_DIR, "depth_anything_v2_vitb_dynamic.onnx")
FIXED_MODEL_FILE = os.path.join(CACHE_DIR, "depth_anything_v2_vitb_770_fixed.onnx")
OPTIMIZED_MODEL_FILE = os.path.join(CACHE_DIR, "depth_anything_v2_vitb_770_opt.onnx")
FP16_MODEL_FILE = os.path.join(CACHE_DIR, "depth_anything_v2_vitb_770_fp16.onnx")
OUTPUT_MODEL_FILE = os.path.join(ASSETS_DIR, "depth_anything_v2_vitb_770_fp16.onnx")

INPUT_SIZE = 770


def download_model():
    if os.path.exists(DYNAMIC_MODEL_FILE):
        size_mb = os.path.getsize(DYNAMIC_MODEL_FILE) / (1024 * 1024)
        print(f"Dynamic model already cached ({size_mb:.0f}MB)")
        return

    os.makedirs(CACHE_DIR, exist_ok=True)
    print(f"Downloading dynamic model (~371MB)...")
    subprocess.check_call([
        "curl", "--retry", "3", "-L", "-C", "-",
        "-o", DYNAMIC_MODEL_FILE,
        DYNAMIC_MODEL_URL
    ])
    print("Download complete.")


def bake_fixed_shape():
    if os.path.exists(FIXED_MODEL_FILE):
        print(f"Fixed {INPUT_SIZE}x{INPUT_SIZE} model already cached")
        return

    print(f"Baking model to fixed {INPUT_SIZE}x{INPUT_SIZE} input shape...")
    from onnxruntime.tools.make_dynamic_shape_fixed import make_input_shape_fixed, fix_output_shapes
    import onnx

    model = onnx.load(DYNAMIC_MODEL_FILE)
    make_input_shape_fixed(model.graph, "image", [1, 3, INPUT_SIZE, INPUT_SIZE])
    fix_output_shapes(model)
    onnx.save(model, FIXED_MODEL_FILE)
    print(f"Fixed model saved ({os.path.getsize(FIXED_MODEL_FILE) / (1024*1024):.0f}MB)")


def optimize_model():
    if os.path.exists(OPTIMIZED_MODEL_FILE):
        size_mb = os.path.getsize(OPTIMIZED_MODEL_FILE) / (1024 * 1024)
        print(f"Optimized model already cached ({size_mb:.0f}MB)")
        return

    print("Running ORT transformer optimizer (fusing attention, GELU, LayerNorm)...")
    try:
        from onnxruntime.transformers.optimizer import optimize_model as ort_optimize
        from onnxruntime.transformers.fusion_options import FusionOptions

        opt_options = FusionOptions("vit")
        optimized = ort_optimize(
            FIXED_MODEL_FILE,
            model_type="vit",
            num_heads=12,
            hidden_size=768,
            optimization_options=opt_options
        )
        optimized.save_model_to_file(OPTIMIZED_MODEL_FILE)
        size_mb = os.path.getsize(OPTIMIZED_MODEL_FILE) / (1024 * 1024)
        print(f"Optimized model saved ({size_mb:.0f}MB)")
    except Exception as e:
        print(f"Transformer optimizer failed: {e}")
        print("Falling back to unoptimized fixed model...")
        import shutil
        shutil.copy2(FIXED_MODEL_FILE, OPTIMIZED_MODEL_FILE)


def convert_fp16():
    if os.path.exists(FP16_MODEL_FILE):
        size_mb = os.path.getsize(FP16_MODEL_FILE) / (1024 * 1024)
        print(f"FP16 model already cached ({size_mb:.0f}MB)")
        return

    print("Converting to FP16 (halving model size)...")
    import onnx
    from onnxconverter_common import float16

    model = onnx.load(OPTIMIZED_MODEL_FILE)
    model_fp16 = float16.convert_float_to_float16(model, keep_io_types=True)
    onnx.save(model_fp16, FP16_MODEL_FILE)
    size_mb = os.path.getsize(FP16_MODEL_FILE) / (1024 * 1024)
    print(f"FP16 model saved ({size_mb:.0f}MB)")


def copy_to_assets():
    os.makedirs(ASSETS_DIR, exist_ok=True)
    import shutil
    shutil.copy2(FP16_MODEL_FILE, OUTPUT_MODEL_FILE)
    size_mb = os.path.getsize(OUTPUT_MODEL_FILE) / (1024 * 1024)
    print(f"Model copied to assets ({size_mb:.0f}MB)")


def main():
    print(f"=== Preparing Depth Anything V2 Base {INPUT_SIZE}x{INPUT_SIZE} FP16 model ===")
    download_model()
    bake_fixed_shape()
    optimize_model()
    convert_fp16()
    copy_to_assets()
    print("=== Done ===")


if __name__ == "__main__":
    main()
