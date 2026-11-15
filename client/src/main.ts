import './styles/tokens.css'

// One page, several experiences. `mode` in the address picks one, and only that one is loaded: its code, its
// styles and its markup. So a phone never downloads the headset experience, and neither can show
// through or get in the way of another.
//
//   (no mode)        the guide page for phone and laptop    src/guide/page.ts     with src/styles/page.css
//   ?mode=xr         the Quest experience of src/xr         src/xr/xrMain.ts      with src/styles/xr.css
//   ?mode=build      upload a guide, watch the agent build  src/build/build.ts    with src/styles/build.css
//   every screen but the headset's   the app's navigation: my guides, new guide   src/nav.ts   with src/styles/nav.css
//   (no job) / ?mode=ready&job=   the workbench: shelf of guides, or one ready   src/home/home.ts   with src/styles/home.css
//
// index.html is the document `?mode=xr` was written for: an empty #app. For the guide page, the small script in
// its <head> has already made the document dark and Greek-first.

const app = document.getElementById('app')!
const mode = new URLSearchParams(location.search).get('mode')

if (mode === 'xr') {
  // Quest 3 phase-2 experience (PLAN §6.0). Loaded lazily so the phone bundle stays small.
  import('./xr/xrMain').then((m) => m.startXR(app))
} else if (mode === 'ready' || (!mode && !new URLSearchParams(location.search).get('job'))) {
  // The workbench: the shelf of guides (no mode, no job), or one guide ready to open (`?mode=ready&job=`).
  void import('./home/home').then((m) => m.startHome(app))
  void import('./nav').then((m) => m.mountNav(mode === 'ready' ? 'ready' : 'home'))
} else if (mode === 'build') {
  // Drop a flat-pack guide (PDF), watch the agent build the 3D guide, approve its plan, open the result.
  void import('./build/build').then((m) => m.startBuild(app))
  void import('./nav').then((m) => m.mountNav('build'))
} else {
  // The guide's markup goes into the document now, still hidden, so that it is there when `load` fires.
  // Its styles arrive with its code, and src/guide/page.ts shows it then.
  app.append(document.querySelector<HTMLTemplateElement>('#guide-page')!.content)
  void import('./guide/page').then((m) => m.startGuidePage())
  void import('./nav').then((m) => m.mountNav('guide'))
}
