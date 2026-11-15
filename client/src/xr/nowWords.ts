// The panel's one line of what to do now, in the guide page's words (src/guide/words.ts, src/i18n.ts):
// a screw by its code and which one of how many in this step (`108560 3/8`), a board by its short name,
// a turn of the whole assembly in two or three words.
import { displayName as pageName } from '../i18n';
import { turnWords } from '../guide/words';
import type { DemoPart, DemoStep } from './demoAssembly';
import { displayName } from './stepGuides';
import type { Lang } from './i18n';

export function nowWords(step: DemoStep, part: DemoPart | undefined, lang: Lang): string {
  if (!part) return turnWords(lang, step.poseName, step.turn);
  if (part.kind === 'screw') {
    const same = step.parts.filter((p) => !p.base && p.kind === 'screw' && p.name === part.name);
    return same.length > 1 ? `${part.name} ${same.indexOf(part) + 1}/${same.length}` : part.name;
  }
  return part.item ? pageName(lang, part.item) : displayName(part, lang);
}
