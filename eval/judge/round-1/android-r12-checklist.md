# android · R12 checklist (XR_NATIVE_SPEC §8), round 1, **code level only (E1)**

Commit main `0ef9168`. No capture exists, so every item is `not shown` for scoring (= fail). The column "code" says what the code on main would show if it runs as written. `code ok` means the feature is written and reachable from the main flow. `unreachable` means it is written but nothing on main calls it.

| # | Item | Scored | Code (file) |
|---|---|---|---|
| 1 ★ | Window beside the object, not over it | not shown | code ok: `ui/WindowFollow.kt`, `MainActivity.frameTick` (object top + 0.10 m, 0.22 m aside) |
| 2 ★ | World-locked | not shown | code ok: only the session-start pose, then follow; never parented to the head |
| 3 | Follows a 20 cm slide in about 1 s, no jitter | not shown | code ok: critically damped spring, 3 cm dead band (`WindowFollow`) |
| 4 | Below eye level | not shown | code partial: placed at the object's top + 0.10 m |
| 5 | Rounded corners, lighter rim, vertical tint | not shown | code ok: `Panels.kt` `glassShape`, rim gradient border |
| 6 | Dark glass | not shown | code ok: `AgColors.glassTop/glassBottom` |
| 7 | Toolbar capsule under the window | not shown | code ok: toolbar ornament inside the same panel quad, below the window |
| 8 | ≤ 4 items, one primary | not shown | code ok: Back · Where am I · Repeat · Next (primary) |
| 9 | Hover and press states | not shown | code ok: 1.03 / 0.97 springs, hover fill (`AgButton`) |
| 10 | One window | not shown | code ok (HUD only with `hud=true`) |
| 11 ★ | ≤ 2 instruction lines | not shown | code ok: `maxLines = 2` (truncates rather than rewording) |
| 12 ★ | Body cap height ≥ 1/28 of window height | not shown | borderline: 17.5 sp ≈ 12.7 mm cap (Inter 0.727) against 340/28 = 12.1 mm |
| 13 | Nothing smaller than caption (≥ 1/40) | not shown | borderline: 12 sp ≈ 8.7 mm cap against 8.5 mm |
| 14 | Greek accents, caps without tonos | not shown | code ok: `Strings.upper` |
| 15 | Names, not raw codes | not shown | code ok: `GuideAction.label` from `names.el/en` (hardware row still `2× 118331`, which is meta, fine) |
| 16 | Colour + shape + word | not shown | code ok: octagon / diamond / check in `Panels.kt` status |
| 17 ★ | Ghost of the next part at its final place on the real drawer | not shown | **at risk**: live tracking never yields a world pose on main (no head poses, see round-1.md), so the ghost sits at the fixed table spot; the left-handed SDK frame is not applied to mesh or pose (`toSdk` passes RH values) |
| 18 ★ | One ghost | not shown | code ok: `setStep` shows CURRENT only |
| 19 | Glass ghost | not shown | code ok: fresnel shader `ghost_fresnel.*`, `GhostMaterial` |
| 20 | Insertion path with arrowhead | not shown | code ok: dots + travelling dash + cone |
| 21 | Turning arrow, clockwise for the tool side | not shown | code partial: 290° arc around `from_dir`; handedness of the sweep not checked |
| 22 | Hole ring or ripple | not shown | **missing** (README "Not yet") |
| 23 | Label pill with leader line, name, code, count | not shown | code ok: `LabelPill`, leader cylinder, anchor dot |
| 24 | Label above the part, not over the window | not shown | code ok: 9 cm above the ghost top, billboarded |
| 25 | Correct: check + fade ≤ 1 s | not shown | code ok: 600 ms fade, status check, chime |
| 26 ★ | Wrong: red dashed outline + octagon ✕ + spoken reason | not shown | **unreachable + partial**: `onGuidanceEvent` is never called (no socket on main); the ghost turns red but has no dashed outline or ✕ on the object |
| 27 | Unsure: amber diamond | not shown | unreachable (same reason) |
| 28 | Turn preview | not shown | code ok: whole assembly as one outline turning (`setStep` turning branch) |
| 29 | Calm completion | not shown | code ok: `finishGuide`, COMPLETE cue |
| 30 | Hands occlude ghost | not shown | **missing** (no Depth API) |
| 31 ★ | Nothing faster than 1 Hz | not shown | code ok: breathe 0.42 Hz, arrow 0.8 Hz, wrong pulse 0.83 Hz for 3.6 s, dash 0.45 Hz |
| 32 | No sliding across the work area (fade-relocate) | not shown | **missing**: the window slides with the spring |
| 33 | Button sounds; distinct correct/wrong/done | not shown | code ok: `ui/Sounds.kt` tick / success / warning / complete |
| 34 | Tracking loss shown honestly | not shown | code ok in principle (pill + fade), but the fixed-table fallback suppresses the pill (`tableFallback != null`) |
| 35 | Coach flow one card at a time, §5.4 copy | not shown | code ok: `onStage` cards 1–5 |
| 36 | Reduced motion stops loops | not shown | code ok: `--ez reduced_motion true` |

Code-level count: **26 of 36 written and reachable** (items 22, 26, 27, 30, 32 missing or unreachable; 12, 13, 17, 21, 34 borderline or at risk). Critical set in code: 7 of 8 (26 fails). Scored pass rate: 0 / 36 (no capture).
