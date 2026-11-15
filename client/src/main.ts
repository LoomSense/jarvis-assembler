import './styles/tokens.css';

const app = document.getElementById('app')!;
const params = new URLSearchParams(location.search);

if (params.get('mode') === 'xr') {
  // Quest 3 phase-2 experience (PLAN §6.0). Loaded lazily so the phone bundle stays small.
  import('./xr/xrMain').then((m) => m.startXR(app));
} else {
  // Phone screens (upload, review, feed, walkthrough) arrive with CLI-01..CLI-04.
  app.innerHTML = `
    <main style="padding: var(--ag-gutter); max-width: 480px">
      <h1 style="color: var(--ag-text-strong); font-weight: 500">Assembly Guide</h1>
      <p>The phone screens are not built yet.</p>
      <p><a style="color: var(--ag-accent)" href="?mode=xr">Open the headset experience (?mode=xr)</a></p>
    </main>`;
}
