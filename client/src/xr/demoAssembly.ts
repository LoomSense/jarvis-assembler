// Stand-in assembly until the phase-1 GLB + manifest exist (CAD-06 / CAD-08).
// IKEA KNAGGLIG box (golden guide), 460 x 250 x 310 mm overall. Steps, hardware codes
// and counts follow the real guide (pages 3–8); part sizes are ESTIMATED.
// The guide builds the box on its side and on its end; here it stays upright on the plate.
//
// Coordinates are in the build plate's frame, in metres: origin at the centre of the
// box footprint on the table surface, +y up, +z towards the user.

import type * as THREE from 'three';

export type Vec3 = [number, number, number];

export interface DemoPart {
  id: string;
  name: string;
  kind: 'board' | 'screw';
  /** Board: [x, y, z] extent. Screw: [diameter, length, 0] along `axis`. */
  size: Vec3;
  axis?: 'x' | 'y' | 'z';
  /** Final position of the part's centre. */
  pos: Vec3;
  /** Where the insertion animation starts, relative to `pos`. */
  insertFrom: Vec3;
  /** Already in place when the step starts (e.g. the LACK top lying face down): drawn as
   *  placed, with no ghost, arrow or insertion loop. */
  base?: boolean;
  /** How the user moves it in: slide/press (`push`, default for boards) or turn (`twist`, default for screws). */
  motion?: 'push' | 'twist';
  /** Screws only: `double` = threaded both ends with a collar (LACK 115980), else a wood screw with a head. */
  screwType?: 'double' | 'wood';
  /** From a pipeline GLB (jobAssembly.ts): the real shape, centred on `pos`, and its half size. */
  geometry?: THREE.BufferGeometry;
  half?: Vec3;
  /** The action's own instruction from the manifest, e.g. "Take a long screw (108363)…". */
  text?: { en: string; el: string };
  /** Hardware colour from the manifest, so each screw code looks different. */
  color?: string;
}

export interface DemoStep {
  title: string;
  instruction: string;
  page: number;
  crop: string;
  /** Greek text for `?lang=el`. */
  el?: { title: string; instruction: string };
  /** Orientation of the whole assembly in this step, Blender XYZ degrees (manifest `assemblyRotationDeg`). */
  pose?: Vec3;
  /** The step starts by turning the assembly; this is what to say. */
  turn?: { en: string; el: string };
  hardware?: { code: string; qty: number };
  parts: DemoPart[];
}

export const FOOTPRINT = { x: 0.46, z: 0.31 };

const W = 0.46;
const H = 0.25;
const D = 0.31;
const FRAME_T = 0.02; // side frame thickness
const BOARD_T = 0.012; // end board thickness
const BOARD_H = 0.078;
const ROWS = [0.042, 0.125, 0.208];
const FRAME_L = W - 2 * BOARD_T;
const FRAME_Z = D / 2 - FRAME_T / 2;
const END_X = W / 2 - BOARD_T / 2;

const crop = (n: number) => `/demo-job/crops/step-0${n}.png`;

const frame = (id: string, name: string, z: number, from: Vec3): DemoPart => ({
  id, name, kind: 'board', size: [FRAME_L, H, FRAME_T], pos: [0, H / 2, z], insertFrom: from,
});

const longScrews = (side: -1 | 1): DemoPart[] =>
  [-0.15, 0.15].map((x, i) => ({
    id: `screw_108363_${side < 0 ? 'a' : 'b'}${i + 1}`,
    name: '108363',
    kind: 'screw' as const,
    size: [0.005, 0.055, 0] as Vec3,
    axis: 'z' as const,
    pos: [x, 0.012, side * (D / 2 - 0.0275)] as Vec3,
    insertFrom: [0, 0, side * 0.09] as Vec3,
  }));

const endBoard = (side: -1 | 1, row: number, handle = false): DemoPart => ({
  id: `${handle ? 'handle' : 'end'}_board_${side < 0 ? 'l' : 'r'}${row + 1}`,
  name: handle ? 'Handle board' : 'End board',
  kind: 'board',
  size: [BOARD_T, BOARD_H, D],
  pos: [side * END_X, ROWS[row], 0],
  insertFrom: [side * 0.12, 0, 0],
});

export const DEMO_STEPS: DemoStep[] = [
  {
    title: 'Join the bottom to the first side',
    el: { title: 'Ένωσε τον πάτο με το πρώτο πλαϊνό', instruction: 'Σπρώξε τον πάτο στο πλαϊνό, σανίδες προς τα μέσα.' },
    instruction: 'Slide the bottom into the side frame, slats facing in.',
    page: 3,
    crop: crop(1),
    parts: [
      frame('side_frame_a', 'Side frame', -FRAME_Z, [0, 0.12, 0]),
      {
        id: 'bottom', name: 'Bottom', kind: 'board',
        size: [FRAME_L, 0.015, D - 2 * FRAME_T], pos: [0, 0.0115, 0], insertFrom: [0, 0, 0.14],
      },
    ],
  },
  {
    title: 'Fix the bottom with 2 long screws',
    el: { title: 'Στερέωσε τον πάτο με 2 μακριές βίδες', instruction: 'Δύο μακριές βίδες από το πλαϊνό μέσα στον πάτο.' },
    instruction: 'Drive both long screws through the side into the bottom.',
    page: 3,
    crop: crop(2),
    hardware: { code: '108363', qty: 2 },
    parts: longScrews(-1),
  },
  {
    title: 'Add the second side frame',
    el: { title: 'Βάλε το δεύτερο πλαϊνό', instruction: 'Σπρώξε το δεύτερο πλαϊνό στην ελεύθερη άκρη του πάτου.' },
    instruction: 'Push the second side onto the free edge of the bottom.',
    page: 4,
    crop: crop(3),
    parts: [frame('side_frame_b', 'Side frame', FRAME_Z, [0, 0, 0.14])],
  },
  {
    title: 'Fix it with 2 long screws',
    el: { title: 'Στερέωσέ το με 2 μακριές βίδες', instruction: 'Όπως πριν: δύο μακριές βίδες μέσα στον πάτο.' },
    instruction: 'Same as before: two long screws into the bottom.',
    page: 4,
    crop: crop(4),
    hardware: { code: '108363', qty: 2 },
    parts: longScrews(1),
  },
  {
    title: 'Close one end with two boards',
    el: { title: 'Κλείσε τη μία άκρη με δύο σανίδες', instruction: 'Βίδωσε τις δύο απλές σανίδες στην αριστερή άκρη.' },
    instruction: 'Screw the two plain boards across the left end.',
    page: 5,
    crop: crop(5),
    hardware: { code: '108560', qty: 8 },
    parts: [endBoard(-1, 0), endBoard(-1, 1)],
  },
  {
    title: 'Add the handle board',
    el: { title: 'Βάλε τη σανίδα με τη λαβή', instruction: 'Η σανίδα με το άνοιγμα μπαίνει πάνω.' },
    instruction: 'The board with the cut-out goes on top, cut-out up.',
    page: 6,
    crop: crop(6),
    hardware: { code: '108560', qty: 4 },
    parts: [endBoard(-1, 2, true)],
  },
  {
    title: 'Close the other end',
    el: { title: 'Κλείσε την άλλη άκρη', instruction: 'Δύο απλές σανίδες στη δεξιά άκρη.' },
    instruction: 'Two plain boards across the right end.',
    page: 7,
    crop: crop(7),
    hardware: { code: '108560', qty: 8 },
    parts: [endBoard(1, 0), endBoard(1, 1)],
  },
  {
    title: 'Last handle board',
    el: { title: 'Τελευταία σανίδα λαβής', instruction: 'Άνοιγμα πάνω, 4 κοντές βίδες. Το κουτί είναι έτοιμο.' },
    instruction: 'Cut-out up, four short screws. The box is done.',
    page: 8,
    crop: crop(8),
    hardware: { code: '108560', qty: 4 },
    parts: [endBoard(1, 2, true)],
  },
];

// ---------------------------------------------------------------------------------------
// IKEA LACK side table (eval/golden/lack-side-table/expected_plan.json): 2 steps on page 1.
// The guide prints no sizes: 550 x 550 x 450 mm is the product's published size, and the
// top thickness, leg section and screw length are ESTIMATED. Built as the guide draws it:
// top face down on a soft surface, double-ended screws into the corner holes, legs twisted on.
// The final turn upright is not drawn in the guide, so it is not a step here.

export const LACK_FOOTPRINT = { x: 0.55, z: 0.55 };

const LACK_TOP_T = 0.05;
const LACK_LEG = 0.05;
const LACK_LEG_H = 0.4;
const LACK_CORNER = 0.275 - LACK_LEG / 2;
const lackCrop = (n: number) => `/demo-job/lack/step-0${n}.png`;
const CORNERS: [number, number][] = [
  [-1, 1],
  [1, 1],
  [1, -1],
  [-1, -1],
];

export const LACK_STEPS: DemoStep[] = [
  {
    title: 'Screw in the 4 double screws',
    el: { title: 'Βίδωσε τις 4 διπλές βίδες', instruction: 'Καπάκι ανάποδα. Βίδωσε με το χέρι μία βίδα σε κάθε γωνία.' },
    instruction: 'Top face down. Twist one screw by hand into each corner hole.',
    page: 1,
    crop: lackCrop(1),
    hardware: { code: '115980', qty: 4 },
    parts: [
      {
        id: 'table_top', name: 'Table top', kind: 'board',
        size: [0.55, LACK_TOP_T, 0.55], pos: [0, LACK_TOP_T / 2, 0], insertFrom: [0, 0, 0], base: true,
      },
      ...CORNERS.map(([sx, sz], i): DemoPart => ({
        id: `screw_115980_${i + 1}`, name: '115980', kind: 'screw', screwType: 'double',
        size: [0.008, 0.07, 0], axis: 'y',
        pos: [sx * LACK_CORNER, LACK_TOP_T, sz * LACK_CORNER], insertFrom: [0, 0.09, 0],
      })),
    ],
  },
  {
    title: 'Twist on the 4 legs',
    el: { title: 'Βίδωσε τα 4 πόδια', instruction: 'Βίδωσε το πόδι δεξιόστροφα μέχρι να σφίξει.' },
    instruction: 'Turn each leg clockwise onto its screw until it sits tight.',
    page: 1,
    crop: lackCrop(2),
    parts: CORNERS.map(([sx, sz], i): DemoPart => ({
      id: `leg_${i + 1}`, name: 'Leg', kind: 'board', motion: 'twist',
      size: [LACK_LEG, LACK_LEG_H, LACK_LEG],
      pos: [sx * LACK_CORNER, LACK_TOP_T + LACK_LEG_H / 2, sz * LACK_CORNER], insertFrom: [0, 0.12, 0],
    })),
  },
];

export interface DemoProduct {
  id: string;
  name: string;
  nameEl: string;
  steps: DemoStep[];
  footprint: { x: number; z: number };
  /** Colour of placed parts in simulation mode. */
  finish: string;
  /** Warnings the guide draws before the steps, shown as "Before you start" screens. */
  tips?: DemoTip[];
}

export interface DemoTip {
  page: number;
  crop: string;
  en: { title: string; body: string };
  el: { title: string; body: string };
}

export const PRODUCTS: Record<'knagglig' | 'lack', DemoProduct> = {
  knagglig: { id: 'knagglig', name: 'KNAGGLIG box', nameEl: 'κουτί KNAGGLIG', steps: DEMO_STEPS, footprint: FOOTPRINT, finish: '#d8b88a' },
  lack: {
    id: 'lack', name: 'LACK side table', nameEl: 'τραπεζάκι LACK', steps: LACK_STEPS, footprint: LACK_FOOTPRINT, finish: '#f1efea',
    // Page 1, boxed drawing: assembling on a rug is fine; on a hard floor the top gets damaged.
    tips: [{
      page: 1,
      crop: '/demo-job/lack/tip-01.png',
      en: { title: 'Work on a soft surface', body: 'Lay the top on a rug or blanket, not a hard floor.' },
      el: { title: 'Δούλεψε σε μαλακή επιφάνεια', body: 'Βάλε το καπάκι πάνω σε χαλί ή κουβέρτα.' },
    }],
  },
};
