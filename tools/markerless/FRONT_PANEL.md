# Front panel on the table without a marker: what to port today

Reference: `server/perception/panel.py`, function `detect_front_panel(img, K, T_world_cam, T_world_table, prev_yaw=None)` (the only copy; `detect_footprint` is the same detector for any rectangle, see "All steps"). `tools/markerless/panel_pose.py` evaluates it on a dataset.

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
`gen_dataset.py`, see `REPORT.md` §8). Add `--raw` for the renders without the image degradation.

## All steps (Sun 15 Nov): which steps the tracker is on for

`detect_footprint(img, K, T_world_cam, T_world_table, length, width, height, prev_yaw, fill_hull)` is the same
detector for any rectangle; `detect_front_panel` is it with 380 × 130 × 16 mm. Every gate and constant is unchanged.
The only addition is `fill_hull`: area, rectness and the contrast ring use the convex hull of the blob (the
rectangle itself does not change: the minimum-area rectangle of a blob is that of its hull).

`footprint.py` computes from the guide, per step, the outline of the placed parts in the step's resting pose
(`base` = when the step starts, `done` = when it ends) and the detector's target: the part that lies flat on the
surface, spans the whole outline and is at most 30 mm thick. A step without one has no target and the tracker is
off. Dataset state N is the end of guide step N, so it is also what stands there during step N + 1.

Same 360 renders, same degradation and noise, same success (10 mm and 5°, yaw modulo 180°). "Gross" is a trusted
pose more than 30 mm or 10° off. "Reports" replays the app's rule (3 trusted in a row within 10 mm and 3°) on each
30-frame sequence.

| guide step | what stands there (dataset state) | detector target | trusted | within 10 mm & 5° | position p50 / p90 / max (mm) | gross trusted | reports (max error) | tracker |
|---|---|---|---|---|---|---|---|---|
| 1 | front alone (state 1) | 380 × 130 × 16 | 71 / 90 (79%) | 61 | 4.6 / 10.8 / 17.0 | 0 | 38 (15.6 mm) | on |
| 2, sides not on yet | front alone (state 1) | 380 × 130 × 16, hull | 75 / 90 (83%) | 64 | 4.7 / 11.3 / 17.0 | 0 | 39 (15.6 mm) | on |
| 2 with the sides on, 3 | sides standing on the front (state 2) | 380 × 130 × 16, hull | 30 / 90 (33%) | 30 | 5.7 / 8.7 / 9.5 | 0 | 14 (7.0 mm) | on |
| 4 before the bottom is in | open U, turned over | none | not rendered | – | – | – | – | off |
| 4 with the bottom in, 5 | turned-over box (state 4) | none | – | – | – | – | – | off |
| 5 with the back in, 6, 7 | turned-over box with back (state 5) | none | – | – | – | – | – | off |

Tried on the later states and not shipped (same script, "not shipped" rows):

- **Sides standing, the whole outline 380 × 130 × 418:** 0 of 90 trusted, with or without the hull. The camera can
  be below the top of the sides, and from above the sides are two 16 mm strips. The front panel under them is
  the rectangle that can be seen: without the hull 0 of 90 (the sides hide its ends), with the hull 30 of 90, all
  within 10 mm. By light: office 19 / 30, window 0 / 30 (low sun, hard shadows), dim 11 / 30.
- **Turned-over box, the whole outline 418 × 380 × 130:** 0 of 90 trusted on both states; with the hull 1 of 90 on
  state 4 and that one is gross (31.6 mm, 5.9°). At the side rims (115 mm): 1 of 90, 27 mm off. Why: from above the
  box is not one rectangle at one height (front edge 380 mm wide at 130 mm, sides and bottom 352 mm wide at 103 to
  115 mm), the 115 mm side faces smear by up to about 100 mm (from the geometry, not measured) and are white or grey
  depending on the light, so the predicted smear is wrong one way or the other, and 418 against 380 mm leaves little room before the two sides are swapped.
  A scratch variant without the smear model (not in the repo) trusted 12 to 20 of 90 on state 4 at p50 11 to
  13 mm with 2 to 6 within 10 mm, and on state 5 it trusted 0 to 3 of 90, none within 10 mm and, with the hull, all of them 90° off. So steps 4 to
  7 are off.
- **The front-panel target left on at the turned-over box:** 0 of 180 trusted on degraded images; on the raw
  renders it trusts one frame 248 mm off (`s08_f012`), never three in a row. The tracker is off there anyway.

On the raw renders (`--raw`) the shipped rows read: 72 / 90 (max 16.5 mm), 75 / 90 (max 16.5 mm), 32 / 90 (max
9.7 mm); gross 0 in all three.

Against the edge tracker (`REPORT.md` §4, all frames, no trust flag): step 1 100% within 10 mm and 5°, step 2 83%,
step 4 52% (33% flipped by 180°), step 5 99%. The edge tracker is right more often on the standing sides (83%
against 33% of frames) but shows its wrong poses (slips of 16 to 23 mm) like its right ones; this detector shows
nothing on the frames it does not trust. For the turned-over box the edge tracker is the only one of the two that
works at all (with the slips and 180° flips listed in `REPORT.md` §4).

The hull makes the rectness gate weaker: with it, 3 of the 12 frames with a hand over the panel are trusted (6 to
13 mm off). It is used only in steps where parts stand on the panel.

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
