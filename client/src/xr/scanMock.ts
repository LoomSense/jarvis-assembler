// Development only (imported behind `import.meta.env.DEV`, so never in a production build): scripted answers for
// the scan flow, to try it without the server's /api/identify and /api/perception/{job}/locate.
//
//   ?mock=scan         identify finds a ready guide: `mockjob` (default knagglig)
//   ?mock=scan-build   identify reads a product name; catalog/open starts a build that is done after 4 polls
//   ?mock=scan-none    identify sees nothing it knows: "show the label"
//   &mockstep=<n>      locate answers step n (default 2)
//
// Job files still come from /files/<mockjob>/ (the dev server's mirror; JOBS_DIR=<repo>/jobs in a worktree).

export function installScanMock(kind: string, params: URLSearchParams): void {
  const job = params.get('mockjob') || 'knagglig';
  const step = Number(params.get('mockstep') || 2);
  const el = params.get('lang') === 'el';
  let polls = 0;
  const real = window.fetch.bind(window);
  const json = (body: unknown, ms = 700) =>
    new Promise<Response>((r) => setTimeout(() => r(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })), ms));

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href);
    const path = url.pathname;
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : {};
    if (path === '/api/identify') {
      console.info('[mock] identify, image', String(body.image ?? '').length, 'base64 chars');
      const seen = { kind: 'box', name: job.toUpperCase(), type: 'box', article: '', text: '' };
      if (kind === 'scan-none')
        return json({ seen: null, match: null, query: null, confidence: 0.2, message: el ? 'Δεν το αναγνωρίζω.' : 'I do not recognise it.', simulated: true });
      if (kind === 'scan-build')
        return json({ seen, match: null, query: job, confidence: 0.8, message: el ? `Είναι ${seen.name}. Ψάχνω τον οδηγό.` : `That is ${seen.name}. Looking for its guide.`, simulated: true });
      return json({ seen, match: { job_id: job, product: seen.name, ready: true }, query: null, confidence: 0.9, message: el ? `Είναι ${seen.name}.` : `That is ${seen.name}.`, simulated: true });
    }
    if (path === '/api/catalog/open') {
      polls = 0;
      return json({ job_id: job, ready: false, product: String(body.q ?? job).toUpperCase() });
    }
    if (path === `/api/jobs/${job}` && kind === 'scan-build') {
      polls++;
      const stages = ['ingest', 'extract_plan', 'parts', 'steps', 'done'];
      const stage = stages[Math.min(polls - 1, stages.length - 1)];
      return json({ job_id: job, stage, status: stage === 'done' ? 'done' : 'running', progress: Math.min(1, polls / 5) }, 150);
    }
    const m = /^\/api\/perception\/([^/]+)\/locate$/.exec(path);
    if (m) {
      console.info('[mock] locate', m[1], 'image', String(body.image ?? '').length, 'base64 chars');
      return json({
        next_step: step,
        action: 0,
        done_through: step - 1,
        confidence: 0.7,
        steps: [],
        message: el ? `Τα ${step - 1} πρώτα βήματα είναι έτοιμα. Συνεχίζουμε στο βήμα ${step}.` : `Steps 1 to ${step - 1} look done. Carry on with step ${step}.`,
        simulated: true,
      });
    }
    return real(input, init);
  };
  // A machine without a camera (a test browser) gets a drawn one, so the picture and the JPEG path still run.
  const md = navigator.mediaDevices;
  if (md?.getUserMedia) {
    const gum = md.getUserMedia.bind(md);
    md.getUserMedia = async (c?: MediaStreamConstraints) => {
      try {
        return await gum(c);
      } catch (err) {
        if (!c?.video) throw err;
        console.info('[mock] no camera here: a drawn one instead');
        return drawnCamera();
      }
    };
  }
  console.info('[mock] scan answers are scripted:', kind, 'job', job);
}

function drawnCamera(): MediaStream {
  const cv = document.createElement('canvas');
  cv.width = 640;
  cv.height = 480;
  const ctx = cv.getContext('2d')!;
  const t0 = performance.now();
  const draw = () => {
    const t = (performance.now() - t0) / 1000;
    ctx.fillStyle = '#3a3329';
    ctx.fillRect(0, 0, 640, 480);
    ctx.fillStyle = '#d8b88a';
    ctx.fillRect(180 + Math.sin(t) * 20, 140, 280, 200);
    ctx.fillStyle = '#fff';
    ctx.font = '28px sans-serif';
    ctx.fillText('mock camera', 230 + Math.sin(t) * 20, 250);
  };
  draw();
  window.setInterval(draw, 100);
  return cv.captureStream(10);
}
