# Front panel on the table without a marker: what to port today

Reference: `server/perception/panel.py`, function `detect_front_panel(img, K, T_world_cam, T_world_table, prev_yaw=None)` (the only copy). `tools/markerless/panel_pose.py` evaluates it on a dataset.

**Status, plainly.** It is measured on synthetic renders only. There are no real
Quest camera frames with a pose in the repo, so nothing here is proven on the
headset. On the renders it is accurate enough to put the ghost on the panel and
it never trusted a grossly wrong pose. Ship the fallback (ghost placed on the
table, user puts the part inside the outline) as the default, and let this
detector snap the ghost onto the real panel only when it reports `trusted`.

**What it solves.** The plane-constrained case only: x, y and yaw of the
380 × 130 × 16 mm panel lying flat on the known table plane, from one frame.
Not general 6-DoF. Yaw is known modulo 180° (a rectangle looks the same turned
round).

## Method (warp, threshold, contours, minAreaRect; no solver)

1. Rectify the camera image to a top-down view of the plane 16 mm above the
   table (the panel's top face), 2.5 mm per pixel, 1.4 × 1.4 m centred where
   the optical axis meets the table. In that view the top face is a true-scale
   152 × 52 px rectangle from any camera angle.
2. Whiteness = `L − 1.5 × chroma` in Lab. Sweep 8 thresholds (percentiles 50 to
   97 of the visible table), take external contours with 0.6–2.2× the panel
   area, fit `minAreaRect`.
3. The white blob is the top face plus the side faces that face the camera.
   Those smear towards the camera by `16 mm × horizontal distance / camera
   height`. Check the rectangle against 380 × 130 mm plus that predicted smear,
   then place an exact 380 × 130 rectangle at the corner **farthest from the
   camera**, which is never smeared.
4. Return `x, y, yaw`, `T_world_obj` (origin = panel centre on the table plane,
   x along the 380 mm side), `corners_px` (the 4 top-face corners in the input
   image) and `trusted` with a `reason`.

Homography used for the warp, with the camera pose in the table frame
(`T_ct = inv(T_table_cam)`, rotation columns `r1 r2 r3`, translation `t`),
window origin `(x0, y0)` and `s` = metres per pixel:

```
H = K · [ s·r1 | s·r2 | x0·r1 + y0·r2 + 0.016·r3 + t ]      (top-down pixel → image pixel)
bird = warpPerspective(img, H, WARP_INVERSE_MAP)
```

Conventions in the reference: camera axes are OpenCV (x right, y down, z
forward); the table frame has z along the table normal (up) and its origin on
the table surface. The image must be undistorted and its pose must be the pose
at the exposure time.

## Measured (renders, 1280 × 960, degraded images, noisy table plane and camera pose)

90 frames of the panel alone (3 lightings), plus 270 frames of later assembly
states as negatives. Table plane noise σ 3 mm / 0.4°, camera pose noise
σ 1.5 mm / 0.15°, blur, sensor noise and JPEG as in `REPORT.md` §1.

| | frames | trusted | position error p50 / p90 / max (mm) | yaw error p50 / p90 (°, mod 180) |
|---|---|---|---|---|
| panel alone, all | 90 | 71 (79%) | 4.6 / 10.8 / 17.0 | 0.21 / 0.43 |
| office light | 30 | 20 | 4.5 / 16.1 / 17.0 | 0.15 / 0.36 |
| low sun, hard shadows | 30 | 28 | 4.3 / 7.9 / 14.5 | 0.11 / 0.38 |
| dim lamp | 30 | 23 | 5.0 / 9.4 / 12.6 | 0.32 / 0.66 |
| hand over the panel | 12 | 0 | – | – |
| later assembly states (negatives) | 270 | 0 | – | – |

- Of the 71 trusted poses, 61 are within 10 mm and 5°; the other 10 are
  between 10 and 17 mm off. None is grossly wrong.
- Untrusted reasons on the 19 other frames: size mismatch 10, not a clean
  rectangle 7 (includes all hand frames), partly out of view 2.
- Speed: 12 to 20 ms p50 and 25 to 34 ms p90 per frame over two runs in Python on an M4 (most of it is the
  warp and the Lab conversion of a 560 × 560 image).

Reproduce: `python panel_pose.py data/synth out/panel_pose.json` (dataset from
`gen_dataset.py`, see `REPORT.md` §8).

## Porting

**Kotlin (OpenCV is in the app).** A direct port, about 120 lines. Every call
exists in the Java bindings: `Imgproc.warpPerspective`, `cvtColor(…BGR2Lab)`,
`GaussianBlur`, `morphologyEx`, `findContours`, `contourArea`, `minAreaRect`,
`boxPoints`, `drawContours`, `dilate`. Build `H` as above from the passthrough
camera pose and the MRUK table anchor. Run it on a background thread at 2–5 Hz;
the panel does not move, so head tracking carries the ghost between results.

**Unity C# (no OpenCV).** Two options:

- Server (recommended for today, serves both apps): `POST /api/perception/{job}/panel`
  takes the JPEG, `fx fy cx cy` and the 4 × 4 camera pose in the table frame,
  and returns `{trusted, reason, x, y, yaw, corners_px, ms}`. Route:
  `server/routes/panel.py`; request, reply and frame conventions:
  `server/perception/README.md`; tests: `tests/test_panel.py`.
- Native: the warp as a shader or a CPU loop, then threshold, connected
  components, convex hull and a rotating-calipers minimum-area rectangle.
  About 250 lines of C#; not realistic before the freeze.

**Use `corners_px` in the app, whichever side computes it.** Cast a ray through
each of the 4 image corners with the app's own camera pose, intersect the plane
16 mm above the app's own table, and take the centre and long axis of the 4
hits. That avoids every axis and handedness conversion between Python, Android
and Unity, and it uses the pose of the frame that was actually sent. The server
still needs the camera-in-table pose to rectify, but an axis mistake there
shows up as `trusted: false`, not as a wrong ghost.

## When not to trust the pose

Keep the last good pose unless `trusted` is true. The gates:

| gate | threshold | catches |
|---|---|---|
| rectangle size, after removing the predicted smear | within 14 mm of 380 and 12 mm of 130 | other white objects (A4 leaflet, later assembly states), wrong table height, merged blobs |
| contour area / rectangle area | ≥ 0.86 | a hand or tool over a corner or edge |
| ring round the blob inside the camera image | ≥ 97% | panel partly out of view |
| whiteness inside minus ring outside | ≥ 18 of 255 | pale table, washed-out exposure |
| camera looks down at the table | optical axis at least about 9° below horizontal | grazing views |

Recommended in the app, not measured here: move the ghost only after 3
trusted results in a row that agree within 10 mm and 3°, and use their median.

Known failure cases:

- **White panel on a pale or white table.** Expect "no candidate" or "low
  contrast", so the pose is never trusted and the app stays on the fallback. Not
  tested: every render has a wood table.
- **Hands over corners.** The pose is rejected (0 of 12 hand frames trusted). It
  comes back when the hand leaves.
- **180° ambiguity.** The method cannot tell the two orientations apart. Pass
  `prev_yaw` (the ghost's current yaw) so it returns the nearer one, and give
  the user a "flip" action. The cam-bolt holes and the groove would break the
  tie; that is not implemented.
- **Wrong table height from MRUK.** The position error is about
  `height error × horizontal distance / camera height`: 5 mm of height error
  gives 5–8 mm at a normal viewing angle. A large error fails the size gate.
- **Not tested at all:** real passthrough exposure and highlights on the
  laminate, lens distortion, a shadow edge crossing the panel, pose and image
  timestamps that do not match, a patterned tablecloth with white areas.
