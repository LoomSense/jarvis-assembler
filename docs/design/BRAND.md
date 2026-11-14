# Assembly Guide: brand and UX guidelines

Status: hackathon v1, 14 Nov 2026. Token source: `docs/design/tokens.css` (prefix `ag`).
Derived from the brand reference design system. Lines marked **chosen** are not in the reference: we picked them.

## 1. Principles

1. **The guide is the authority.** Every step, part and correction cites its guide page. The UI shows the guide crop next to our 3D, never instead of it. Estimated values are always labelled `estimated`.
2. **Quiet control room.** Near-black surfaces, one azure accent used as light, colour reserved for status. The 3D model and the real table are the hero; chrome stays out of the way.
3. **Show the agent's work, honestly.** The build feed shows what the agent thought, called and found, including its mistakes and fixes. No fake progress, no invented confidence.
4. **Calm hands-on guidance.** One instruction at a time, short, cited, never scolding. Warn before a mistake, confirm after a success, ask when unsure.
5. **Status is never colour alone.** Every status has a colour, an icon shape and a word, on phone and in XR.

## 2. Tokens

### Colour roles

| Token | Dark (default) | Light | Role |
|---|---|---|---|
| `--ag-bg` | `#000000` | `#ffffff` | Page |
| `--ag-surface` | `#0c0c0c` | `#ffffff` | Cards, sheets (always with a border) |
| `--ag-surface-2` | `#161618` **chosen** | `#f4f4f5` **chosen** | Chips, nested rows |
| `--ag-text-strong` | `#ffffff` | `#000000` | Titles |
| `--ag-text` | white 82% | black 85% | Body |
| `--ag-text-meta` | white 66% | black 66% | Timestamps, page refs |
| `--ag-border` | white 9% | ink 14% | Card hairline |
| `--ag-accent` | `#9ecfff` | `#2a6db5` **chosen** | Live state, info, links, focus of attention |
| `--ag-accent-deep` | `#274a6b` | `#3d86d6` | Accent fills (selected step, XR buttons) |
| `--ag-primary` | `#ffffff` | `#000000` | Primary button fill |
| `--ag-info` | `#9ecfff` | `#2a6db5` | Info |
| `--ag-success` | `#6fdc9b` **chosen** | `#1e7a47` | Correct, done |
| `--ag-warn` | `#ffa95c` **chosen** | `#a14d00` | Warning, flag needing review |
| `--ag-error` | `#ff8a7a` **chosen** | `#b42318` | Wrong, failed |
| `--ag-unsure` | `#e8c14b` | `#8a6500` | Unsure, paused, awaiting (reference amber) |

The reference is strict monochrome with one accent and amber for paused states only; it has no success, warning or error colours. We added three, all at least 7:1 on `#0c0c0c` and at least 5.3:1 on white. The reference's light accent `#3d86d6` is 3.8:1 on white, so it is kept for graphics only and text uses `#2a6db5` (5.3:1).

### Type

One family, Google Sans (variable, real Greek), Arial fallback. 500 for headings, 400 for copy, 600 only for uppercase labels. JetBrains Mono only for `tool_call` and `tool_result` payloads in the feed (the reference's "terminal" layer).

| Token | Size / line height | Tracking | Use |
|---|---|---|---|
| `display` | 31 / 1.06 | -0.035em | Screen title (one per screen) |
| `h3` | 21 / 1.25 | -0.01em | Step title, card title |
| `lead` | 20 / 1.55 | 0 | Instruction text under a step title |
| `body` | 17 / 1.7 | 0 | Running copy |
| `small` | 15 / 1.6 | 0 | Meta, captions, chip text |
| `label` | 12.5 / 1.45 | 0.08em, uppercase, 600 | Section labels, badges. Never above 0.1em |

The reference forbids a sixth size: keep it. The marketing hero size (up to 86px) is not used on phone.

### Spacing, radii, elevation, motion

- **Spacing:** 4px base: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. Phone gutter 16px (**chosen**; reference web gutter is 24). Touch targets at least 44px (**chosen**).
- **Radii:** 8 small, 12 inputs and thumbnails, 16 cards and the canvas frame, 18 bottom sheets, pill for buttons, chips and badges.
- **Elevation:** card = gradient fill white 5.5% to 1.6%, hairline border, inset top highlight, shadow `0 24px 60px -28px rgba(0,0,0,.65)`. Glass (only for bars floating over the 3D canvas) = ink 88 to 80% with `blur(16px)`. No hard drop shadows.
- **Motion:** easing `cubic-bezier(.16,1,.3,1)`. Durations 200 (micro), 250 (base), 300 (slow), 850 (screen and feed-item rise). Buttons lift -2px on hover, press to `scale(.98)`. Status-dot breathe 2.4s; live badge 1.8s. Bounce, elastic and spin are banned. Every loop lives in a class so `prefers-reduced-motion` turns it off.
- **Icons:** inline SVG, one stroke family, stroke 1.8 to 2, round caps, `currentColor`. No icon font, no emoji. Text glyphs allowed: `→ ✓ ◆ ›`.

## 3. Status semantics (all surfaces)

| Status | Colour token | Shape (phone and XR) | Word (EN / EL) |
|---|---|---|---|
| info | `--ag-info` | filled circle | Info / Πληροφορία |
| success | `--ag-success` | circle with check `✓` | Done / Έγινε |
| warn | `--ag-warn` | triangle with `!` | Check / Έλεγξε |
| error | `--ag-error` | octagon with `✕` | Wrong / Λάθος |
| unsure | `--ag-unsure` | diamond with `?` | Not sure / Δεν είμαι σίγουρος |

Agent event types map to a status dot and a row style in the build feed:

| Event | Dot | Row |
|---|---|---|
| `thought` | accent, hollow | Body text, meta colour, max 3 lines then "more" |
| `tool_call` | accent, filled, pulsing while open | Tool name in label style, args in mono, collapsed |
| `tool_result` | success or error | Mono summary, thumbnail if an image |
| `image` | none | 64px thumbnail, radius 12, tap to enlarge |
| `part_status` | per status | Part label chip + status word + guide page |
| `step_ready` | success | Card: "Step 4 ready · p. 6", tap opens walkthrough at that step |
| `approval_required` | warn | Sticky card with Approve button |
| `progress` | none | Updates the top progress bar only, no row |
| `error` | error | Card with message and Retry |
| `done` | success | Card with "Open walkthrough" primary button |

## 4. Phone screens (portrait first, 360 to 430px wide)

Global: black page, 16px gutter, one primary (white pill) per screen. Top bar 56px: back chevron, screen title (h3), live badge when a job is running. A 2px progress bar (accent, linear fill, reference "tick" pattern) sits under the top bar during jobs.

**Upload.** Title "Upload a guide". A dashed-border drop card (radius 16, 200px tall) with a document icon, "Choose a PDF" primary button and "Max 40 pages" meta line. States: *idle*; *dragging* (border turns accent); *uploading* (filename, progress bar, Cancel); *error* (error card: "That file is not a PDF." plus Choose again). Empty state is the idle state. Include a "Try a sample guide" glass button.

**Plan review.** List of cards, one per part then one per step, in guide order. Each card: guide crop left (96px square, radius 12, tap to zoom), right column with label chip (`A`, `B`), name, count ("×2"), page ref ("p. 3"), and flags. Flags render as status chips (`warn` triangle + "Unclear drawing"). Estimated values carry an `estimated` meta tag. A summary strip at the top: "12 parts · 9 steps · 2 flags". Sticky bottom bar (glass): Approve (primary, disabled with reason while count mismatches exist) and "Edit" (glass). Loading: skeleton cards (surface-2 blocks, no shimmer under reduced motion). Error: card with message and Retry.

**Build feed.** Chat-like stream, newest at the bottom, auto-scrolls unless the user has scrolled up (then show a "New events ↓" pill). Rows per the event table above; each row: 8px status dot, 24px left rail line connecting dots, timestamp in meta. Rows rise in (850ms, expo). Empty: "Waiting for the agent to start" with a pulsing dot. Disconnect: unsure banner "Reconnecting…" (SSE replays from last event).

**Walkthrough.** Vertical split: 3D canvas about 58% (radius 16 frame, step counter overlay top-left "4 / 9"), guide crop plus hardware chips about 30%, controls bar the rest. Hardware chips: pill, surface-2, icon + "Cam lock ×4". Controls bar (glass, 64px): Back, Play/Repeat, Next (primary), step scrubber dots. Loading: canvas shows spinner-free text "Loading model" with progress; error: "Model failed to load" + Retry; guide crop missing: meta "No drawing for this step".

**Start assembling.** Card choosing the role: "Wear the headset" (primary) or "Use this phone as the camera" (glass). Camera role screen: full-bleed camera, glass top bar with "Camera on" live badge and Pause, step chip bottom. A visible "vision paused" state uses the unsure colour and diamond.

**Toasts** (**chosen**, not in the reference): glass pill, bottom, above the controls bar, icon + one sentence, 4s, swipe to dismiss, `role="status"`; errors use `role="alert"` and stay until dismissed.

## 5. Quest 3 in-world UI (three.js canvas-texture panels over passthrough)

None of this is in the reference; all values are **chosen** from common XR practice, keeping the reference's look (ink panel, hairline border, azure accent).

**Placement and comfort**
- No head-locked UI. Every panel is world-anchored to the table (anchor or hit-test pose), placed beside the work area, 0.6 to 0.9 m from the user, 10 to 15° below eye level, turned to face the user once on placement. Users can grab-move it.
- Step panel: **0.40 × 0.28 m at 0.8 m** (about 28° × 20° of view). Canvas texture 1024 × 717 px, so 1 px ≈ 0.39 mm.
- Nothing flashes faster than 2 Hz. Ghost pulses stay at or below 1 Hz. No full-field colour changes.

**Text size** (angular height of capital letters, h = 2·d·tan(θ/2))

| Role | Angle | At 0.5 m | At 1 m | Canvas px on 0.40 m panel at 0.8 m |
|---|---|---|---|---|
| Minimum (meta) | 0.7° | 6.1 mm | 12.2 mm | 28 px |
| Body / instruction | 1.1° | 9.6 mm | 19.2 mm | 40 px |
| Step title | 1.8° | 15.7 mm | 31.4 mm | 64 px |

Font: Google Sans 500 for titles, 400 for body, loaded before drawing the canvas (`document.fonts.load`). Max 2 lines of instruction per panel.

**Contrast against passthrough**
- Panel background `#0c0c0c` at 82% opacity, radius 32 px on the canvas, 2 px border white 16%. Text white (body 86%). Target at least 7:1 against the panel itself.
- No pure white areas larger than text strokes and icons. Buttons fill with `#274a6b` (accent deep) and white text, not the phone's white primary.
- Status shown as a coloured 6 px left edge on the panel plus the shape icon.

**Ghost part material** (`MeshStandardMaterial`, `transparent: true`, `depthWrite: false`, plus white `EdgesGeometry` outline at 60%)

| State | Colour / emissive | Opacity | Pulse | Extra cue |
|---|---|---|---|---|
| Next (neutral) | `#9ecfff` | 0.40 | 0.42 Hz (2.4 s), opacity ±0.1 | Insertion path animated, 1.5 s loop |
| Correct | `#4ade80` | 0.45 | none, fades out over 600 ms | `✓` circle badge above part |
| Wrong | `#ff6b6b` | 0.50 | 0.83 Hz | `✕` octagon badge, dashed outline |
| Unsure | `#e8c14b` | 0.40 | 0.42 Hz | `?` diamond badge |

Emissive intensity 0.5 to 0.7 (see `tokens.css`). Green and red are never the only cue: badge shape and outline style differ, and the voice says the word.

**Pinch buttons:** Next, Back, Repeat on the step panel's bottom row. Each at least 6 × 4 cm (about 4° at 0.8 m) with 1 cm gaps. Hover (ray or finger near): border to accent; press: scale 0.96 for 120 ms plus a short click sound. Next is the accent-deep fill; Back and Repeat are outline.

**"Vision paused" badge:** pill on the panel top-right, unsure amber, diamond icon, "Vision paused · say resume". Static, no pulse.

**Hardware chips:** row under the instruction, chip `#161618`, icon + "Dowel ×4", 28 px text minimum.

## 6. Voice and copy

From the reference: plain, specific, honest. Sentence case. No exclamation marks, no emoji, no hype words ("seamless", "elevate", "unleash"). **No em dashes in either language**; use a period, comma or colon. Join label parts with a middle dot (`Step 4 · p. 6`). Greek and English are equal and set identically.

**Voice agent persona** (**chosen**):
- One instruction per turn, under about 15 words. Lead with the action.
- Cite step and page when correcting or introducing a step.
- Calm and neutral. Never "you did it wrong", never "oops". Describe the fix, not the mistake.
- Warn before an action, confirm after it. Confirmations are short ("Good, that's in place.").
- When unsure, ask to see, do not guess.
- Greek uses the informal singular (εσύ), as a person beside you would (**chosen**; the reference's site copy uses the formal plural for sales).
- Use the guide's labels (A, B, bag 2) and millimetres.

| Situation | English | Greek |
|---|---|---|
| Wrong part | That's part C, the shelf. Step 4 needs part B, the side panel. Page 6. | Αυτό είναι το κομμάτι C, το ράφι. Το βήμα 4 θέλει το κομμάτι B, το πλαϊνό. Σελίδα 6. |
| Flip needed | Turn panel B over. The holes face up, as on page 6. | Γύρισε το πάνελ B ανάποδα. Οι τρύπες κοιτάνε πάνω, όπως στη σελίδα 6. |
| Wrong hardware | Those are the 30 mm screws. This step uses the 10 mm ones, bag 2. | Αυτές είναι οι βίδες των 30 mm. Εδώ θέλουμε αυτές των 10 mm, σακουλάκι 2. |
| Out of order | Hold on. Panel D goes on after step 6. Page 8 shows it. | Περίμενε. Το πάνελ D μπαίνει μετά το βήμα 6. Το δείχνει η σελίδα 8. |
| Correct | Good, that's in place. | Ωραία, μπήκε σωστά. |
| Step complete | Step 4 done. Next: four cam locks into panel B. | Το βήμα 4 έγινε. Επόμενο: τέσσερα έκκεντρα στο πάνελ B. |
| Unsure | I can't see it clearly. Show me the part. | Δεν το βλέπω καθαρά. Δείξε μου το κομμάτι. |
| Repeat | Step 5, page 7. Push four dowels into the top edge of panel A. | Βήμα 5, σελίδα 7. Βάλε τέσσερις καβίλιες στην πάνω πλευρά του πάνελ A. |
| Vision paused | Camera paused. Say "resume" to continue. | Η κάμερα σταμάτησε. Πες «συνέχεια» για να ξεκινήσω ξανά. |
| Estimated value | The guide doesn't print this size. I estimated it, so check the drawing. | Ο οδηγός δεν γράφει αυτή τη διάσταση. Την υπολόγισα, οπότε έλεγξε το σχέδιο. |

UI copy follows the same rules: buttons are verbs ("Approve plan", "Start assembling", "Retry"); errors say what happened and what to do ("Model failed to load. Retry."); no blame.

## 7. Accessibility

- WCAG AA contrast on every text pair (all tokens above pass on their intended surface).
- Global `:focus-visible` ring: 2px solid, offset 2px, white on dark, black on light. Never transition the outline colour.
- Labels sit above inputs, never placeholder-as-label. Toggle chips carry `aria-pressed`.
- `prefers-reduced-motion` stops every loop (pulses, shimmer, rise-ins); in XR the same setting (or a voice "stop moving") freezes ghost pulses to a steady glow.
- Feed is an `aria-live="polite"` log; errors use `role="alert"`.
- Status always carries icon shape plus word, never colour alone.

## 8. Not in the reference (summary of choices)

Success, warn and error colours; light-mode accent for text; phone gutter and 44px targets; toasts; skeleton states; all XR geometry, text angles, ghost materials and pulse rates; status shapes; voice persona and Greek register. The reference is a dark marketing site with no product UI, so component specs above adapt its button, card, chip, input, badge, status-dot and terminal patterns. The brand's logo, name and slogan are not used.
