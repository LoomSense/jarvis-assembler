# Quest 3 native Unity app: research notes (2026-11-15)

Target editor: Unity 6.3 LTS (6000.3.28f1), Apple silicon. Package versions were read live from the registries on 2026-11-15 (`curl <registry>/<package>`, `dist-tags.latest`).
Anything without a confirming source is marked **UNVERIFIED**.

## 1. Passthrough Camera API (PCA)

| Item | Finding | Source |
|---|---|---|
| Official sample | `oculus-samples/Unity-PassthroughCameraApiSamples`: CameraViewer, CameraToWorld, BrightnessEstimation, MultiObjectDetection (YOLO + Inference Engine), ShaderSample. Needs Unity 6000.0.38f1+, MRUK v81+, Quest 3/3S, HzOS v74+. XR Simulator is not supported. Link v2.1+ works. | [repo](https://github.com/oculus-samples/Unity-PassthroughCameraApiSamples) |
| Devices / OS | Quest 3 and 3S only. Horizon OS v74+. | [overview](https://developers.meta.com/horizon/documentation/unity/unity-pca-overview/) |
| Permission | `horizonos.permission.HEADSET_CAMERA`. The migration guide says to **remove** `android.permission.CAMERA` (avoids a double prompt). The overview accepts either. | [migration](https://developers.meta.com/horizon/documentation/unity/unity-pca-migration-from-webcamtexture/), [overview](https://developers.meta.com/horizon/documentation/unity/unity-pca-overview/) |
| Unity/MRUK req | Unity 6000.0.66f2+. Passthrough must be enabled. **OVRManager > "Enabled Passthrough Camera Access"** must be ticked. If you hand-edit the manifest on Unity 6+, re-add the permission yourself. | [PCA docs](https://developers.meta.com/horizon/documentation/unity/unity-pca-documentation) |
| Built-in component | `Meta.XR.PassthroughCameraAccess` (MonoBehaviour) in **MRUK**, introduced in **MRUK v81**. It replaces the old `WebCamTexture` + `PassthroughCameraUtils` sample scripts. | [migration](https://developers.meta.com/horizon/documentation/unity/unity-pca-migration-from-webcamtexture/) |
| Frame (GPU) | `Texture GetTexture()`. Also `IsPlaying`, `IsUpdatedThisFrame`, and `TargetMaterial`/`TexturePropertyName` (auto-binds the texture to a material). | [API v207](https://developers.meta.com/horizon/reference/mruk/v207/class_meta_x_r_passthrough_camera_access) |
| Frame (CPU) | `NativeArray<Color32> GetColors()`. Expensive. Do not cache, modify or dispose the array. | [API v207](https://developers.meta.com/horizon/reference/mruk/v207/class_meta_x_r_passthrough_camera_access) |
| Intrinsics | `CameraIntrinsics Intrinsics`, with `FocalLength`, `PrincipalPoint`, `SensorResolution` and `LensOffset`. Static; available once enabled and permission is granted. The field types are not stated (likely Vector2 in px, **UNVERIFIED**). Intrinsics refer to `SensorResolution`, so scale them to `CurrentResolution` (**UNVERIFIED**, check the sample's CameraToWorld). | [PCA docs](https://developers.meta.com/horizon/documentation/unity/unity-pca-documentation), [API](https://developers.meta.com/horizon/reference/mruk/v207/class_meta_x_r_passthrough_camera_access) |
| Pose | `Pose GetCameraPose()` gives the world pose at the latest image `Timestamp`. It replaces `PassthroughCameraUtils.GetCameraPoseInWorld(eye)`. | [migration](https://developers.meta.com/horizon/documentation/unity/unity-pca-migration-from-webcamtexture/) |
| Helpers | `Ray ViewportPointToRay(Vector2 vp, Pose? pose=null)` (viewport 0,0 is bottom-left) and `Vector2 WorldToViewportPoint(Vector3, Pose?)`. Pair with MRUK `EnvironmentRaycastManager.Raycast(ray, out EnvironmentRaycastHit)` (depth) to get a 3D point. | [migration](https://developers.meta.com/horizon/documentation/unity/unity-pca-migration-from-webcamtexture/), [PCA docs](https://developers.meta.com/horizon/documentation/unity/unity-pca-documentation) |
| Timestamp | `DateTime Timestamp` of the latest image. `GetFrameMetadata(DateTime)` (exposure, ISO, focus) needs v209+. | [PCA docs](https://developers.meta.com/horizon/documentation/unity/unity-pca-documentation) |
| Camera select | `CameraPosition` (`CameraPositionType` Left/Right). `RequestedResolution` falls back to the nearest smaller supported size. `CurrentResolution`. `MaxFramerate` (default 60; change only while disabled). `static GetSupportedResolutions(pos)`. `static IsSupported`. | [API v207](https://developers.meta.com/horizon/reference/mruk/v207/class_meta_x_r_passthrough_camera_access) |
| Resolutions | v81+: 320x240, 640x480, 800x600, **1280x960**. v83+: up to 1280x1280. **v209+: 1728x1728** (1:1). Always read `CurrentResolution`. | [PCA docs](https://developers.meta.com/horizon/documentation/unity/unity-pca-documentation) |
| Rate / latency | 60 Hz. **20–40 ms** capture latency. | [overview](https://developers.meta.com/horizon/documentation/unity/unity-pca-overview/) |

## 2. Meta XR SDK packages (Meta registry, latest on 2026-11-15)

| Package ID | Latest | Notes |
|---|---|---|
| `com.meta.xr.sdk.core` | **209.0.0** (2026-11-10) | unity 6000.0. Depends on `com.unity.xr.hands` 1.7.2. Previous: 207.0.1. |
| `com.meta.xr.mrutilitykit` | **209.0.0** | Depends on core 209.0.0, `com.unity.nuget.newtonsoft-json` 3.0.2, `com.unity.ai.navigation`, TMP. Contains PassthroughCameraAccess. |
| `com.meta.xr.sdk.interaction` | 209.0.0 | Interaction SDK essentials. |
| `com.meta.xr.sdk.interaction.ovr` | 209.0.0 | ISDK on OVR (core + interaction). |
| `com.meta.xr.sdk.platform` | 209.0.0 | Not needed for a hackathon. |
| `com.meta.xr.sdk.audio` | 209.0.0 | Optional. |
| `com.meta.xr.sdk.haptics` | 209.0.0 | Optional. |
| `com.meta.xr.sdk.voice` | 85.0.1 | Lags behind. Optional. |

Source: `https://npm.developer.oculus.com/<id>` registry JSON, queried live. The registry search also lists `com.meta.xr.sdk.utilities`, `com.meta.xr.simulator` and `com.meta.xr.sdk.avatars`.

- **All-in-one:** "Meta XR All-in-One SDK" is on the Asset Store ([UPM doc](https://developers.meta.com/horizon/documentation/unity/unity-package-manager/)). I found no `com.meta.xr.sdk.all` on the npm registry (404), so install the individual packages.
- **Install route:** Meta recommends the Asset Store. The "advanced" route is the npm registry at https://npm.developer.oculus.com ([UPM doc](https://developers.meta.com/horizon/documentation/unity/unity-package-manager/)). Meta's doc describes importing tarballs. Using it as a **scoped registry** works because the registry speaks the npm protocol (verified by curl), but Meta's page does not document this route (**UNVERIFIED as official**).
- **Unity version:** MRUK 203+ requires Unity 6000.0.66f2+ ([MRUK 203 notes](https://developers.meta.com/horizon/downloads/package/meta-xr-mr-utility-kit-upm/203.0/)). The anchors doc recommends Unity 6.1+ ([anchors](https://developers.meta.com/horizon/documentation/unity/unity-spatial-anchors-persist-content/)). Unity 6.3 LTS should be fine. Packages declare `unity: 6000.0`.
- **XR plugin:** Meta recommends the **Unity OpenXR plugin** (`com.unity.xr.openxr`, latest 1.19.0) with the **Meta XR feature group** for Android enabled. The Oculus XR Plugin (`com.unity.xr.oculus` 4.5.6) is **deprecated** and gets no new features ([project setup](https://developers.meta.com/horizon/documentation/unity/unity-project-setup/), [compat](https://developers.meta.com/horizon/documentation/unity/unity-and-openxr-compatibility)). MRUK and passthrough need OVRCameraRig/OVRManager in the scene ([compat](https://developers.meta.com/horizon/documentation/unity/unity-and-openxr-compatibility)). PCA on the OpenXR backend is the default sample path (**UNVERIFIED** explicitly).
  - `com.unity.xr.meta-openxr` (2.7.0) is Unity's AR Foundation-style Meta package. Meta's compat page mentions it, but it is not required by the Meta XR Core path (**UNVERIFIED**). Don't add it unless the Project Setup Tool asks.
- **Spatial anchors:** `OVRSpatialAnchor` with `SaveAnchorAsync`, `LoadUnboundAnchorsAsync` and `UnboundAnchor.BindTo()`. Enable Anchor Support ([doc](https://developers.meta.com/horizon/documentation/unity/unity-spatial-anchors-persist-content/)).
- **Hands:** OVRManager Hand Tracking Support (Controllers And Hands / Hands Only) auto-adds the feature and permission ([hands setup](https://developers.meta.com/horizon/documentation/unity/unity-handtracking-hands-setup/)). Use `OVRHand`/`OVRSkeleton` (Core) or Interaction SDK (`com.meta.xr.sdk.interaction.ovr`) for poke/grab.
- **Tables / planes:** MRUK `MRUKAnchor.SceneLabels.TABLE` (a volume anchor), `anchor.HasAnyLabel(...)`, `room.FindLargestSurface(labelFlags)` and `room.GetKeyWall(out scale)` ([MRUKAnchor v83](https://developers.meta.com/horizon/reference/mruk/v83/class_meta_x_r_m_r_utility_kit_m_r_u_k_anchor), [content placement](https://developers.meta.com/horizon/documentation/unity/unity-mr-utility-kit-content-placement)). Requires the spatial-data permission `com.oculus.permission.USE_SCENE`. MRUK auto-requests it when Data Source = Device and Load Scene on Startup is on. Don't also request it via OVRManager ([spatial perm](https://developers.meta.com/horizon/documentation/unity/unity-spatial-data-perm/)).

## 3. Android player settings and manifest

| Setting | Value | Source |
|---|---|---|
| Build profile | Use the **Meta Quest** build profile (Unity 6.1+). It auto-installs the OpenXR plugin. | [Unity 6.3 manual](https://docs.unity.com/en-us/engine/6000.3/manual/xr/configuring-project-for/meta-quest-develop/build-profile) |
| Graphics API | **Vulkan** (profile default) | same |
| Scripting backend / arch | **IL2CPP**, **ARM64** | same |
| Stereo path | Single Pass Instanced | same |
| Min API | Profile default is 29. Meta allows a minSdk of **32** | same, [Meta blog](https://developers.meta.com/horizon/blog/meta-quest-apps-android-14-march-1) |
| Target API | **34** (store-enforced for new apps since 2026-03-01. HzOS v76+ is Android 14). The profile default is 32, so raise it. | [Meta blog](https://developers.meta.com/horizon/blog/meta-quest-apps-android-14-march-1), [min OS](https://developers.meta.com/horizon/documentation/unity/min-os-versions/) |
| Color space | Linear (**UNVERIFIED**, not on fetched pages; the Project Setup Tool flags it) | — |
| Texture compression | ASTC (**UNVERIFIED**) | — |
| MSAA | 4x | [conf settings](https://developers.meta.com/horizon/documentation/unity/unity-conf-settings/) |

- **Project Setup Tool** (Meta > Tools > Project Setup Tool): use Fix All and Apply All on the Android tab ([project setup](https://developers.meta.com/horizon/documentation/unity/unity-project-setup/)).
- **Manifest:** generate it with **Meta > Tools > Create store-compatible AndroidManifest.xml**. The SDK writes `com.oculus.supportedDevices` and the hand-tracking entries at build time. A custom `Assets/Plugins/Android/AndroidManifest.xml` takes precedence ([conf settings](https://developers.meta.com/horizon/documentation/unity/unity-conf-settings/), [hands](https://developers.meta.com/horizon/documentation/unity/unity-handtracking-hands-setup/)). The processor class is named `OVRManifestPreprocessor` (**UNVERIFIED**).
- **Entries to expect:**
  - `horizonos.permission.HEADSET_CAMERA` (sourced above)
  - `com.oculus.permission.USE_SCENE` (sourced above)
  - `com.oculus.permission.HAND_TRACKING` + `<uses-feature android:name="oculus.software.handtracking" android:required="false"/>` ([hands](https://developers.meta.com/horizon/documentation/unity/unity-handtracking-hands-setup/))
  - `com.oculus.intent.category.VR` on the launcher activity (**UNVERIFIED** on fetched pages; generated by the tool)
  - `android.permission.INTERNET`: set Player > Internet Access = Require (**UNVERIFIED**)

## 4. Runtime GLB loading (glTFast)

| Package | Latest (Unity registry) | Note |
|---|---|---|
| `com.unity.cloud.gltfast` | **6.21.0** (2026-11-11). 6.20.0 (2026-08-27) is the safer choice. 7.0.0-exp.1 exists. | unity 6000.0 |
| `com.unity.meshopt.decompress` | 0.1.0-preview.7 (2024) is the last non-exp. 0.3.0-exp.1 is the latest tag | required for `EXT_meshopt_compression` ([features](https://docs.unity3d.com/Packages/com.unity.cloud.gltfast@6.21/manual/features.html)) |
| `com.unity.cloud.draco` | **5.6.0** stable. 6.0.0-pre.1 is the `latest` tag | required for `KHR_draco_mesh_compression` |
| `com.unity.cloud.ktx` | not checked | for `KHR_texture_basisu` |

- **Load:** `var g = new GltfImport(); await g.Load(url | byte[] | NativeArray<byte>.ReadOnly); await g.InstantiateMainSceneAsync(parent);`. A `true` return only means no critical error. Loads go through UnityWebRequest by default ([runtime import](https://docs.unity3d.com/Packages/com.unity.cloud.gltfast@6.21/manual/ImportRuntime.html)). Also available: `LoadGltfBinary`, `LoadFile` and `LoadStream`.
- **Animation:** `ImportSettings.AnimationMethod` = `None | Legacy | Mecanim | Playables` (Playables removed, "not usable in builds"). Mecanim clips import but are not assigned or playable without extra work. **Use Legacy** ([enum](https://docs.unity3d.com/Packages/com.unity.cloud.gltfast@6.21/api/GLTFast.AnimationMethod.html), [features](https://docs.unity3d.com/Packages/com.unity.cloud.gltfast@6.21/manual/features.html)). Clips come from `GltfImport.GetAnimationClips()` (**UNVERIFIED** name).
- **Final-frame transforms:** `clip.SampleAnimation(go, clip.length)` sets the animated properties at that time ([SampleAnimation](https://docs.unity.com/en-us/engine/6000.3/script-reference/unityengine/animationclip/sampleanimation)). Runtime use with legacy clips is **UNVERIFIED**. The fallback is `Animation[name].time = len; Animation.Sample()`.
- **Handedness:** glTFast negates **X** for positions/normals and **Y, Z** for quaternions (source `Runtime/Scripts/Jobs.cs` lines ~771–789 in [com.unity.cloud.gltfast](https://github.com/Unity-Technologies/com.unity.cloud.gltfast)). A glTF point `(x,y,z)` becomes Unity `(-x,y,z)`. Apply the same flip to any server-side coordinates.

## 5. WebSocket client

- **NativeWebSocket** `com.endel.nativewebsocket` ([repo](https://github.com/endel/NativeWebSocket), Apache-2.0):
  - `https://github.com/endel/NativeWebSocket.git#upm` gives 1.1.6.
  - `#upm-2` gives **2.0.8** (current major).
- **2.x API:**
  - `new WebSocket(url[, headers])`
  - `await Connect()`, `Send(byte[])`, `SendText(string)`, `Close()`
  - events `OnOpen`, `OnMessage(byte[])`, `OnError`, `OnClose`
  - In 2.x, Unity dispatches through `SynchronizationContext`, so **remove** `DispatchMessageQueue()` from `Update()`. 1.x required calling it in `Update()` on non-WebGL.
- **Android:** listed as a supported target. IL2CPP is not stated explicitly (**UNVERIFIED**; it uses `System.Net.WebSockets.ClientWebSocket`, which works under IL2CPP in practice).
- **Fallbacks:** plain `System.Net.WebSockets.ClientWebSocket` (built into .NET, no package), or HTTP polling via `UnityWebRequest`.

## 6. Marker tracking (free)

- **AprilTag:** `jp.keijiro.apriltag` **1.0.3** (2026-04-08) ([repo](https://github.com/keijiro/jp.keijiro.apriltag), [npm](https://www.npmjs.com/package/jp.keijiro.apriltag), BSD-2).
  - Scoped registry: `https://registry.npmjs.com`, scope `jp.keijiro`. Depends on `com.unity.burst`.
  - Native binaries: Win/mac/Linux x64, iOS arm64, **Android arm64**. IL2CPP is not stated, but it uses a native plugin, which is normal with IL2CPP (**UNVERIFIED**).
  - Only the **tagStandard41h12** family is supported.
  - API:
    - `new AprilTag.TagDetector(w, h, decimation=2)`
    - `ProcessImage(ReadOnlySpan<Color32> img, float fov, float tagSizeMeters)`
    - `DetectedTags` (`TagPose` with `ID`, `Position`, `Rotation`, camera-relative)
    - `Dispose()`
  - **Pose caveat (from source `Runtime/Unity/Internal/PoseEstimationJob.cs`):**
    - `focal = height/2/tan(fov/2)`, so the **vertical** FoV is in **radians** (the README says horizontal degrees; trust the code).
    - The principal point is assumed at the **image centre**, with fx = fy.
    - For Quest PCA, pass `fov = 2*atan(H/2/fy)` with fy scaled to the frame height. For better accuracy, fork `PoseEstimationJob` to use the real `PrincipalPoint`.
    - Pose conversion: `pos*(1,-1,1)`, quaternion `*(-1,1,-1,1)`.
    - The image row order (Unity bottom-up vs top-down) is **UNVERIFIED**. Test with a printed tag.
  - **Feed:** use `PassthroughCameraAccess.GetColors()` and a world pose `camPose * tagPose`.
- **Meta built-in:** MRUK **Trackables** (`MRUKTrackable`, `TrackableType`, events `TrackableAdded`/`TrackableRemoved`) detect **QR codes** (up to version 10) and keyboards ([trackables](https://developers.meta.com/horizon/documentation/unity/unity-mr-utility-kit-trackables), [QR](https://developers.meta.com/horizon/documentation/unity/unity-mr-utility-kit-qrcode-detection)).
  - Turn it on with MRUK > Scene Settings > Tracker Configuration > *QR Code Tracking Enabled*. Payload: `MarkerPayloadString`.
  - Needs Quest 3/3S and the spatial-data permission. Sample: `Assets/MRUKSamples/QRCodeDetection/` ([sample](https://developers.meta.com/horizon/documentation/unity/unity-sample-mruk-qr-code)).
  - Experimental, with community reports of breakage after OS updates. No raw camera access is needed: the OS gives a pose directly. **The simplest marker option.**
- **ArUco / OpenCV:** OpenCV for Unity is a paid asset. A free route is an OpenCV Android `.so` + P/Invoke (`solvePnP`), which takes too long for a hackathon (**UNVERIFIED** effort). C# PnP without OpenCV: compute a homography from the 4 tag corners + `K`, then decompose it (**UNVERIFIED**, roll your own). The AprilTag plugin already does the pose.

## 7. On-device ML

- **Inference Engine** (formerly Sentis): `com.unity.ai.inference` **2.6.1** (2026-04-02, unity 6000.0). `com.unity.sentis` stops at 2.1.3 (Unity registry, queried live).
- The PCA **MultiObjectDetection** sample runs **YOLOv9** (NMS, UInt8-quantized `.sentis`) through Inference Engine, pinned to **2.2.1** in the sample ([sample doc](https://developers.meta.com/horizon/documentation/unity/unity-sample-camera-object-detection), [Sentis doc](https://developers.meta.com/horizon/documentation/unity/unity-pca-sentis/), [repo](https://github.com/oculus-samples/Unity-PassthroughCameraApiSamples)). 2D boxes become 3D via `ViewportPointToRay` + `EnvironmentRaycastManager`. Meta notes that boxes do not align perfectly. No published per-frame latency (**UNVERIFIED**).

## 8. JSON

- `com.unity.nuget.newtonsoft-json` **3.2.2** (Unity registry, 2025-10-27). MRUK already pulls in ≥3.0.2.

## Recommended `Packages/manifest.json` (additions)

```json
{
  "scopedRegistries": [
    { "name": "Meta XR", "url": "https://npm.developer.oculus.com", "scopes": ["com.meta"] },
    { "name": "Keijiro", "url": "https://registry.npmjs.com", "scopes": ["jp.keijiro"] }
  ],
  "dependencies": {
    "com.meta.xr.sdk.core": "209.0.0",
    "com.meta.xr.mrutilitykit": "209.0.0",
    "com.meta.xr.sdk.interaction.ovr": "209.0.0",
    "com.unity.xr.openxr": "1.19.0",
    "com.unity.cloud.gltfast": "6.20.0",
    "com.unity.meshopt.decompress": "0.3.0-exp.1",
    "com.unity.cloud.draco": "5.6.0",
    "com.unity.nuget.newtonsoft-json": "3.2.2",
    "com.endel.nativewebsocket": "https://github.com/endel/NativeWebSocket.git#upm-2",
    "jp.keijiro.apriltag": "1.0.3",
    "com.unity.ai.inference": "2.2.1"
  }
}
```

- `com.unity.ai.inference` is optional. Use 2.2.1 to match the Meta sample, or 2.6.1 for the latest.
- Drop Draco or meshopt if the GLBs don't use them.
- If 209.0.0 misbehaves (released 5 days ago), fall back to **207.0.1** for all `com.meta.*` packages together. Keep the Meta versions identical across packages.
- With glTFast 6.20.0, meshopt 0.1.0-preview.7 compatibility is **UNVERIFIED**. Check the glTFast changelog if the import fails.

## Recommended Player settings

- Build Profile: **Meta Quest** (switch platform). XR Plug-in Management > OpenXR > Android, with the **Meta XR feature group** enabled.
- Run **Meta > Tools > Project Setup Tool** and use Fix All + Apply All (Android tab).
- Graphics API **Vulkan** only. **IL2CPP**, **ARM64** only. Stereo: Single Pass Instanced.
- Min API **32**, Target API **34**.
- Color space **Linear** (UNVERIFIED). Texture compression ASTC (UNVERIFIED). MSAA 4x.
- Internet Access = **Require** (for WebSocket/HTTP) (UNVERIFIED path).
- OVRManager on the camera rig:
  - Passthrough Support = Required/Supported, plus an `OVRPassthroughLayer` (underlay)
  - **Enabled Passthrough Camera Access** ticked
  - Hand Tracking Support = Controllers And Hands
  - Scene Support + Anchor Support on
- Manifest: Meta > Tools > Create store-compatible AndroidManifest.xml. Then confirm that `horizonos.permission.HEADSET_CAMERA`, `com.oculus.permission.USE_SCENE` and `com.oculus.permission.HAND_TRACKING` are present.

## Confirmed by installing (15 Nov 2026, Unity 6000.3.28f1)

- All packages above resolved from the scoped registries (Meta, npmjs for `jp.keijiro`) and the project compiles in batchmode.
- **glTFast 6.20.0 with meshopt 0.1.0-preview.7 does not compile**: `GltfImport.cs(24,8): error CS1029: #error: 'You have to update the meshoptimizer ... package'`. glTFast's asmdef wants `MESHOPT_IS_RECENT` (>= 0.2.0-exp.1), so the project uses **0.3.0-exp.1**.
- `Meta.XR.PassthroughCameraAccess` (MRUK 209, `Core/Scripts/PassthroughCameraAccess.cs`): `Vector2Int CurrentResolution`, `DateTime Timestamp`, `CameraIntrinsics Intrinsics` with `Vector2 FocalLength`, `Vector2 PrincipalPoint`, `Vector2Int SensorResolution`, `float LensFocalLength`; `NativeArray<Color32> GetColors()`, `Texture GetTexture()`, `Pose GetCameraPose(...)`, `bool IsPlaying`, `bool IsUpdatedThisFrame`.
- Assembly names for asmdef references: `meta.xr.mrutilitykit`, `endel.nativewebsocket`, `AprilTag.Runtime`, `glTFast`, `Unity.TextMeshPro` (inside `com.unity.ugui` 2.0), `Unity.XR.Management(.Editor)`, `Unity.XR.OpenXR(.Editor)`.
- `OVRPassthroughLayer` defaults to underlay; `OVRManager.isInsightPassthroughEnabled` is a public field.

## UNVERIFIED list

1. ~~Scoped-registry install of `com.meta.*`~~ works (confirmed above), though Meta documents the Asset Store or tarballs.
2. ~~Field types of `CameraIntrinsics`~~ (confirmed above); still open: whether the intrinsics must be scaled from `SensorResolution` to `CurrentResolution`.
3. PCA with OpenXR is not explicitly stated (it is the default path for Unity 6 / SDK v74+).
4. Whether `com.unity.xr.meta-openxr` is needed alongside Meta XR Core (believed not).
5. Linear colour space, ASTC, the `OVRManifestPreprocessor` name, `com.oculus.intent.category.VR`, and the INTERNET entry.
6. NativeWebSocket on Android IL2CPP.
7. AprilTag on IL2CPP, and its image row order.
8. glTFast `GetAnimationClips()` name, and `SampleAnimation` at runtime on legacy clips.
9. ~~meshopt 0.1.0-preview.7 with glTFast 6.20~~: incompatible (confirmed above); 0.3.0-exp.1 compiles, decoding on device not yet tested.
10. YOLO sample latency on Quest 3.
11. The HzOS minimum for PassthroughCameraAccess: the guide states both v74 and v81.
