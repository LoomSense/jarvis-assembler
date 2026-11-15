# unity · R12 checklist (XR_NATIVE_SPEC §8), round 1, **code level only (E1)**

Commit main `0ef9168` (quest-unity unchanged since `e57d543`, phase 1). No capture, so every item is `not shown` (= fail) for scoring. The round-1 guide/UI work is on agent branches and is not scored here.

| # | Item | Scored | Code (file) |
|---|---|---|---|
| 1 ★ | Window beside the object | not shown | code partial: `StepPanelStub.PlaceBeside` beside a fixed 0.3 m box at `AssemblyAnchor` (the anchor never moves: tracker is a stub) |
| 2 ★ | World-locked | not shown | code ok (step panel); the debug HUD is head-locked but only with `hud=true` |
| 3 | Follows a 20 cm slide | not shown | code ok in principle (damped lerp), never exercised (no tracking) |
| 4 | Below eye level | not shown | partial: anchor 0.5 m below the eyes, panel +0.1 m |
| 5 | Rounded corners, rim, tint | not shown | missing: a Quad with `Theme.GlassMaterial` |
| 6 | Dark glass | not shown | unknown (material slot) |
| 7 | Toolbar capsule | not shown | missing: no buttons at all |
| 8 | ≤ 4 items, one primary | not shown | missing |
| 9 | Hover and press | not shown | missing |
| 10 | One window | not shown | code ok |
| 11 ★ | ≤ 2 instruction lines | not shown | not enforced: title has no wrap or line limit |
| 12 ★ | Body cap height ≥ 1/28 | not shown | TMP 0.18 → about 18 mm on a 0.20 m panel: likely passes |
| 13 | Caption ≥ 1/40 | not shown | `size=70%` detail: likely passes |
| 14 | Greek, no tonos in caps | not shown | Inter SDF with Greek baked; no caps rule |
| 15 | Names, not codes | not shown | action text from the manifest; no part label |
| 16 | Colour + shape + word | not shown | missing |
| 17 ★ | Ghost of the next part on the drawer | not shown | missing: 3 cm grey cube at the manifest target (`GhostRendererStub`), no GLB |
| 18 ★ | One ghost | not shown | code ok (one cube) |
| 19 | Glass ghost | not shown | missing |
| 20–30 | Path, turning arrow, ring, label, correct, wrong, unsure, turn, completion, occlusion | not shown | missing |
| 31 ★ | Nothing faster than 1 Hz | not shown | code ok: one 0.28 s pop per message |
| 32 | No sliding across the work area | not shown | missing (lerp follow) |
| 33 | Sounds | not shown | missing |
| 34 | Tracking loss honest | not shown | missing |
| 35 | Coach flow one card at a time | not shown | partial: plain EN/EL lines, not the §5.4 copy |
| 36 | Reduced motion | not shown | missing |

Code-level count: **about 5 of 36** (2, 10, 12, 18, 31; 1, 3, 4, 13, 35 partial). Critical set in code: 4 of 8 (1 partial, 11 and 17 missing, 26 missing).
