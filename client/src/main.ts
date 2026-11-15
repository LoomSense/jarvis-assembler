import './styles/tokens.css'

// One page, several experiences. `mode` in the address picks one, and only that one is loaded: its code, its
// styles and its markup. So a phone never downloads the headset experience, and neither can show
// through or get in the way of another.
//
//   (no mode)        the guide page for phone and laptop    src/guide/page.ts     with src/styles/page.css
//   ?mode=xr         the Quest experience of src/xr         src/xr/xrMain.ts      with src/styles/xr.css
//   ?mode=build      upload a guide, watch the agent build  src/build/build.ts    with src/styles/build.css
//   the workbench and the build screen   the app's navigation: my guides, new guide   src/nav.ts   with src/styles/nav.css
//   (the steps have one way out, their back button, and the other ways to build beside the language: src/guide/ar.ts)
//   (no mode, no job)   the landing page: upload a guide, my guides   src/landing/landing.ts   with src/styles/landing.css
//   ?mode=ready&job= / ?mode=shelf   the workbench: one guide ready, or the shelf   src/home/home.ts   with src/styles/home.css
//
// index.html is the document `?mode=xr` was written for: an empty #app. For the guide page, the small script in
// its <head> has already made the document dark and Greek-first.

const app = document.getElementById('app')!
const mode = new URLSearchParams(location.search).get('mode')

if (mode === 'xr') {
  // Quest 3 phase-2 experience (PLAN §6.0). Loaded lazily so the phone bundle stays small.
  import('./xr/xrMain').then((m) => m.startXR(app))
} else if (!mode && !new URLSearchParams(location.search).get('job')) {
  // The landing page: what the app does, "upload a guide", and the guides that exist. It has its own top bar.
  void import('./landing/landing').then((m) => m.startLanding(app))
} else if (mode === 'ready' || mode === 'shelf') {
  // The workbench: one guide ready to open (`?mode=ready&job=`), or its shelf of boxes (`?mode=shelf`).
  void import('./home/home').then((m) => m.startHome(app))
  void import('./nav').then((m) => m.mountNav('ready'))
} else if (mode === 'live') {
  // Build with the camera: the phone watches the real parts, says what it sees and what to do next, as it happens.
  void import('./live/live').then((m) => m.startLive(app))
} else if (mode === 'build') {
  // Drop a flat-pack guide (PDF), watch the agent build the 3D guide, approve its plan, open the result.
  void import('./build/overview').then((m) => {
    m.startOverview() // listening before the feed's first event
    return import('./build/build').then((b) => b.startBuild(app))
  })
  void import('./nav').then((m) => m.mountNav('build'))
} else {
  // The guide's markup goes into the document now, still hidden, so that it is there when `load` fires.
  // Its styles arrive with its code, and src/guide/page.ts shows it then.
  app.append(document.querySelector<HTMLTemplateElement>('#guide-page')!.content)
  void import('./guide/page').then((m) => m.startGuidePage())
  void import('./guide/ar').then((m) => m.startTableAR())
}
