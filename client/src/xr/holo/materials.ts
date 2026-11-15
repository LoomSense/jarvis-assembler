import * as THREE from 'three';
import { LineMaterial } from 'three/addons/lines/LineMaterial.js';
import { LineSegments2 } from 'three/addons/lines/LineSegments2.js';
import { LineSegmentsGeometry } from 'three/addons/lines/LineSegmentsGeometry.js';
import { reducedMotion } from '../palette';

// The holographic look: fresnel rim glow, a slow scan band, glowing world-width edges and
// a fading grid. No post-processing (PLAN §6.0: Quest budget), so every glow is geometry
// or a cheap fragment shader. Motion stays at or under 1 Hz (BRAND.md §5).

/** Shared time uniform; frozen under reduced motion. */
export const holoTime = { value: 0 };

export function tickHolo(dt: number): void {
  if (!reducedMotion()) holoTime.value += dt;
}

const lineMaterials = new Set<LineMaterial>();

/** LineMaterial needs the drawing-buffer size; call on resize. */
export function setLineResolution(w: number, h: number): void {
  for (const m of lineMaterials) m.resolution.set(w, h);
}

export interface EdgeOptions {
  color: THREE.ColorRepresentation;
  /** World width in metres. */
  width?: number;
  opacity?: number;
  dashed?: boolean;
  additive?: boolean;
}

export function makeLineMaterial(o: EdgeOptions): LineMaterial {
  const m = new LineMaterial({
    color: new THREE.Color(o.color).getHex(),
    linewidth: o.width ?? 0.0012,
    worldUnits: true,
    transparent: true,
    opacity: o.opacity ?? 0.9,
    depthWrite: false,
    dashed: o.dashed ?? false,
    dashSize: 0.012,
    gapSize: 0.008,
  });
  if (o.additive ?? true) m.blending = THREE.AdditiveBlending;
  m.resolution.set(window.innerWidth, window.innerHeight);
  lineMaterials.add(m);
  return m;
}

/** Thick glowing edges of a mesh geometry. */
export function makeEdges(geo: THREE.BufferGeometry, mat: LineMaterial, thresholdDeg = 25): LineSegments2 {
  const lg = new LineSegmentsGeometry().fromEdgesGeometry(new THREE.EdgesGeometry(geo, thresholdDeg));
  const line = new LineSegments2(lg, mat);
  line.computeLineDistances();
  line.renderOrder = 6;
  return line;
}

/** Thick lines from a flat list of segment endpoints [x0,y0,z0, x1,y1,z1, ...]. */
export function makeSegments(points: number[], mat: LineMaterial): LineSegments2 {
  const g = new LineSegmentsGeometry().setPositions(points);
  const line = new LineSegments2(g, mat);
  line.computeLineDistances();
  line.renderOrder = 6;
  return line;
}

// ---------------------------------------------------------------- hologram fill

const HOLO_VERT = /* glsl */ `
  uniform float uY0;
  uniform float uY1;
  varying vec3 vN;
  varying vec3 vV;
  varying float vY;
  void main() {
    vec4 wp = modelMatrix * vec4(position, 1.0);
    vec4 mv = viewMatrix * wp;
    vN = normalize(normalMatrix * normal);
    vV = normalize(-mv.xyz);
    vY = (wp.y - uY0) / max(uY1 - uY0, 1e-4);
    gl_Position = projectionMatrix * mv;
  }
`;

const HOLO_FRAG = /* glsl */ `
  uniform vec3 uColor;
  uniform vec3 uFillColor;
  uniform float uOpacity;
  uniform float uTime;
  uniform float uScan;
  uniform float uScanHz;
  uniform float uFill;
  varying vec3 vN;
  varying vec3 vV;
  varying float vY;
  void main() {
    float facing = abs(dot(normalize(vN), normalize(vV)));
    float rim = pow(1.0 - facing, 2.0);
    // Fine horizontal hologram lines (about 4 mm apart) and one slow scan band.
    float fine = 0.88 + 0.12 * step(0.5, fract(vY * 60.0));
    float band = uScan * smoothstep(0.07, 0.0, abs(vY - fract(uTime * uScanHz) * 1.2 + 0.1));
    vec3 col = uColor * (0.55 + 1.4 * rim) * fine + uColor * band * 0.9;
    float a = uOpacity * (0.4 + 1.1 * rim) * fine + band * 0.35 * uOpacity;

    // Solidify sweep (step complete): fill rises from the bottom with a bright edge.
    float filled = step(vY, uFill);
    float edge = smoothstep(0.035, 0.0, abs(vY - uFill)) * step(0.001, uFill) * step(uFill, 0.999);
    col = mix(col, uFillColor * (0.75 + 0.6 * rim), filled * 0.9) + uFillColor * edge * 1.6;
    a = mix(a, 0.8, filled) + edge * 0.8;

    gl_FragColor = vec4(col, clamp(a, 0.0, 1.0));
    #include <colorspace_fragment>
  }
`;

export type HoloMaterial = THREE.ShaderMaterial & {
  uniforms: {
    uColor: { value: THREE.Color };
    uFillColor: { value: THREE.Color };
    uOpacity: { value: number };
    uTime: typeof holoTime;
    uScan: { value: number };
    uScanHz: { value: number };
    uFill: { value: number };
    uY0: { value: number };
    uY1: { value: number };
  };
};

export function makeHoloMaterial(color: THREE.ColorRepresentation, opacity = 0.35, scan = 1): HoloMaterial {
  return new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color(color) },
      uFillColor: { value: new THREE.Color('#6fdc9b') },
      uOpacity: { value: opacity },
      uTime: holoTime,
      uScan: { value: scan },
      uScanHz: { value: 0.35 },
      uFill: { value: 0 },
      uY0: { value: 0 },
      uY1: { value: 0.3 },
    },
    vertexShader: HOLO_VERT,
    fragmentShader: HOLO_FRAG,
    transparent: true,
    depthWrite: false,
  }) as HoloMaterial;
}

// ---------------------------------------------------------------- plate grid

const GRID_VERT = /* glsl */ `
  varying vec2 vP;
  void main() {
    vP = position.xz;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const GRID_FRAG = /* glsl */ `
  uniform vec3 uColor;
  uniform vec2 uHalf;
  uniform float uReveal;
  uniform float uTime;
  uniform float uOpacity;
  varying vec2 vP;
  float gridLine(vec2 p, float s, float w) {
    vec2 q = p / s;
    vec2 g = abs(fract(q - 0.5) - 0.5) / fwidth(q);
    return 1.0 - min(min(g.x, g.y) / w, 1.0);
  }
  void main() {
    float minor = gridLine(vP, 0.02, 1.0) * 0.22;
    float major = gridLine(vP, 0.10, 1.4) * 0.55;
    vec2 d = uHalf - abs(vP);
    float fade = smoothstep(0.0, 0.07, min(d.x, d.y));
    float r = length(vP / uHalf) / 1.4142;
    float shown = step(r, uReveal);
    float ring = smoothstep(0.035, 0.0, abs(r - uReveal)) * (1.0 - step(1.05, uReveal));
    // A soft band sweeps across the plate every 6 s.
    float sweep = smoothstep(0.05, 0.0, abs(vP.x / uHalf.x - (fract(uTime / 6.0) * 2.6 - 1.3)));
    float a = (max(minor, major) + 0.035 + sweep * 0.18) * fade * shown * uOpacity + ring * 0.9;
    gl_FragColor = vec4(uColor * (1.0 + ring * 0.8), a);
    #include <colorspace_fragment>
  }
`;

export type GridMaterial = THREE.ShaderMaterial & {
  uniforms: { uReveal: { value: number }; uOpacity: { value: number } };
};

export function makeGridMaterial(color: THREE.ColorRepresentation, half: THREE.Vector2): GridMaterial {
  return new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color(color) },
      uHalf: { value: half },
      uReveal: { value: 0 },
      uTime: holoTime,
      uOpacity: { value: 1 },
    },
    vertexShader: GRID_VERT,
    fragmentShader: GRID_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
  }) as GridMaterial;
}

// ---------------------------------------------------------------- projector beam

const BEAM_VERT = /* glsl */ `
  uniform float uHeight;
  varying float vH;
  varying vec3 vN;
  varying vec3 vV;
  void main() {
    vH = position.y / uHeight + 0.5;
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    vN = normalize(normalMatrix * normal);
    vV = normalize(-mv.xyz);
    gl_Position = projectionMatrix * mv;
  }
`;

const BEAM_FRAG = /* glsl */ `
  uniform vec3 uColor;
  uniform float uOpacity;
  varying float vH;
  varying vec3 vN;
  varying vec3 vV;
  void main() {
    float side = pow(1.0 - abs(dot(normalize(vN), normalize(vV))), 1.5);
    float a = pow(1.0 - clamp(vH, 0.0, 1.0), 1.6) * (0.25 + 0.75 * side) * uOpacity;
    gl_FragColor = vec4(uColor, a);
    #include <colorspace_fragment>
  }
`;

export function makeBeamMaterial(color: THREE.ColorRepresentation, height: number): THREE.ShaderMaterial {
  return new THREE.ShaderMaterial({
    uniforms: { uColor: { value: new THREE.Color(color) }, uOpacity: { value: 0.35 }, uHeight: { value: height } },
    vertexShader: BEAM_VERT,
    fragmentShader: BEAM_FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    blending: THREE.AdditiveBlending,
  });
}

/** Turn an object to face the camera (full billboard), whatever its parent's rotation. */
export function billboard(obj: THREE.Object3D, camera: THREE.Camera): void {
  const parentQ = new THREE.Quaternion();
  obj.parent?.getWorldQuaternion(parentQ);
  const camQ = camera.getWorldQuaternion(new THREE.Quaternion());
  obj.quaternion.copy(parentQ.invert().multiply(camQ));
}
