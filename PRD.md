# SBS 3D Converter — Android App Technical Plan

## Overview

A native Android app (Kotlin + Jetpack Compose) that converts 2D images and videos into stereoscopic 3D Side-by-Side (SBS) format for viewing on XR glasses, VR headsets, and 3D displays. The app runs entirely on-device with no server component. Processing is offline/batch — no real-time constraints.

**Target user:** Personal use tool. Not commercial.

---

## Architecture Summary

```
┌─────────────────────────────────────────────────┐
│                   Jetpack Compose UI             │
│  (Media picker, settings, progress, preview)     │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│              Processing Pipeline                 │
│                                                  │
│  Image: Pick → Decode → Depth → SBS Warp → Save │
│  Video: Pick → Decode frames → Depth per frame   │
│         → Temporal smooth → SBS Warp → Encode    │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│              Native Components                   │
│                                                  │
│  • ONNX Runtime Android (depth inference)        │
│  • MediaCodec/MediaExtractor (video decode)      │
│  • MediaCodec/MediaMuxer (video encode)          │
│  • Android Foreground Service (long tasks)        │
└─────────────────────────────────────────────────┘
```

---

## Tech Stack

| Component | Choice | Notes |
|-----------|--------|-------|
| Language | Kotlin | Pure native Android, no Flutter |
| UI | Jetpack Compose | Material 3 |
| Depth model (images) | Depth Anything V2 Base | Pre-exported ONNX available from `fabio-sim/Depth-Anything-ONNX` |
| Depth model (video) | Depth Anything V2 Base + temporal smoothing | Per-frame inference with EMA depth smoothing. Video Depth Anything deferred to v2 due to export complexity |
| Model format | FP16 ONNX (~195MB) | Quantized from FP32 (~390MB) for memory efficiency |
| Inference runtime | ONNX Runtime Android | Official Maven dependency: `com.microsoft.onnxruntime:onnxruntime-android` |
| Video decode | Android MediaCodec + MediaExtractor | Native API, no external dependencies |
| Video encode | Android MediaCodec + MediaMuxer | H.264 output in MP4 container |
| SBS warp | Custom Kotlin implementation | Mesh warping algorithm with hole-filling |
| Long processing | Android Foreground Service | Persistent notification with progress |

---

## Model Details

### Depth Anything V2 Base (ViT-B)

- **Parameters:** ~97M
- **Input:** 518×518 RGB image (normalized)
- **Output:** 518×518 single-channel relative depth map
- **License:** CC-BY-NC-4.0 (fine for personal use, NOT for commercial distribution)
- **ONNX source:** `fabio-sim/Depth-Anything-ONNX` GitHub releases — pre-exported for ViT-B with both static (518×518) and dynamic shapes
- **HuggingFace ONNX:** `onnx-community/depth-anything-v2-base` (alternative source)
- **FP16 quantization:** Use ONNX Runtime's built-in quantization or `onnxruntime.quantization` Python tool to convert FP32 → FP16 before deploying

### Why NOT Video Depth Anything on-device

Video Depth Anything Base would give temporally consistent depth maps, but:

1. **No ONNX export exists.** The temporal attention head (spatial-temporal head with multi-head self-attention along temporal dimension) has not been successfully exported to any static graph format.
2. **The streaming mode has quality degradation.** The authors note significant accuracy drops in streaming vs. offline mode (e.g., d1 on ScanNet drops from 0.926 to 0.836).
3. **ExecuTorch/TorchScript export is unproven** for DINOv2-based architectures — `interpolate_pos_encoding` has known export issues.

**Mitigation:** Apply temporal smoothing to per-frame DA V2 Base depth maps (see Temporal Smoothing section below).

---

## Processing Pipeline — Detailed

### Phase 1: Image Processing

```
1. User picks image from gallery
2. Decode to Bitmap (Android BitmapFactory)
3. Preprocess:
   a. Resize to 518×518 (maintain aspect ratio, pad if needed)
   b. Normalize: (pixel / 255.0 - mean) / std
      mean = [0.485, 0.456, 0.406]
      std  = [0.229, 0.224, 0.225]
   c. Convert to float tensor [1, 3, 518, 518]
4. Run ONNX Runtime inference → depth map [1, 518, 518]
5. Post-process depth map:
   a. Normalize to 0-1 range
   b. Resize to original image dimensions (bilinear interpolation)
   c. Apply Gaussian blur (configurable kernel size, e.g. 5-15)
6. Generate SBS pair using mesh warping (see SBS Warp section)
7. Combine left + right views into single SBS image
8. Save as JPEG/PNG to gallery
```

### Phase 2: Video Processing

```
1. User picks video from gallery
2. Extract metadata (resolution, fps, duration, codec)
3. Start foreground service with progress notification
4. Initialize:
   a. MediaExtractor + MediaCodec decoder
   b. MediaCodec encoder + MediaMuxer for output
   c. ONNX Runtime session (load model once)
   d. Previous depth map buffer (for temporal smoothing)
5. Frame loop:
   a. Decode next frame via MediaCodec → YUV buffer
   b. Convert YUV → RGB Bitmap
   c. Preprocess for depth inference (same as image pipeline)
   d. Run depth inference
   e. Apply temporal smoothing against previous depth map
   f. Generate SBS frame via mesh warp
   g. Encode SBS frame via MediaCodec encoder
   h. Update progress notification
6. Finalize MediaMuxer (write moov atom)
7. Copy audio track from source to output (via MediaExtractor + MediaMuxer)
8. Save to gallery, notify user
```

---

## SBS Warp Algorithm

### Mesh Warping Approach

The mesh warping method treats the image as a triangle mesh deformed by depth values. This inherently handles holes better than per-pixel displacement because triangles stretch to cover gaps.

```
Algorithm: Mesh-based DIBR (Depth Image Based Rendering)

1. Create a regular grid of vertices over the image
   - Grid resolution: e.g., every 2-4 pixels
   - Each vertex has position (x, y) and depth d

2. For LEFT eye view:
   - Shift each vertex: x_left = x + (depth[x,y] * eye_separation / 2)
   
3. For RIGHT eye view:
   - Shift each vertex: x_right = x - (depth[x,y] * eye_separation / 2)

4. Triangulate the grid (two triangles per quad cell)

5. Render the warped mesh using texture mapping:
   - Original image is the texture
   - Warped vertex positions define where each triangle maps
   - Use standard rasterization (can use Android Canvas or OpenGL)

Parameters:
- eye_separation: float (user-configurable, default ~30, range 5-80)
- depth_blur: int (Gaussian kernel size, odd, default 7, range 3-33)
```

### Hole Filling Strategy

For cases where holes still appear at high eye separation values:

1. **Depth map pre-blur** (primary defense): Gaussian blur on the depth map before warping smooths depth discontinuities, preventing most holes from forming. This is the single most effective technique.

2. **Background extrapolation** (secondary): For remaining holes at depth boundaries:
   - Holes in DIBR always expose background (the occluded area behind a foreground object)
   - Detect hole pixels (pixels with no source mapping)
   - For each hole pixel, scan horizontally toward the background side (the side with larger depth values)
   - Copy color from the nearest valid background pixel
   - This is fast (single horizontal pass) and produces natural results since the exposed area IS background

3. **Edge-aware depth processing** (optional enhancement): Before warping, apply a bilateral filter to the depth map instead of Gaussian blur. This smooths depth within objects while preserving edges, reducing both holes and ghosting artifacts.

---

## Temporal Smoothing for Video

Since we're using per-frame Depth Anything V2 Base (no temporal model), raw depth maps may flicker between frames. Apply temporal smoothing:

### Exponential Moving Average (EMA) on Depth Maps

```kotlin
// For each frame:
if (previousDepthMap == null) {
    smoothedDepth = currentDepth
} else {
    // alpha controls smoothing strength
    // Lower alpha = more smoothing (less flicker, more lag)
    // Higher alpha = less smoothing (more responsive, more flicker)
    // Recommended: 0.3 - 0.5
    val alpha = 0.4f
    smoothedDepth = alpha * currentDepth + (1 - alpha) * previousDepthMap
}
previousDepthMap = smoothedDepth
```

### Enhanced: Depth-Aware Temporal Smoothing

Standard EMA can cause ghosting on moving objects. Improved version:

```
1. Compute per-pixel depth difference: diff = |currentDepth - previousDepth|
2. For pixels where diff > threshold (moving objects/scene changes):
   - Use higher alpha (e.g., 0.8) → favor current frame
3. For pixels where diff < threshold (static regions):
   - Use lower alpha (e.g., 0.2) → favor smooth temporal output
4. This preserves depth accuracy on moving objects while
   eliminating flicker on static backgrounds
```

---

## Video I/O Implementation

### Option A: MediaCodec (Recommended — No External Dependencies)

**Decode pipeline:**
```
MediaExtractor → select video track → MediaCodec decoder (no Surface)
→ output ByteBuffer (YUV_420_888) → convert to RGB Bitmap
```

**Key implementation notes:**
- Configure decoder WITHOUT a Surface (pass `null`): `codec.configure(format, null, null, 0)`
- Output format is typically YUV_420_888 — need YUV→RGB conversion
- Use `Image` API (API 21+) for cleaner buffer access than raw ByteBuffer
- Color format varies by vendor — `COLOR_FormatYUV420Flexible` is the safest choice (supported by nearly all devices)
- Process frames sequentially: dequeue input buffer → feed encoded data → dequeue output buffer → convert → process → release

**Encode pipeline:**
```
SBS Bitmap → draw to OpenGL Surface (EGL) → MediaCodec encoder → MediaMuxer → MP4
```

**Key implementation notes:**
- Use Surface-based input for the encoder (avoids color format issues)
- `createInputSurface()` gives you a Surface, render SBS frames to it via EGL
- MediaMuxer handles container format (MP4) and muxes video + audio tracks
- Copy audio track separately: extract audio from source, add to output MediaMuxer

**YUV to RGB conversion (for decode):**
```kotlin
// Using Android's built-in YuvImage → compress → decode path
// OR more efficiently, use RenderScript/Vulkan compute
// OR manual conversion for YUV_420_888:

fun yuv420ToRgb(image: Image, width: Int, height: Int): Bitmap {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    // Standard YUV→RGB matrix conversion
    // R = Y + 1.402 * (V - 128)
    // G = Y - 0.344136 * (U - 128) - 0.714136 * (V - 128)
    // B = Y + 1.772 * (U - 128)
}
```

### Option B: Build FFmpegKit from Archived Source

FFmpegKit was retired January 2025. Prebuilt binaries removed from Maven Central. However:
- Source code is still on GitHub (`arthenica/ffmpeg-kit`, archived)
- Community forks exist for 16KB page size support (`moizhassankh/ffmpeg-kit-android-16KB`)
- Can be built locally and included as a local AAR dependency
- Provides simpler API for decode/encode but adds ~35-40MB to APK

**Only use this option if MediaCodec proves too painful.** For a personal tool, the overhead is acceptable, but the build process is involved.

---

## ONNX Runtime Integration

### Gradle Dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}
```

### Model Loading

```kotlin
// Load model from app's files directory (not assets — avoids JVM heap OOM)
// Copy ONNX file to filesDir on first launch, then load from path
val session = OrtEnvironment.getEnvironment().use { env ->
    val options = OrtSession.SessionOptions().apply {
        // Enable NNAPI for hardware acceleration
        addNnapi()
        // Set thread count for CPU fallback
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
    }
    env.createSession(modelFilePath, options)
}
```

### Critical: Load from File Path, Not Assets

ONNX Runtime Android has a known issue where loading from Android assets/resources copies the entire model to JVM heap, causing OOM for large models (~195MB FP16). Always:
1. Bundle the model as a raw resource or download on first launch
2. Copy to `context.filesDir` 
3. Load via file path: `env.createSession(filePath, options)`

### Inference

```kotlin
fun runDepthInference(bitmap: Bitmap): FloatArray {
    // 1. Preprocess bitmap to float tensor
    val inputTensor = preprocessBitmap(bitmap) // [1, 3, 518, 518]
    
    // 2. Create OnnxTensor
    val ortInput = OnnxTensor.createTensor(env, inputTensor, longArrayOf(1, 3, 518, 518))
    
    // 3. Run inference
    val results = session.run(mapOf("image" to ortInput))
    
    // 4. Extract depth map
    val depthMap = (results[0].value as Array<Array<FloatArray>>)[0] // [518, 518]
    
    return depthMap.flatten().toFloatArray()
}
```

---

## Model Acquisition

The ONNX model file needs to be obtained before the agent starts building:

### Pre-exported ONNX (easiest)

```bash
# Depth Anything V2 Base — static 518x518
# From fabio-sim/Depth-Anything-ONNX GitHub releases
# Download: depth_anything_v2_vitb.onnx (FP32, ~390MB)

# OR from HuggingFace:
# onnx-community/depth-anything-v2-base
pip install huggingface-hub
huggingface-cli download onnx-community/depth-anything-v2-base --include "onnx/*"
```

### FP16 Quantization

```python
# Convert FP32 ONNX to FP16 (~195MB)
import onnx
from onnxconverter_common import float16

model = onnx.load("depth_anything_v2_vitb.onnx")
model_fp16 = float16.convert_float_to_float16(model)
onnx.save(model_fp16, "depth_anything_v2_vitb_fp16.onnx")
```

### Model Delivery Strategy

For a personal-use app, the simplest approach:
1. Include the FP16 ONNX file (~195MB) as a downloadable asset
2. On first launch, download from a URL (e.g., hosted on Google Drive or GitHub LFS) to `context.filesDir`
3. Show a one-time download progress screen
4. Subsequent launches load from local storage

Alternatively, bundle in APK as a raw resource (makes APK ~200MB+ but avoids download step).

---

## User Interface

### Screens

1. **Home Screen**
   - "Select Image" button
   - "Select Video" button  
   - Recent conversions list (thumbnails)

2. **Settings / Adjustment Screen**
   - Eye separation slider (5-80, default 30)
   - Depth intensity slider (affects depth map contrast before warping)
   - Depth blur strength slider (3-33, odd values, default 7)
   - Output format selector (Half-SBS vs Full-SBS)
   - Output arrangement (Parallel vs Cross-eyed)

3. **Processing Screen**
   - Progress bar (determinate for video, indeterminate spinner for image)
   - Estimated time remaining (for video)
   - Cancel button
   - Preview of last processed frame (for video)

4. **Preview Screen**
   - Full-screen SBS preview of the result
   - Pinch to zoom
   - Save to gallery button
   - Share button
   - "Adjust and reprocess" button

### Output Formats

- **Full SBS:** Left and right views at full source resolution, side by side. Output width = 2× source width. Best quality but large files.
- **Half SBS (default):** Each eye view is half-width. Output matches source resolution. Standard for most VR players and displays.

---

## Project Structure

```
sbs-converter/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/sbsconverter/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ui/
│   │   │   │   ├── screens/
│   │   │   │   │   ├── HomeScreen.kt
│   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   ├── ProcessingScreen.kt
│   │   │   │   │   └── PreviewScreen.kt
│   │   │   │   ├── components/
│   │   │   │   └── theme/
│   │   │   ├── processing/
│   │   │   │   ├── DepthEstimator.kt        # ONNX Runtime wrapper
│   │   │   │   ├── SbsWarper.kt             # Mesh warp + hole filling
│   │   │   │   ├── TemporalSmoother.kt      # EMA depth smoothing
│   │   │   │   ├── ImageProcessor.kt        # Single image pipeline
│   │   │   │   ├── VideoProcessor.kt        # Video frame loop
│   │   │   │   └── YuvConverter.kt          # YUV↔RGB conversion
│   │   │   ├── video/
│   │   │   │   ├── VideoDecoder.kt          # MediaCodec decode
│   │   │   │   ├── VideoEncoder.kt          # MediaCodec encode
│   │   │   │   └── AudioCopier.kt           # Extract + mux audio
│   │   │   ├── service/
│   │   │   │   └── ProcessingService.kt     # Foreground service
│   │   │   ├── model/
│   │   │   │   ├── ProcessingConfig.kt      # User settings data class
│   │   │   │   └── ProcessingResult.kt
│   │   │   └── util/
│   │   │       ├── BitmapUtils.kt
│   │   │       └── ModelManager.kt          # Download/cache ONNX model
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── models/                                   # FP16 ONNX model (gitignored)
│   └── depth_anything_v2_vitb_fp16.onnx
└── build.gradle.kts
```

---

## Build Order (For Coding Agent)

Implement in this order. Each phase should be testable independently.

### Phase 1: Single Image Depth Map (Proof of Concept)

**Goal:** Load an image, run depth inference, display the depth map.

1. Set up Android project with Kotlin + Jetpack Compose
2. Add ONNX Runtime Android dependency
3. Implement `ModelManager.kt` — copy ONNX model to filesDir, handle first-run download
4. Implement `DepthEstimator.kt`:
   - Load ONNX session from file path (NOT assets)
   - Enable NNAPI execution provider
   - Preprocess bitmap: resize to 518×518, normalize with ImageNet mean/std, convert to float tensor [1, 3, 518, 518]
   - Run inference
   - Post-process: normalize output to 0-1, resize to original dimensions
5. Simple UI: pick image → show original + depth map side by side
6. **Test:** Verify depth map looks correct (closer objects = brighter/darker depending on model output convention)

### Phase 2: SBS Warp for Images

**Goal:** Take an image + depth map and produce a valid SBS output.

1. Implement `SbsWarper.kt`:
   - Mesh warping algorithm (create vertex grid, displace by depth, rasterize with texture mapping)
   - Depth map pre-blur (Gaussian)
   - Background extrapolation hole-filling
   - Generate left eye and right eye views
   - Combine into Half-SBS or Full-SBS output
2. Implement `ImageProcessor.kt` — orchestrate the full image pipeline
3. UI: Add settings sliders (eye separation, depth blur) + save to gallery
4. **Test:** Open SBS output in a VR player or cross-eyed viewer. Verify 3D depth is perceptible and natural.

### Phase 3: Video Decode + Encode

**Goal:** Decode a video frame-by-frame and re-encode to MP4 (without depth processing — just passthrough to verify the I/O pipeline).

1. Implement `VideoDecoder.kt`:
   - MediaExtractor to select video track
   - MediaCodec decoder in ByteBuffer mode (no Surface)
   - YUV→RGB conversion
   - Frame iterator/callback pattern
2. Implement `YuvConverter.kt` — handle YUV_420_888 → ARGB_8888
3. Implement `VideoEncoder.kt`:
   - MediaCodec encoder with Surface input
   - EGL context + surface setup
   - Render Bitmaps to encoder surface
4. Implement `AudioCopier.kt` — extract audio track from source, mux into output
5. Wire up: decode → (no processing) → encode → save
6. **Test:** Output video should be identical to input (visual passthrough verification)

### Phase 4: Video SBS Processing

**Goal:** Full video pipeline with depth + temporal smoothing + SBS.

1. Implement `TemporalSmoother.kt`:
   - EMA with depth-aware adaptive alpha
   - Buffer management for previous frame depth map
2. Implement `VideoProcessor.kt`:
   - Orchestrate: decode → depth → temporal smooth → SBS warp → encode
   - Progress reporting callback
3. Implement `ProcessingService.kt`:
   - Foreground service with persistent notification
   - Progress updates
   - Handle process kill / battery optimization
4. Wire up full video pipeline through the service
5. **Test:** Convert a 10-30 second video. Verify temporal consistency (no obvious flickering). View in VR/SBS player.

### Phase 5: Polish

1. Processing queue (multiple files)
2. Estimated time remaining based on per-frame timing
3. Preview screen with pinch-zoom
4. Share functionality
5. Error handling (unsupported codecs, OOM, etc.)
6. Model download with retry + progress UI

---

## Known Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| ONNX model OOM on load | High | Load from file path, never from assets/resources. Use FP16 model. |
| YUV color format varies by device | Medium | Use `COLOR_FormatYUV420Flexible` and `Image` API. Test on multiple devices. |
| Temporal flickering in video | Medium | EMA depth smoothing. Depth-aware alpha for moving objects. Accept some flicker for v1. |
| Long processing times for video | Low (acceptable) | Foreground service. User expects offline processing. Show progress. |
| MediaCodec encode complexity | Medium | Surface-based input with EGL. Reference BigFlake's MediaCodec examples. |
| Audio sync after processing | Low | Copy audio track directly without re-encoding. Match video PTS. |
| Model too large for APK | Low | Host model externally, download on first launch. Or accept large APK for personal use. |

---

## Key Resources

- **Depth Anything V2 ONNX exports:** https://github.com/fabio-sim/Depth-Anything-ONNX/releases
- **ONNX Runtime Android docs:** https://onnxruntime.ai/docs/tutorials/mobile/
- **ONNX Runtime Android Maven:** `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`
- **MediaCodec reference implementation:** https://bigflake.com/mediacodec/
- **SBS reference implementation:** https://github.com/yushan777/SBS-2DTo3D (Python — port warp logic to Kotlin)
- **DIBR hole-filling survey:** Search "depth image based rendering disocclusion filling" for academic references
- **Video Depth Anything (future upgrade):** https://github.com/DepthAnything/Video-Depth-Anything

---

## Future Enhancements (v2+)

- **Video Depth Anything integration** — once a reliable mobile export path (ExecuTorch or ONNX) exists for the temporal model, replace per-frame DA V2 + EMA smoothing
- **GPU-accelerated warp** — move SBS warp to OpenGL ES fragment shader for faster processing
- **Batch processing queue** — process multiple files while phone is idle/charging
- **Half-SBS live preview** — show approximate 3D effect before full processing
- **Anaglyph output** — red-cyan mode for quick previewing without 3D hardware
- **iOS port** — Core ML has official DA V2 Small support; would need separate implementation