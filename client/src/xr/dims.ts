import type { DemoPart } from './demoAssembly';
import { partHalfExtents } from './ghost';

// The size of a part as the wearer would measure it: boards longest side first ("436 × 250 × 20 mm"), screws
// as diameter × length ("⌀5 × 55 mm"). The numbers are the 3D model's, which the guide does not print
// (PLAN §0 rule 3), so they carry "≈" unless the job says the part's dimensions are not estimated.

const mm = (m: number) => String(Math.round(m * 1000));

export function dimsText(part: DemoPart): string {
  const approx = part.dimsEstimated === false ? '' : '≈ ';
  if (part.kind === 'screw') {
    const [d, len] = part.size;
    return `${approx}⌀${mm(d)} × ${mm(len)} mm`;
  }
  const h = partHalfExtents(part);
  const sides = [h.x * 2, h.y * 2, h.z * 2].sort((a, b) => b - a);
  return `${approx}${sides.map(mm).join(' × ')} mm`;
}
