"""
Download Depth Anything V2 Base dynamic ONNX model, bake to fixed 756x756,
and quantize to FP16 for Android deployment.

Requirements: pip install onnxruntime onnx onnxconverter-common
"""

import os
import sys
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
ASSETS_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")
CACHE_DIR = os.path.join(SCRIPT_DIR, ".cache")

DYNAMIC_MODEL_URL = "https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vitb_dynamic.onnx"
DYNAMIC_MODEL_FILE = os.path.join(CACHE_DIR, "depth_anything_v2_vitb_dynamic.onnx")
FIXED_MODEL_FILE = os.path.join(CACHE_DIR, "depth_anything_v2_vitb_518.onnx")
OUTPUT_MODEL_FILE = os.path.join(ASSETS_DIR, "depth_anything_v2_vitb_518.onnx")

INPUT_SIZE = 518


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


def copy_to_assets():
    if os.path.exists(OUTPUT_MODEL_FILE):
        size_mb = os.path.getsize(OUTPUT_MODEL_FILE) / (1024 * 1024)
        print(f"Model already in assets ({size_mb:.0f}MB)")
        return

    os.makedirs(ASSETS_DIR, exist_ok=True)
    import shutil
    shutil.copy2(FIXED_MODEL_FILE, OUTPUT_MODEL_FILE)
    size_mb = os.path.getsize(OUTPUT_MODEL_FILE) / (1024 * 1024)
    print(f"FP32 model copied to assets ({size_mb:.0f}MB)")


def main():
    print(f"=== Preparing Depth Anything V2 Base {INPUT_SIZE}x{INPUT_SIZE} model ===")
    download_model()
    bake_fixed_shape()
    copy_to_assets()
    print("=== Done ===")


if __name__ == "__main__":
    main()
