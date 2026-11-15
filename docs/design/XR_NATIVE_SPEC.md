# Native Quest 3 XR spec: look, guidance on the object, interaction

Status: v1 · Sun 15 Nov 2026 · for `quest-unity/` (Unity 6, Meta XR SDK) and `quest-android/` (Kotlin, Meta Spatial SDK). The WebXR client (`client/src/xr/`) is the reference for behaviour, not for look.
Scored by the judge as **R12** (`eval/judge/RUBRIC-R12.md`) using the checklist in §8.
Mockups of the target look: `docs/design/xr-mockups/` (§9).

This spec extends `docs/design/BRAND.md` (tokens, status semantics, voice) and keeps every token in `docs/design/tokens.css`. Where it changes a BRAND §5 number for the native apps, the line says **changes BRAND §5**. If you change a value here, log it as a decision in `docs/PROGRESS.md` §6.

Target feel: a calm, premium spatial UI in the style of current headset operating systems (glass windows, ornaments, soft light, springy but quiet motion), adapted to Quest 3 passthrough. Guidance is drawn **on the real object**; the window only confirms and cites.

Sources (read 15 Nov 2026; Apple pages via their HIG, Meta via the Horizon design pages):
- [A-win] Apple HIG, Windows: https://developer.apple.com/design/human-interface-guidelines/windows
- [A-orn] Apple HIG, Ornaments: https://developer.apple.com/design/human-interface-guidelines/ornaments · Toolbars: https://developer.apple.com/design/human-interface-guidelines/toolbars
- [A-mat] Apple HIG, Materials: https://developer.apple.com/design/human-interface-guidelines/materials
- [A-type] Apple HIG, Typography: https://developer.apple.com/design/human-interface-guidelines/typography
- [A-eyes] Apple HIG, Eyes: https://developer.apple.com/design/human-interface-guidelines/eyes · Buttons: https://developer.apple.com/design/human-interface-guidelines/buttons
- [A-space] Apple HIG, Spatial layout: https://developer.apple.com/design/human-interface-guidelines/spatial-layout
- [A-motion] Apple HIG, Motion: https://developer.apple.com/design/human-interface-guidelines/motion · Accessibility: https://developer.apple.com/design/human-interface-guidelines/accessibility
- [A-audio] Apple HIG, Playing audio: https://developer.apple.com/design/human-interface-guidelines/playing-audio
- [M-mr] Meta, MR design guidelines: https://developers.meta.com/horizon/design/mr-design-guideline/
- [M-hands] Meta, Hands UI best practices: https://developers.meta.com/horizon/design/hands-ui-best-practices/ · Hit targets: https://developers.meta.com/horizon/design/styles_inputs_hit_targets/
- [M-type] Meta, Typography: https://developers.meta.com/horizon/design/styles_typography/ · Color: https://developers.meta.com/horizon/design/styles_color/ · Layouts: https://developers.meta.com/horizon/design/styles_layouts/
- [M-head] Meta, Head-locked UI: https://developers.meta.com/horizon/design/head/ · Comfort: https://developers.meta.com/horizon/design/comfort/
- [W-7:1] WCAG 2.2 SC 1.4.6: https://www.w3.org/WAI/WCAG22/Understanding/contrast-enhanced.html · [W-flash] SC 2.3.1: https://www.w3.org/WAI/WCAG22/Understanding/three-flashes-or-below-threshold.html

What the sources say that drives this spec (short):
- Windows use a glass background; opaque windows "obscure people's surroundings and can make a window feel constricting and heavy" [A-win]. Glass "limits the range of background color information so a window can continue to provide contrast" and adapts to the luminance behind it [A-mat].
- Ornaments float "in a plane that's parallel to its associated window and slightly in front of it"; toolbars sit "along the bottom edge of a window"; ornaments stay as wide as the window or narrower [A-orn].
- The headset OS uses bolder body and title weights; avoid Light and Thin [A-type]. Meta: Medium and heavier weights are more legible [M-type].
- Interactive targets at least 60 × 60 pt, centres 60 pt apart, rounded shapes; buttons play a sound as feedback [A-eyes]. Meta: hands have no haptics, so every press needs a visual change and a short sound; adjacent targets at least 12 mm apart; colliders about 2.5° to 3° minimum; primary controls 60 × 60 dp [M-hands].
- "Avoid anchoring content to the wearer's head" [A-space]; minimise head-locked content [M-head]. Avoid motion at the edges of the field of view; fade before relocating; make motion optional [A-motion].
- Meta: pure white and pure black do not work well; light backgrounds no brighter than `#DADADA` [M-type color]. Place objects about 1 m away slightly below the line of sight; indirect (ray) interaction is comfortable from about 0.8 m [M-mr, M-hands].
- No more than three flashes in any one second [W-flash]; 7:1 is the enhanced contrast level for body text [W-7:1].

Quest 3 adaptation in one paragraph: passthrough has no system glass blur, the app cannot cheaply sample what is behind a panel, and the display is about 25 pixels per degree at the centre. So the glass is **darker and more opaque** than the reference OS (0.90 in the text zone), the "glass" look comes from a **top sheen, a light rim, a gradient and blue noise**, not from blur, and every size is specified **in degrees** first and in millimetres at a given distance second.

---

## 1. Principles (five)

1. **Content on the object.** The part, the path and the turn are drawn where the hands work. The window beside the object only confirms, counts and cites the guide page. If an instruction can be drawn, it is drawn, not written.
2. **One action at a time.** One ghost, one path or turning arrow, one label, one sentence. Nothing for the next action is visible until this one is done.
3. **Calm.** Dark glass, one azure accent, colour only for status. Motion is slow (pulses at most 1 Hz), springs settle without visible overshoot, sounds are soft. Nothing moves at the edge of view.
4. **Glanceable.** Every state reads in under one second from 0.8 m: big type, at most two lines of instruction, status by colour plus shape plus word.
5. **Honest.** Show what the system knows and how sure it is: tracking state, "estimated", "not sure". Never draw a confident ghost on a lost pose; fade it and say so.

---

## 2. Spatial layout

### 2.1 Units

Angular size θ at distance d: size = 2 · d · tan(θ/2). At the three working distances:

| θ | at 0.6 m | at 0.8 m | at 0.9 m | Quest 3 px (≈ 25 px/°) |
|---|---|---|---|---|
| 0.85° | 8.9 mm | 11.9 mm | 13.4 mm | 21 |
| 1.0° | 10.5 mm | 14.0 mm | 15.7 mm | 25 |
| 1.2° | 12.6 mm | 16.8 mm | 18.8 mm | 30 |
| 1.25° | 13.1 mm | 17.5 mm | 19.6 mm | 31 |
| 2.0° | 20.9 mm | 27.9 mm | 31.4 mm | 50 |
| 3.0° | 31.4 mm | 41.9 mm | 47.2 mm | 75 |
| 4.3° | 45.1 mm | 60.1 mm | 67.6 mm | 108 |

Text sizes below are **font sizes (em)** in degrees, the same convention as BRAND §5 (its 28/40/64 px on the canvas are em sizes). Cap height ≈ 0.72 em for Google Sans and Inter.

### 2.2 Text sizes

| Role | Font size (em) | At 0.8 m | Weight | Tracking | Use |
|---|---|---|---|---|---|
| Caption (minimum) | 0.85° | 11.9 mm | 500 | +0.01 em | Page ref, meta, codes. Nothing smaller anywhere |
| Body | 1.25° | 17.5 mm | 500 | 0 | Instruction lines (max 2, ≤ 30 characters each) |
| Label on object | 1.2° name, 0.85° code | 16.8 / 11.9 mm | 600 name, 500 code | 0 | Part pill |
| Title | 2.0° | 27.9 mm | 600 | −0.01 em | Step title (≤ 20 characters per line, max 2 lines, prefer 1) |
| Display | 3.0° | 41.9 mm | 600 | −0.02 em | Completion only |
| Uppercase label | 0.85° | 11.9 mm | 600 | +0.06 em | "ΒΗΜΑ 3 ΑΠΟ 7 · ΣΕΛΙΔΑ 5". Never above 0.08 em |

**Changes BRAND §5:** sizes up from 0.7° / 1.1° / 1.8° to 0.85° / 1.25° / 2.0° (passthrough lowers contrast; the team already enlarged the WebXR panel by 25 % on 15 Nov for the same reason), body weight 400 → 500 (the reference OS uses bolder body and title styles on glass [A-type]; Meta says Medium and heavier read better [M-type]). Line height 1.2 for titles, 1.36 for body. Design for Greek: it runs about 12 % longer than English.

Labels on the object keep their **angular** size as the user moves: scale = distance / 0.8 m, clamped 0.75× to 1.5× (the reference OS calls this dynamic scale [A-win]). The step window keeps its metric size (it is placed, not followed).

### 2.3 Distances and zones (from the user's eyes)

- **Work zone:** the tracked object, 0.45 to 0.75 m, 25° to 50° below eye level (hands on a table).
- **Window zone:** the step window centre at **0.80 m** (0.75 to 0.95 m), **10° to 20° below eye level**, within ±30° of the user's forward direction. Indirect pinch-ray interaction is comfortable from about 0.8 m [M-hands]; never closer than 0.6 m (vergence strain and accidental pokes).
- **Hand corridor (UI-free):** the volume between the user's shoulders and the object's bounding box, widened by 0.15 m on each side. No window, label, button or ornament may enter it. Labels on the object sit above it.
- **Object margin:** windows and ornaments keep ≥ 0.08 m from the object's bounding box (as seen from the user), so a pinch on a button can never happen inside the work area, and a hand at the object never hovers a button.
- **Peripheral rule:** nothing animates more than 35° from the user's forward direction, except a single static edge chevron pointing to off-screen guidance.

### 2.4 The step window

- Size **0.52 × 0.34 m** (36.0° × 24.0° at 0.8 m). Corner radius **24 mm** (≈ 7 % of the height), continuous corners (superellipse, exponent 5), not circular arcs. Inner padding 22 mm. (**Changes BRAND §5** size 0.40 × 0.28 m; current WebXR is 0.50 × 0.35 m.)
- Coach cards (§5.4) use a **compact** window, 0.36 × 0.20 m, same recipe and placement rules.
- Layout, left column 60 %: status line (uppercase label + page), title, two body lines, hardware chips. Right column 40 %: the guide drawing for the step as **white line art on the glass** (no white block), radius 14 mm, tap to enlarge 2×.
- Top of the window: a 4 px (≈ 0.12°) progress bar of the steps, accent on glass, inset by the corner radius.
- One window only. A second floating card (the parts card) is an **ornament** of this window (left or right edge), never a separate window.

### 2.5 Ornaments

- **Toolbar ornament** under the window: a glass capsule, centred, **as wide as its buttons and never wider than the window** [A-orn], height 56 mm (4.0°), 14 mm in front of the window plane, overlapping the window's bottom edge by 50 % of its height. Contents, left to right: Back, Repeat, microphone state, **Next** (primary). Four items maximum.
- **Parts ornament** on the window's outer edge (the edge away from the object): 0.14 × 0.18 m glass card, 14 mm in front, vertically centred, showing "ΠΑΡΕ" (uppercase label), a turntable 3D thumbnail of the next part (20°/s, stops under reduced motion), its name and code.
- **Status pill** above the window's top edge, centred, only when there is something to say: tracking lost, vision paused, offline. Unsure amber diamond plus word. Static.

### 2.6 Where the window sits relative to the tracked object

1. **Side.** On the side **opposite the parts pile**, so reaching for parts never crosses the window. If the pile is unknown: right of the object for right-handed users, left for left-handed users (§7). The window's inner edge is 0.10 m beyond the object's bounding box edge as seen from the user.
2. **Height.** Window centre at the object's top height + 0.12 m, then clamped so it stays 10° to 20° below eye level.
3. **Facing.** The window turns about the vertical axis only, to face the user's head. It never rolls or pitches with the head; a fixed 8° back tilt (top away) matches a reading posture.
4. **Following the object.** The window is a child of a **follow anchor** attached to the tracked object's pose (position only, no rotation). The anchor moves with a critically damped spring (response 0.6 s, damping ratio 1.0) and a dead band of 3 cm: small tracking jitter never moves the window. **Never head-locked**: if the user turns away the window stays with the object.
5. **When the object turns** (a turn step, or the user rotates the drawer): the window does **not** orbit with the object. It stays on the user's side and re-evaluates the side rule only after the object has been still for 1 s. If the side changes, fade out (150 ms), move, fade in (250 ms) [A-motion: fade before relocating]. Never slide a window across the work zone.
6. **When the object moves more than 0.25 m** (picked up, slid along the table): the window follows with the spring above; if the object leaves the window zone, the window fades and re-places beside the object's new pose.
7. **Tracking lost:** the window stays where it is, the status pill says "Χάθηκε το συρτάρι · δείξ' το ξανά" / "Lost the drawer · show it again", the ghost fades to 30 % and freezes at the last pose (§4.12).
8. **User override:** pinch-and-hold the window's grab bar (a 60 × 6 mm capsule, 10 mm under the toolbar ornament, white 35 %) to move it; once moved by the user, its offset from the object is kept for the session.

---

## 3. Materials

### 3.1 Glass panel recipe (windows, ornaments, pills)

All colours from `tokens.css`. Alpha is straight alpha; blend in linear colour space.

| Layer | Spec |
|---|---|
| Base tint | Vertical gradient `rgba(24, 27, 31, α)` top → `rgba(8, 9, 11, α)` bottom (cool near-black, around `--ag-surface #0c0c0c`). |
| Opacity α | **0.90** in the text zone; the outer 16 mm band of the window ramps to **0.80** at the edge (glass edge looks thinner and lets passthrough through). Adaptive when the passthrough camera is on: measure mean luminance behind the window from the camera frame each second; > 0.6 → α 0.94, < 0.15 → α 0.84. Clamp 0.84 to 0.94. |
| Sheen | Linear gradient at 150°, white 7 % at the top-left corner → 0 at 45 % of the diagonal (same direction as `--ag-glass-fill`). |
| Rim light | 1.5 mm inner stroke (≈ 0.1°): white **24 %** along the top edge, fading to **8 %** at the bottom edge (light from above). Pills: 1.2 mm, white 28 % → 10 %. |
| Inner shadow | 12 mm soft inner shadow along the bottom edge, black 22 %. Gives thickness without a drop shadow. |
| Outer halo | 24 mm Gaussian halo outside the edge, black 14 %. Separates the window from bright walls. No hard drop shadow. |
| Noise | Monochrome blue noise, 256 px tile, ±1.5 % luminance, screen-space, static. Kills 8-bit banding in dark gradients on the Quest display. |
| Thickness | Flat quad (no geometry depth). Ornaments sit 14 mm in front; the parallax between them is the depth cue. |
| Corner radius | Window 24 mm (≈ 7 % of height); ornaments and pills fully rounded (radius = height / 2); chips 50 % of height; image well 14 mm. Rule: inner radius = outer radius − padding. |

**Text colours on glass:** title white 100 %, body white 88 %, caption white 68 %. Contrast check (worst case, passthrough wall `#DADADA` behind the window, α 0.90, linear blend): composite background luminance ≈ 0.075; white title ≈ 8.4:1, body ≈ 7.4:1, caption ≈ 5.0:1 (caption is ≥ 4.5:1 and allowed only for non-essential meta). Body text must stay ≥ 7:1 [W-7:1].

**Unity (URP, Shader Graph or HLSL):**
- Unlit, Transparent, Blend `SrcAlpha OneMinusSrcAlpha`, ZWrite Off, ZTest LEqual, render queue Transparent+10 (above ghosts, below labels). Linear colour space, 4× MSAA.
- Draw the rounded rectangle as an SDF in the fragment shader from UV and panel size in metres (superellipse `pow(|x|^5 + |y|^5, 1/5)`), so the corner stays smooth at any size. Rim = `smoothstep` band on the SDF, multiplied by a vertical gradient. Inner shadow = SDF-based falloff near the bottom edge. Noise = a 256² blue-noise texture sampled in screen space.
- UI on top: world-space Canvas (uGUI) with TextMeshPro, or UI Toolkit world space (Unity 6) if the team already uses it. Quality mode: render the step window into a RenderTexture (2048 × 1340) and show it with `OVROverlay` (quad compositor layer) for the sharpest text; keep the halo and rim in the eye buffer. Choose one and say which in the README.
- Passthrough: `OVRPassthroughLayer` underlay; keep the eye buffer background `(0,0,0,0)`.

**Android (Meta Spatial SDK + Filament):**
- Panels are Compose or View panels. Draw the glass in Compose (`Brush.verticalGradient` base, `drawWithContent` for sheen, rim as a gradient `border`, noise as a tiled bitmap at 3 % alpha) and give the panel a **transparent** background so the alpha survives. Use the SDK's compositor-layer option for panels where available (sharper text) and verify it in the installed SDK version (UNVERIFIED API names; do not guess, read the SDK samples).
- Panel pixel density: at least **40 px per degree** of panel width (0.52 m at 0.8 m = 36° → ≥ 1440 px wide; use 1440 × 940).
- 3D guidance materials in Filament `.mat`: `shadingModel: unlit`, `blending: transparent`, `depthWrite: false`, fresnel from `getWorldNormalVector()` and `getWorldViewVector()`.

### 3.2 Text rendering

- **Font:** Google Sans (BRAND, real Greek). Fallback **Inter** (full Greek, OFL). Never a font without Greek glyphs (no faux Greek via fallback mixing inside a line). Ship the TTF in the app; do not fetch at runtime.
- **Weights:** 600 titles and part names, 500 body and captions, 600 uppercase labels. No 300 or lighter anywhere [A-type, M-type]. Use real weight files, never `Face Dilate` or faux bold.
- **Unity TextMeshPro SDF:** one font asset per weight. Atlas 4096 × 4096, sampling point size 96, padding 10 (≈ 10 %), render mode `SDFAA`, character set: Basic Latin, Latin-1, Greek and Coptic (U+0370–03FF), Greek Extended (U+1F00–1FFF, for accented capitals in uppercase labels), General Punctuation, plus `· → ✓ ✕ « » … ½ ×`. Material: `Sharpness` +0.1, no outline. On-object labels only: `Underlay` black 40 %, offset 0, softness 0.35 (legibility over bright passthrough). Enable "Extra padding".
- **Android:** Compose `Text` with the bundled font family, `fontFeatureSettings = "tnum"` for counts, `lineHeight` as in §2.2. Disable text autosizing.
- **Numbers:** tabular figures in counts and step numbers ("2 από 4"). Units with a thin space: "37 mm".
- **Greek uppercase:** remove tonos in all-caps labels ("ΒΗΜΑ", not "ΒΉΜΑ"); use `toUpperCase(new Locale("el"))` / culture-aware uppercase.

### 3.3 Icon style

- One stroke family, rounded caps and joins, stroke **1.8 mm at 0.8 m** (≈ 0.13°), drawn on a 24-unit grid, `currentColor`. Size 1.4° in buttons, 1.0° inline. Filled only for status shapes (BRAND §3: circle, check-circle, triangle, octagon, diamond).
- Icons: chevron-left (Back), arrow-loop (Repeat), mic / mic-off, check (Next on the last action becomes "Τέλος" with check), hand (grab bar hint), cube-turn (turn the assembly), screw, eye-off (vision paused).
- No emoji, no icon fonts, no glyphs other than `· → ✓ ✕ ›`.

---

## 4. Guidance on the object (the heart)

Everything here is world-locked to the tracked object's pose and sized in the object's metres (true scale) unless stated. Colours: azure `--ag-accent #9ecfff` (next), success `#6fdc9b`, error `#ff8a7a`, unsure `#e8c14b`, warn `#ffa95c`. **Use the token colours for ghosts too** (BRAND §5 ghost table used `#4ade80` and `#ff6b6b`; **changes BRAND §5** to the tokens so the window, the badge and the ghost match).

Drawing order (back to front): ghost fill → ghost rim/edges → path / turning arrow / ripples → leader line → label pill → badges. All guidance is occluded by real hands and real objects using the Meta Depth API (environment depth, soft occlusion, 2 cm feather); without depth, at least hands occlude (hand mesh as depth-only).

### 4.1 Ghost of the next part

- **Geometry:** the part's own mesh at its final pose (end of the `walkthrough` clip), true scale. Never scaled for visibility; small parts get the ripple (4.4) and the label instead.
- **Fill:** azure, alpha **0.14**, unlit, ZWrite off.
- **Fresnel rim:** emissive azure, `rim = pow(1 − saturate(dot(N, V)), 2.5) × 0.65`. Faces seen edge-on glow, faces seen straight on stay clear.
- **Edges:** hard edges (dihedral > 30°) as lines 1.5 mm wide (screen-space quads or a pre-baked bevel ring), azure lightened 8 %, alpha 0.85. Edges are what makes a flat board readable.
- **Breathing:** fill alpha 0.14 ± 0.05, sine, **0.42 Hz** (2.4 s). Rim and edges constant. Reduced motion: no breathing.
- **Appear:** 350 ms, fill and edges from 0, translate 2 cm along `from_dir` to the final pose, ease-out-expo `cubic-bezier(.16,1,.3,1)`.
- **Behind real geometry:** where the ghost is inside the real assembly (a dowel in its hole), draw the hidden part at 35 % of its visible alpha with dashed edges (6 mm dash, 4 mm gap) so the user sees depth without z-fighting.
- **Only one ghost at a time**, plus at most one red outline in the wrong state.

### 4.2 Insertion path

- **When:** every `place` and `press` action with a `from_dir`; `fasten` uses the turning arrow (4.3) plus a short straight path (3 cm).
- **Shape:** dotted arc from the **start** to 2 cm before the ghost surface. Start = the real part's position when the camera has found it in the pile, otherwise 25 cm from the target along `from_dir`. The arc is a cubic Bézier: control 1 = start + 0.30 m up, control 2 = end + 0.18 m along `from_dir` + 0.12 m up. Straight parts of the guide (dowels, pins) use a straight dotted line along `from_dir`, 8 to 12 cm.
- **Dots:** diameter 4 mm, spacing 12 mm, azure, alpha ramps 0.35 at the start → 0.90 at the end. Dots are camera-facing discs, not spheres.
- **Travelling dash:** a bright segment of 4 dots (diameter 5 mm, alpha 1.0, with a 3× soft halo at 16 %) runs start → end in **1.6 s** (ease-in-out cubic), then a 0.6 s pause: cycle 2.2 s (0.45 Hz).
- **Arrowhead:** cone, length 14 mm, base Ø 10 mm, azure, alpha 0.97, at the end of the path, pointing along the path tangent.
- **Hide** the path as soon as the hand holding the part is within 6 cm of the target (it would sit under the hand).

### 4.3 Turning arrow (screws, cam bolts, cam locks)

- **Shape:** a 290° arc around the insertion axis, radius = part radius + 10 mm (min 18 mm, max 40 mm), tube Ø 3.5 mm, azure alpha 0.95, with a soft halo (Ø 11 mm, alpha 0.16). Arrowhead at the **clockwise** end as seen from where the tool comes from (cam locks: from outside the panel). Offset 6 mm in front of the surface along the axis.
- **Motion:** the arc is static; a highlight (a 60° bright sweep, alpha +0.3) runs along it in the turning direction every **1.25 s (0.8 Hz)**. Reduced motion: no sweep.
- **Amount:** a caption chip next to the arrow says how far: "½ στροφή ως να κλειδώσει" / "Half a turn until it locks" (cam locks), "Ως μέσα" / "All the way in" (screws). Cam locks also show their target slot angle: a short azure tick on the lock face pointing at the cam bolt.
- **Tool:** if the guide shows a tool, a 1.0° tool icon in the chip (flat screwdriver, Allen key). No 3D tool model.

### 4.4 Hole highlight (ripple)

- A constant ring on the hole lip: radius = hole radius + 2 mm, stroke 1.2 mm, azure alpha 0.9, 0.5 mm off the surface along its normal.
- Ripples: two rings start at the lip and grow to 2.2× radius while fading 0.6 → 0 in **1.2 s**, a new ripple every **2.0 s (0.5 Hz)**. Ease-out.
- Only the hole(s) of the **current** action. Several identical holes (4 runner screws): the current one ripples, the remaining ones of the same step get a static ring at alpha 0.35, done ones none.

### 4.5 Part label (pill with leader line)

- **Content:** `name · code` then a count chip: "Βίδα έκκεντρου · 118331  [2 από 2]" / "Cam bolt · 118331  [2 of 2]". Boards: name only plus count if more than one of the same ("Πλαϊνό, δεξί"). Names from the manifest `parts[].names` and `hardware[].names` (never a raw code alone as the title).
- **Pill:** glass recipe (3.1), height 2.0° (28 mm at 0.8 m), padding 0.8° horizontal; name 1.2° white 600; code 0.85° white 68 % 500; count chip `--ag-accent-deep #274a6b` fill, white text 0.85°, radius = height / 2.
- **Leader line:** 1.2 mm, white 70 %, from the pill's bottom centre to a 3 mm white dot on the part's anchor (top centre of its ghost). Length 6 to 12 cm; the pill sits **above** the part and **above the line of sight** to it, never between eye and target.
- **Orientation:** billboard about the vertical axis only; dynamic scale (§2.2). Pills never overlap each other or the window; at most **one** label plus one badge visible.
- **Focus:** when the head ray is within 10° of the pill, its rim brightens to 35 % (no motion).

### 4.6 "Pick this part" cue

- **Found in the pile** (camera answer with a position): an azure ellipse on the table under the part (stroke 2 mm, fill 10 %), 2 cm larger than the part's footprint, plus a 1.0° chevron 6 cm above it that bobs ±1 cm at **0.5 Hz**. The label pill (4.5) is anchored to the part in the pile with the prefix "Πάρε" / "Pick up". When the part is lifted, the cue moves to the insertion path (4.2).
- **Not found** (no pile detection): only the parts ornament (2.5) shows the part, and the voice names it once. Never invent a position.

### 4.7 Correct

- Ghost cross-fades to success green in 150 ms, the rim flares to 1.0 and decays to 0 over **600 ms** (ease-out), the fill goes to 0 in the same time, then the ghost is removed (the real part is now there).
- A **check badge** (filled circle with ✓, 1.6° diameter, BRAND §3 shape) appears 4 cm above the part: scale 0.85 → 1.0 with the UI spring (§6.1, no visible overshoot), holds 900 ms, fades 300 ms.
- Sound: **success** (6.2). Voice only at the end of a step ("Το βήμα 3 έγινε. Επόμενο: ..."), not after every action.
- Next action's ghost appears 400 ms after the badge starts (never two ghosts at once).

### 4.8 Wrong

- **Wrong part or wrong place:** the wrong real part gets a **red outline**: dashed edges (8 mm dash, 5 mm gap, 2 mm wide) in `--ag-error #ff8a7a`, fill 6 %, no fresnel glow. An **octagon ✕ badge** (1.6°) above it. The outline's alpha pulses 0.6 ↔ 1.0 at **0.83 Hz for 3 cycles**, then stays steady (never nags).
- The correct target keeps its azure ghost (the one ghost). If the wrong part has a correct place elsewhere, a short azure label on that place says "Ο DR πάει εδώ" / "DR goes here".
- **Spoken reason** within 600 ms, BRAND §6 phrasing: name what it is, what the step needs, the page. The window's status line turns error (octagon + "Λάθος") with the same sentence as a caption.
- Sound: **warning** (6.2), once.
- Never a full-field red tint, never flashing faster than 1 Hz, never "λάθος σου" / "you did it wrong".

### 4.9 Unsure

- Amber diamond **?** badge 1.6° above the uncertain area; the ghost's rim turns amber (`#e8c14b`), fill stays azure. Static.
- Voice asks to see: "Δεν το βλέπω καθαρά. Δείξε μου το κομμάτι." / "I can't see it clearly. Show me the part."
- The window's status line shows the amber diamond and "Δεν είμαι σίγουρος" / "Not sure". The user can always continue with Next.

### 4.10 Turn the assembly

- For `turn` actions and steps whose `assemblyRotationDeg` changes: an **animated rotation preview**: an azure outline of the whole assembly's current silhouette (edges only, 1.5 mm, alpha 0.8, no fill) rotates from the current orientation to the target orientation about the real axis, **1.6 s** ease-in-out, holds 0.8 s at the end, fades and repeats, **at most 3 times**, then stays at the end pose.
- A large curved arrow around the rotation axis (radius 0.6 × the assembly's bounding diagonal, tube 4 mm), arrowhead in the turning direction, static.
- Copy: "Γύρισε το συρτάρι ανάποδα" / "Turn the drawer upside down". After the turn, tracking re-acquires (§2.6 rule 5) before the next ghost appears.
- Reduced motion: show the start outline at 30 % and the end outline at 80 % with the arrow; no animation.

### 4.11 Completion

- The last real part gets the correct sequence (4.7). Then: **three green rings** on the table around the assembly's footprint expand once from 1.0× to 1.4× the footprint radius and fade over **1.2 s** (staggered 150 ms). The whole assembly's rim glows green 0.5 → 0 over 1.2 s. No confetti, no particles, no repeat.
- The window shows the display-size line "Το συρτάρι είναι έτοιμο" / "Your drawer is done", a summary ("7 βήματα · 26 κινήσεις · 18 λεπτά"), and one primary button "Τέλος" / "Finish". Sound: **complete** (6.2).

### 4.12 Tracking states, drawn honestly

| State | Ghost | Window status pill |
|---|---|---|
| Tracked | as above | none |
| Predicted (≤ 0.5 s without a measurement) | unchanged | none |
| Lost (> 0.5 s) | fades to 30 % in 300 ms, freezes, edges dashed | amber diamond "Χάθηκε το συρτάρι · δείξ' το ξανά" |
| Re-acquired | fades back in 250 ms at the new pose (no slide from the old pose) | pill fades out |

### 4.13 Never

- Text walls: more than **2 instruction lines** in the window, more than **one sentence** in a label, paragraphs anywhere.
- Head-locked UI of any kind (including toasts and subtitles). Body-locked "lazy follow" is not used either.
- Flashing: any element changing brightness faster than **1 Hz**, or 3 or more flashes in one second [W-flash]. No full-field colour changes.
- **More than one ghost** at a time; ghosts of future actions; a ghost scaled up "to be visible".
- Arrows or labels that pass through the user's hands or sit between the eyes and the target.
- Status by colour alone (always shape plus word).
- Large pure-white areas (any white fill larger than a button) and pure black opaque panels [M-type color].
- Motion at the edge of view; panels sliding across the work zone; rotating the user's world [A-motion].
- A confident ghost on a lost pose (4.12); invented positions for parts in the pile.
- Raw part codes as the only name ("118331 2/2").

---

## 5. Interaction

### 5.1 Input model

- **Hands first**, controllers also work. Window and ornaments: **indirect** (hand ray + pinch) at 0.8 m. Direct poke is off for the window (it sits beyond comfortable reach) and on for the parts ornament only if it is within 0.5 m.
- Quest 3 has no eye tracking: "gaze" means the **head ray**. Head ray is used only for passive focus (label rim brightening); never to trigger an action.
- Step advance without buttons: perception `step_complete`, or the hand-dwell check (hand still at the target for 1.5 s) confirms; Next is the fallback.

### 5.2 States of every button

| State | Visual | Timing |
|---|---|---|
| Rest | Secondary: glass fill white 10 %, rim 16 %. Primary (Next): `--ag-accent-deep #274a6b` fill, white 600 text | |
| Hover (ray on it) | Scale **1.00 → 1.03**, fill +8 % white, rim 35 %, plus a soft spotlight: radial gradient white 12 %, radius 30 mm, centred on the ray hit point, follows it | spring 0.25 s, ζ 0.9 |
| Pressed (pinch closed) | Scale **0.97**, fill +16 % white (primary: accent `#9ecfff` at 30 % over the deep fill) | 80 ms, then hold |
| Release (activated) | Back to hover scale with the spring; **tick** sound at pinch **down** | 200 ms |
| Disabled | 40 % opacity, no hover; reason as caption under it | |
| Cue (system thinks the step is done) | A second ring 3 mm outside the primary button, azure, static | |

- **Size:** every target at least **60 × 40 mm at 0.8 m** (4.3° × 2.9°); the primary at least 80 × 44 mm. Collider = visual + 4 mm each side. Gap between targets ≥ **12 mm** [M-hands]. Rounded shapes only [A-eyes].
- **Labels:** verbs, 1 to 2 words: "Επόμενο", "Πίσω", "Ξανά", "Τέλος" / "Next", "Back", "Repeat", "Finish".
- **No haptics on hands**; every press gets the visual press state and the tick sound [M-hands]. Controller users also get a 10 ms, 0.2 amplitude haptic.

### 5.3 Voice

Rules (extends BRAND §6):
- One instruction per turn, **≤ 12 words**, verb first. Name + code only when choosing between similar parts.
- Cite the page when starting a step and when correcting.
- Confirm briefly; no praise words; never blame.
- The window caption shows every spoken line (captions on by default).
- Commands, in both languages, no wake word while guidance runs: "επόμενο / next", "πίσω / back", "ξανά / repeat", "πού είμαι / where am I", "σταμάτα / stop", "συνέχεια / resume".

| Moment | Ελληνικά | English |
|---|---|---|
| Step start | Βήμα 3, σελίδα 5. Βάλε την κλειδαριά έκκεντρου στη στρογγυλή τρύπα. | Step 3, page 5. Put the cam lock in the round hole. |
| Turn | Γύρισέ την δεξιόστροφα, μισή στροφή, ως να κλειδώσει. | Turn it clockwise, half a turn, until it locks. |
| Correct (step end) | Το βήμα 3 έγινε. Επόμενο: γύρισε το συρτάρι. | Step 3 done. Next: turn the drawer over. |
| Wrong | Αυτός είναι ο DR. Αριστερά μπαίνει ο DL. Σελίδα 9. | That's DR. The left side takes DL. Page 9. |
| Unsure | Δεν το βλέπω καθαρά. Δείξε μου το κομμάτι. | I can't see it clearly. Show me the part. |
| Lost | Δεν βλέπω το συρτάρι. Κοίτα το ξανά. | I can't see the drawer. Look at it again. |

### 5.4 Coach flow (first run), one card at a time

The coach uses the compact window (§2.4; before an object is tracked it is placed once 0.8 m ahead, 15° down, then world-locked). Each card is said once. Progress label "1 από 5" in the uppercase label style. No Skip on the first card; Skip from card 2.

| # | Trigger | Title EL / EN | Body EL / EN | On the world | Buttons |
|---|---|---|---|---|---|
| 1 Look | App start, camera on | Κοίτα το κουτί ή το έπιπλο / Look at the box or the furniture | Κράτα το περίπου ένα μέτρο μακριά. / Keep it about a metre away. | Four white corner brackets (viewfinder, 1.5 mm, 70 %) lock onto the most box-like region the camera sees | none (automatic) |
| 2 Identifying | Frame sent to `/api/identify` | Αναγνωρίζω… / Identifying… · then: Βρήκα: συρτάρι MALM / Found: MALM drawer | Διαβάζω την ετικέτα. / Reading the label. | Brackets turn azure and a light sweep (0.8 Hz) crosses the box label; when found, brackets snap to the box and a pill shows "Συρτάρι MALM · AA-516949"; continues by itself after 2 s | Άκυρο / Cancel · then: Όχι αυτό / Not this |
| 3 Building (only if the guide is new) | `catalog/open` → job building | Φτιάχνω τα βήματα από τον οδηγό / Making the steps from the guide | Διαβάζω τον οδηγό · 2 από 4 / Reading the guide · 2 of 4 (real stage from `/api/jobs/{id}`, never a fake percentage) | none | Άκυρο / Cancel |
| 4 Locate | Guide ready, frame to `/locate` | Βρίσκω σε ποιο βήμα είσαι… / Finding your step… | then: Είσαι στο βήμα 3 από 7. / You're at step 3 of 7. | Ghost of the already-built parts flashes once at 30 % (one 400 ms fade in/out) on the object | Άλλο βήμα / Other step |
| 5 Lock on | Tracking acquires (or the fallback touch starts) | Μείνε ακίνητος για λίγο / Hold still for a moment · fallback: Άγγιξε την μπροστά αριστερή κάτω γωνία / Touch the front-left bottom corner | Κλείδωσε. / Locked on. | Corner ring under the fingertip fills in 0.8 s (fallback) | Ξανά / Again |
| 6 Guide | Locked | First step window (§2.4) | | First ghost (§4.1) | Toolbar ornament |

Errors on any card: the card's status line turns error with one sentence and one action ("Δεν απαντά ο server. Ξανά." / "No answer from the server. Retry.").

---

## 6. Motion and sound

### 6.1 Curves and durations

| Name | Curve | Duration | Use |
|---|---|---|---|
| UI spring | spring, response **0.35 s**, damping ratio **0.86** (mass 1: stiffness ≈ 322, damping ≈ 31) | settles ≈ 0.45 s | Window appear, badge pop, hover/press |
| Press spring | response 0.25 s, ζ 0.9 | ≈ 0.3 s | Button scale |
| Follow spring | response 0.6 s, ζ 1.0 (critically damped), dead band 3 cm | | Window follows the object |
| Enter | `cubic-bezier(.16,1,.3,1)` (BRAND ease-out-expo) | 300 to 350 ms | Ghost appear, cards rise 8 mm |
| Exit | `cubic-bezier(.7,0,.84,0)` | 200 ms | Fades out |
| Standard | `cubic-bezier(.2,0,0,1)` | 250 ms | Colour and state cross-fades |
| Relocate | fade out 150 ms → move → fade in 250 ms | | Never slide across the view |

Loops and their rates (all ≤ 1 Hz): ghost breathe 0.42 Hz · path dash 0.45 Hz · hole ripple 0.5 Hz · pick chevron 0.5 Hz · turn sweep 0.8 Hz · wrong outline 0.83 Hz × 3 cycles · identify sweep 0.8 Hz. Springs never overshoot more than 2 % (ζ ≥ 0.85). Bounce, elastic and spin are banned (BRAND §2).

### 6.2 Sounds

Short, soft, tonal, a family (same timbre: a soft glass or marimba tone with a fast attack and short decay). Peak −14 dBFS, cues duck under voice by 8 dB. Spatialised at their source (UI sounds at the window, guidance sounds at the part).

| Sound | Character | Length | When |
|---|---|---|---|
| tick | Dry, muted click, 2 to 4 kHz, no tail | 25 ms | Pinch down on any button |
| hover | none (silent) | | Hover is visual only |
| place | Very soft wooden tap, low-mid | 60 ms | Ghost appears for a new action |
| success | Two notes rising a major third (E6 → G♯6), glassy | 350 ms | Action correct |
| step | Three notes rising (C6, E6, G6) | 600 ms | Step complete |
| warning | Two notes falling a semitone, low (A4 → G♯4), muted, not a buzzer | 300 ms | Wrong part or place, once |
| unsure | One soft mid note with a slight upward bend | 250 ms | Unsure |
| complete | Four-note rising arpeggio with a soft shimmer tail | 1.4 s | Assembly complete |
| lost | One soft low note | 200 ms | Tracking lost (once per loss) |

Never communicate only by sound [A-audio]: every sound has a visual twin.

---

## 7. Accessibility and comfort

- **Contrast:** body text ≥ **7:1** against the panel composited over a `#DADADA` passthrough background (3.1); captions and on-object codes ≥ 4.5:1; check in the editor with a white backdrop behind the window.
- **Reduced motion:** a setting (and the voice command "σταμάτα να κινείσαι" / "stop moving") freezes all loops: ghosts steady, paths static with the arrowhead, ripples become one static ring, turning arrows static, turn preview shows start and end outlines. Springs become 150 ms cross-fades.
- **Seated or standing:** the window height rule (10° to 20° below eye level) uses the current head height; it re-evaluates when head height changes by more than 15 cm for more than 2 s (fade-relocate). No UI above eye level.
- **Left-handed:** a setting (first run asks "Με ποιο χέρι δουλεύεις;" / "Which hand do you work with?" only if the app cannot infer it from the first ten pinches). Mirrors: window side, parts ornament side, turning arrow labels stay on the outer side, hand corridor recomputed.
- **Colour vision:** status by shape plus word everywhere (BRAND §3). Wrong is also dashed; correct also has the check.
- **Hearing:** captions for every spoken line in the window; sounds have visual twins.
- **Comfort:** 72 Hz minimum, 90 Hz preferred; no full-field effects; nothing closer than 0.45 m except the user's own hands' feedback; no head-locked content; every motion can be turned off.
- **Language:** EL and EN equal; Greek is the design length. Switch by voice ("Ελληνικά" / "English") or in the toolbar's overflow.

---

## 8. Checklist the judge ticks (observable from a headset capture)

Each item is checked on headset screenshots or a short capture (E4) of the scored build with the real MALM drawer. **Ruler:** the drawer front is 380 mm wide and the step window is 0.52 m wide; estimate angles and sizes by ratio against them at a similar depth. ★ = critical (must pass for R12 ≥ 3). Mark each item pass / fail / not shown (not shown = fail).

**Window and ornaments**
1. ★ The step window is beside the object, not over it, and does not overlap the object or the hands in any frame.
2. ★ The window stays world-locked: turning the head 30° moves the window across the view (not head-locked).
3. The window follows the drawer when it is slid 20 cm (within about 1 s), without jitter while the drawer is still.
4. The window sits below eye level (its centre in the lower half of the view when looking at the object).
5. The window has visibly rounded continuous corners, a lighter top rim and a top-to-bottom tint (not a flat grey rectangle).
6. The window is dark glass: passthrough faintly visible at its edges, never a white or light-grey panel.
7. Buttons are in a separate toolbar capsule under the window (ornament), not inside the text area.
8. The toolbar has at most 4 items and exactly one primary (filled) button.
9. Hover on a button shows a visible change (scale or brighter fill) before the pinch; press shows a smaller scale.
10. Only one window is open (plus its ornaments).

**Type and copy**
11. ★ Instruction text is at most 2 lines; no paragraph anywhere.
12. ★ Body text is readable in the capture: its cap height is ≥ 1/28 of the window's height (body 1.25° em on a 24° window).
13. No text smaller than the caption size (cap height ≥ 1/40 of the window height).
14. Greek renders with correct accents and no missing-glyph boxes; uppercase labels have no tonos.
15. Titles use names, not raw codes ("Βίδα έκκεντρου 2 από 2", not "118331 2/2").
16. Status shows colour **and** shape **and** word (e.g. octagon ✕ + "Λάθος").

**Guidance on the object**
17. ★ A ghost of the next part is drawn at its final place on the real drawer (within about 2 cm by eye against the real holes or edges).
18. ★ Only one ghost is visible at a time.
19. The ghost reads as glass: edges and rim brighter than the face fill; the fill does not hide the real wood behind it.
20. For a `place` action, an insertion path (dotted arc or line with an arrowhead) runs from the part's start to its place.
21. For a screw, cam bolt or cam lock, a turning arrow circles the axis in the clockwise direction for the tool's side.
22. The current hole has a ring or ripple; other holes of the step are dimmer or plain.
23. The part label is a pill with a leader line to the part, showing name, code and count ("2 από 4").
24. The label sits above the part and not between the eye and the target; it does not overlap the window.
25. Correct: a green check badge appears and the ghost fades out within about 1 s.
26. ★ Wrong: the wrong part gets a red (dashed) outline plus an octagon ✕, and a spoken reason is heard in the capture.
27. Unsure: an amber diamond "?" with the word appears (or the voice asks to see the part).
28. Turn step: an animated or two-pose rotation preview with a curved arrow shows how to turn the drawer.
29. Completion: a calm finish (green rings or glow, a "done" line) without confetti or flashing.
30. Real hands occlude the ghost and the arrows (guidance does not draw over fingers).

**Motion, sound, comfort**
31. ★ No element flashes or pulses faster than about once per second; nothing flashes 3 times in a second.
32. No motion at the edge of the view; windows never slide across the work area (fade-relocate instead).
33. Every button press in the capture has a sound; correct, wrong and done have distinct sounds.
34. Tracking loss is shown honestly: the ghost fades or dashes and a status pill says so.
35. The coach flow reaches the first ghost through one card at a time with the §5.4 copy (look → identifying → [building] → locate → lock on → guide).
36. Reduced-motion mode (if toggled in the capture) stops all loops.

Scoring: pass rate = passed / 36. Items 1, 2, 11, 12, 17, 18, 26, 31 are the critical set.

---

## 9. Mockups

`docs/design/xr-mockups/` (1600 × 1000 PNG, made 15 Nov 2026). The "passthrough" is a Blender 5.3 Cycles render of the parts from `jobs/malm-drawer/walkthrough.glb` on a table, with grain and softening added to look like the Quest camera feed. The 3D guidance (ghost, path, turning arrow, ripples, outlines) is rendered in the same scene; the UI is HTML with the tokens, placed at projected anchor points. The guide well shows our own line drawing, not the guide crop (guide pages stay out of git). The type in the mockups uses the §2.2 em sizes scaled to the window's angular width.

| File | Shows |
|---|---|
| `a-step-panel.png` | Step window beside the drawer (opposite the parts pile), toolbar ornament with one primary, grab bar, ghost + path + label on the object. The parts ornament is not shown (it would sit off the right edge) |
| `b-ghost-path-label.png` | Ghost of the right side panel with fresnel rim, dotted insertion arc with travelling dash, label pill with leader line |
| `c-turn-cam-lock.png` | Turning arrow around the cam lock axis, hole ripple, label with "½ στροφή" chip |
| `d-wrong-part.png` | Wrong runner with red dashed outline and ✕ badge, the correct place as the one ghost, window status in error |
| `e-coach-identifying.png` | Coach card 2 "Αναγνωρίζω…", viewfinder brackets on the box, label sweep |
| `f-completion.png` | Finished drawer, green rings, display line and summary |

The mockups show the look and layout; numbers in this spec win where a mockup differs. Sources in `xr-mockups/src/`: `scene.py` (Blender: `Blender -b -P scene.py -- <a…f> bg_<x>.png bg_<x>.json 48`, needs `label.png` from `label.html`), `mockups.html` (`?s=a…f`, needs `bg_<x>.png` and an `anchors.js` built from the JSON files), screenshotted with headless Chrome at 1600 × 1000.
