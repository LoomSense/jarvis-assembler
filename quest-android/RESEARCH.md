# Native Android (Kotlin) Quest 3 app: research notes

Written Sun 15 Nov 2026 for phase 1. Everything marked **UNVERIFIED** has not been checked against a document or a
device. Nothing here has run on a Quest yet (no headset was connected to the build machine).

## 1. Passthrough Camera API (PCA) from native Android

| Item | Finding | Source |
|---|---|---|
| API | Standard Android **Camera2**; the forward RGB cameras of Quest 3 / 3S appear as camera devices | [PCA native overview](https://developers.meta.com/horizon/documentation/native/android/pca-native-overview/), [Spatial SDK PCA Kotlin API](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-pca-kotlin-api/) |
| Permissions | `android.permission.CAMERA` (passthrough and avatar cameras) or `horizonos.permission.HEADSET_CAMERA` (passthrough only); the Horizon one is a runtime permission. Meta's samples request both. | same pages; `PermissionManager.kt` in [Meta-Passthrough-Camera-API-Samples](https://github.com/meta-quest/Meta-Passthrough-Camera-API-Samples) |
| Manifest | `<uses-permission android:name="horizonos.permission.HEADSET_CAMERA"/>`; Spatial Scanner showcase adds `<uses-feature android:name="horizonos.feature.HEADSET_CAMERA" android:required="false"/>`; CameraDemo uses `android.hardware.camera2.full` | samples |
| Minimum OS | **Horizon OS v74** (overview page, CameraDemo README). The Spatial SDK page says "Camera2 API is available on Horizon OS v76+". Treat **v76** as the safe minimum (the two pages disagree). | as above |
| Devices | Quest 3, Quest 3S only (`com.oculus.supportedDevices = quest3\|quest3s`) | CameraDemo manifest |
| Vendor tags | `com.meta.extra_metadata.camera_source` (0x80004d00, `0` = passthrough RGB), `com.meta.extra_metadata.position` (0x80004d01, `0` left, `1` right) | overview page; `QuestCameraHelpers.kt` |
| Resolutions | 1280×960; 1280×1280 from v83; 1728×1728 from v209 (max). Scanner showcase lists 320×240 … 1280×960 for YUV. | overview page |
| Rate / latency | "Data rate: 60Hz", "Image capture latency: 20-40ms" | overview page |
| Intrinsics | `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` = [fx, fy, cx, cy, s] in pixels of `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`; scale to the delivered size. Read by the Spatial Scanner showcase `CameraController.getCameraProperties`. | showcase code ([Meta-Spatial-SDK-Samples](https://github.com/meta-quest/Meta-Spatial-SDK-Samples) `Showcases/meta_spatial_scanner`) |
| Lens pose | `LENS_POSE_TRANSLATION` (m) and `LENS_POSE_ROTATION` (quaternion x,y,z,w), **relative to the head (HMD centre)**: the Spatial SDK page says the config holds "the lens position and rotation relative to the center of the HMD". Read in both CameraDemo and the showcase. | as above |
| Lens pose → world | Showcase converts with `Quaternion(r[3], -r[0], -r[1], r[2]).inverse() * Quaternion(180°,0,0)` and `t = (x, y, -z)` into Spatial SDK axes, then multiplies with the head pose. **UNVERIFIED** for our use: confirm with a marker at a known place. `LENS_POSE_REFERENCE` value on Quest: **UNVERIFIED**. | showcase |
| Per-frame camera pose | No per-frame pose in the capture result is documented. Pose = head pose at the frame's `SENSOR_TIMESTAMP` × head-from-lens. Timestamp clock base (REALTIME vs. MONOTONIC) and the exact latency to compensate: **UNVERIFIED**. A press article says Camera2 returns the headset pose obtained through OpenXR; not found in Meta's docs (**UNVERIFIED**). | [UploadVR](https://www.uploadvr.com/quest-passthrough-camera-api-experimental-out-now/) |
| Distortion | `LENS_DISTORTION` not read by Meta's samples; whether frames are already undistorted: **UNVERIFIED** (MarkerTracker assumes zero distortion for now). | - |
| Known issues | RGB channels may swap after first install on v74 following an OS flash (reboot fixes); the image covers a smaller rectangle than the user sees. | overview page |

## 2. Framework: Meta Spatial SDK (chosen) vs OpenXR + own renderer

| | **Meta Spatial SDK 0.15.0** (chosen) | OpenXR (C++/NDK) + own renderer |
|---|---|---|
| Language / build | Kotlin, plain Gradle + Maven Central (`com.meta.spatial:*`), builds with `./gradlew assembleDebug` (done here) | C++ via NDK/CMake, JNI for Camera2/UI; much more code |
| Official samples | [Meta-Spatial-SDK-Samples](https://github.com/meta-quest/Meta-Spatial-SDK-Samples): MixedRealitySample (passthrough, MRUK), Object3DSampleIsdk (hands), Spatial Scanner showcase (**PCA + OpenCV + ML in Kotlin**), Mruk/anchors | [OpenXR SDK samples](https://github.com/meta-quest) (XrPassthrough etc.); no PCA + renderer sample in one place |
| Passthrough | `scene.enablePassthrough(true)` (MixedRealitySample) | `XR_FB_passthrough` by hand |
| glTF | `Mesh(Uri)` loads `.glb` (local `apk://`/file or network with `NetworkedAssetLoader` + `OkHttpAssetFetcher`, FeatureDevSample); node animation via `meta-spatial-sdk-animation` | own loader (e.g. cgltf/tinygltf) + renderer |
| Hands | ISDK (`meta-spatial-sdk-isdk`), `AvatarBody` hand entities; joint access for fingertips (exact API: **UNVERIFIED**, see BodyTrackingSample) | `XR_EXT_hand_tracking` |
| Anchors / scene | `meta-spatial-sdk-mruk` (table plane, anchors), MixedRealitySample | `XR_FB_spatial_entity`, scene by hand |
| Custom shaders | Yes: GLSL `.vert/.frag` in `app/src/shaders`, compiled to SPIR-V by the Meta Spatial Gradle plugin with the NDK's `glslc`; `SceneMaterial.custom(name, attributes)` with Vector4/Texture2D attributes, `setBlendMode(BlendMode.TRANSLUCENT)`, `setDepthWrite(DISABLE)`; includes give `vertexOut.worldNormal/worldPosition` and `g_ViewUniform.eyeCenter0` (enough for fresnel). Marked "experimental" by Meta. **Built here:** `ghost_fresnel.vert/.frag` compile and ship in the APK (`assets/ghost_fresnel.*.spv`, 10 variants). | Full control |
| Text | Compose (or Android View) UIs rendered into world-space panels (`ComposeViewPanelRegistration` + `Entity.createPanelEntity`); full Android text stack, so Greek glyphs, kerning and font fallback come for free. Panels render either as a **compositor quad layer** (`PanelRenderMode.Layer`, `QuadLayerConfig`, `LayerFilters`: sharpest text, sampled by the compositor) or as a **mesh** in the scene (`PanelRenderMode.Mesh`: depth-sorted with the 3D content, needed for labels that sit on the object). No SDF 3D text component found in the SDK jars or samples: text is rasterised at the panel's resolution (`DpPerMeterDisplayOptions`), not SDF (**UNVERIFIED** quality at small angular sizes; layer count limits of the compositor: **UNVERIFIED**). | Own SDF text (msdfgen atlas incl. Greek), most work |
| Animation | Kotlin `SystemBase.execute()` per frame (used here for the fps counter), animation module for glTF clips, tweening in samples (PremiumMediaSample `tweenEngine`) | own |
| Risk | SDK pre-1.0 (0.x), APIs move between minors; Spatial plugin pins Kotlin 2.2.0 | months of engine work |

**Decision: Meta Spatial SDK.** It is the only option with official Kotlin samples for every requirement (passthrough,
PCA + OpenCV, hands, anchors, glTF, panels), it builds from the Gradle CLI, and it supports custom shaders (fresnel
ghost, translucency) and high-quality world-space text panels, which the visual-quality criterion (R12) needs.
The weak spot is 3D text on the object: labels will be small mesh-mode panels with leader-line meshes; if their
sharpness is not good enough on device, the fallback is a custom SDF text shader (msdf atlas texture + the same
`SceneMaterial.custom` path), which the shader pipeline above already supports.

## 3. OpenCV on Android: markers and PnP

| Item | Finding | Source |
|---|---|---|
| Gradle | Official `org.opencv:opencv` on Maven Central (4.9.0 … 5.1.0). We use **4.10.0**, the version Meta's Spatial Scanner showcase uses. | [Maven metadata](https://repo1.maven.org/maven2/org/opencv/opencv/maven-metadata.xml), showcase `libs.versions.toml` |
| ArUco / AprilTag | In the main `objdetect` module since OpenCV 4.7: `org.opencv.objdetect.ArucoDetector`, `Objdetect.getPredefinedDictionary(Objdetect.DICT_APRILTAG_36h11)`; compiles here. | `vision/MarkerTracker.kt` |
| PnP | `Calib3d.solvePnP(..., Calib3d.SOLVEPNP_IPPE_SQUARE)` for one square marker, `Calib3d.projectPoints` for the reprojection error, `Calib3d.Rodrigues`. Compiles here; not run. | same |
| Native lib | `OpenCVLoader.initLocal()` loads `libopencv_java4.so` (21 MB arm64) from the APK. | APK listing |
| Size | OpenCV ships 4 ABIs (~110 MB); `abiFilters += "arm64-v8a"` halves the APK. | build |

## 4. Open questions (need a headset)

1. Does `LiveCameraSource.discover()` see two passthrough cameras with intrinsics and lens pose on our Quest 3 OS
   version? (logged as AGQ `camera` at start-up)
2. Lens-pose axis conversion and timestamp alignment (§1): measure with a printed marker at a known spot.
3. Render fps with passthrough + 2 panels + GLB + OpenCV at 10 Hz on the camera thread (target 72 fps).
4. Compose panel text sharpness at label size (layer vs. mesh mode).
5. Greek TTS: server `/api/tts` works; Android `TextToSpeech` Greek voice on Horizon OS is **UNVERIFIED**.
