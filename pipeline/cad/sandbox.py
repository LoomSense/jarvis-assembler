"""Run one part script that a model wrote, in a child process that can be stopped (CAD-09, PLAN §7.2).

    from pipeline.cad import sandbox
    r = sandbox.run_part(script, "side_panel", out_dir, kind="part", params=None, dims=None)
    r["ok"], r["info"]                       # the same dict harness.build_part returns
    r["error"], r["traceback"], r["killed"]  # what to send back to the model when it failed

    .venv/bin/python -m pipeline.cad.sandbox --selftest

What the child gets:
  time     killed after `wall_s` seconds on the clock, or `cpu_s` seconds of CPU (RLIMIT_CPU)
  memory   killed when its memory passes `mem_mb` (checked five times a second; RLIMIT_AS too where the OS honours it)
  files    no file larger than `file_mb` (RLIMIT_FSIZE), at most 64 open files, no core dump
  secrets  an environment with no API keys: only PATH, PYTHONPATH and a scratch HOME and TMPDIR
  macOS    also run under sandbox-exec: no network, writing only in the out folder and its scratch folder,
           no reading of .env files or of ~/.ssh, ~/.aws, ~/.config, ~/.netrc
On Linux there is no sandbox-exec, so network and file access are NOT restricted there: run the pipeline in
a container or a VM with no secrets on disk. `isolation` in the result says which of the two applied.

Every script pays the 1.4 s CadQuery import. A long-lived worker that forks per script would save it; this
one starts a new process each time because that is simpler to kill cleanly.
"""
from __future__ import annotations

import json
import os
import resource
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
SANDBOX_EXEC = "/usr/bin/sandbox-exec"
LIMITS = {"wall_s": 30, "cpu_s": 30, "mem_mb": 2048, "file_mb": 64}
TAIL = 4000                  # characters of traceback kept for the model


def _profile(out: Path, scratch: Path) -> str:
    home = Path.home()
    deny_read = " ".join(f'(subpath "{home / d}")' for d in (".ssh", ".aws", ".config", ".gnupg")) + f' (literal "{home / ".netrc"}")'
    return f"""(version 1)
(allow default)
(deny network*)
(deny file-write*)
(allow file-write* (subpath "{out.resolve()}") (subpath "{scratch.resolve()}") (literal "/dev/null") (literal "/dev/dtracehelper"))
(deny file-read* {deny_read} (regex #"/\\.env($|\\.)"))
"""


def _limits(cpu_s: int, mem_mb: int, file_mb: int):
    def apply():
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_s, cpu_s + 1))
        resource.setrlimit(resource.RLIMIT_FSIZE, (file_mb << 20, file_mb << 20))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
        try:                                                      # macOS accepts no address-space limit
            resource.setrlimit(resource.RLIMIT_AS, (mem_mb << 20, mem_mb << 20))
        except (ValueError, OSError):
            pass
    return apply


def _rss_mb(pid: int) -> float:
    r = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], capture_output=True, text=True)
    try:
        return int(r.stdout.strip()) / 1024
    except ValueError:
        return 0.0


def run_part(script: Path, part_id: str, out: Path, kind: str = "part", params: dict | None = None,
             dims: dict | None = None, **limits) -> dict:
    lim = dict(LIMITS, **limits)
    out.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix="cadbox-"))
    job = {"script": str(Path(script).resolve()), "part_id": part_id, "out": str(out.resolve()), "kind": kind,
           "params": params, "dims": dims}
    cmd = [sys.executable, "-m", "pipeline.cad.sandbox", "--child", json.dumps(job)]
    isolation = "limits only"
    if sys.platform == "darwin" and os.path.exists(SANDBOX_EXEC):
        cmd = [SANDBOX_EXEC, "-p", _profile(out, scratch), *cmd]
        isolation = "limits + sandbox-exec (no network, writes only in the out folder)"
    env = {"PATH": "/usr/bin:/bin", "PYTHONPATH": str(ROOT), "HOME": str(scratch), "TMPDIR": str(scratch),
           "PYTHONDONTWRITEBYTECODE": "1", "LC_ALL": "C.UTF-8"}
    t0 = time.perf_counter()
    killed = None
    with open(scratch / "stdout", "wb") as so, open(scratch / "stderr", "wb") as se:
        p = subprocess.Popen(cmd, cwd=scratch, env=env, stdin=subprocess.DEVNULL, stdout=so, stderr=se,
                             start_new_session=True, preexec_fn=_limits(lim["cpu_s"], lim["mem_mb"], lim["file_mb"]))
        while p.poll() is None:
            if time.perf_counter() - t0 > lim["wall_s"]:
                killed = f"ran longer than {lim['wall_s']} s"
            elif _rss_mb(p.pid) > lim["mem_mb"]:
                killed = f"used more than {lim['mem_mb']} MB of memory"
            if killed:
                try:
                    os.killpg(p.pid, signal.SIGKILL)              # the whole group, in case the script started children
                except ProcessLookupError:
                    pass
                p.wait()
                break
            time.sleep(0.2)
    wall = round(time.perf_counter() - t0, 2)
    stdout = (scratch / "stdout").read_text(errors="replace")
    stderr = (scratch / "stderr").read_text(errors="replace")
    shutil.rmtree(scratch, ignore_errors=True)
    res = [l for l in stdout.splitlines() if l.startswith("RESULT ")]
    base = {"part_id": part_id, "wall_s": wall, "isolation": isolation, "returncode": p.returncode}
    if not killed and p.returncode == -signal.SIGXCPU:
        killed = f"used more than {lim['cpu_s']} s of CPU"
    if killed:
        return dict(base, ok=False, killed=True, error=f"stopped: the script {killed}", traceback=stderr[-TAIL:])
    if p.returncode == 0 and res:
        return dict(base, ok=True, killed=False, info=json.loads(res[-1][7:]))
    lines = [l for l in stderr.strip().splitlines() if l.strip()]
    return dict(base, ok=False, killed=False, error=lines[-1] if lines else f"exit code {p.returncode}", traceback=stderr[-TAIL:])


def _child(job: dict) -> int:
    from pipeline.cad import harness
    info = harness.build_part(Path(job["script"]), job["part_id"], Path(job["out"]), job["kind"], job["params"], job["dims"])
    print("RESULT " + json.dumps(info))
    return 0


def _selftest() -> int:
    """Good script passes; runaway, memory hog, huge file, network, outside write and secret reads are stopped."""
    work = Path(tempfile.mkdtemp(prefix="cadbox-test-"))
    out = work / "out"
    head = "import cadquery as cq\nPARAMS = {'a': 10}\n"
    ok_tail = "    return cq.Workplane('XY').box(p['a'], 20, 30), {'top': {'origin': [0, 0, 15], 'axis': [0, 0, 1]}}\n"
    outside = work / "outside.txt"
    secret = work / ".env"; secret.write_text("GEMINI_API_KEY=not-a-real-key\n")
    os.environ["CADBOX_FAKE_KEY"] = "not-a-real-key"
    cases = {
        "good": ("def build(p):\n" + ok_tail, True, None),
        "syntax_error": ("def build(p):\n    return (\n", False, False),
        "raises": ("def build(p):\n    raise ValueError('hole is wider than the board')\n", False, False),
        "runaway_cpu": ("def build(p):\n    while True:\n        pass\n", False, True),
        "sleeps_forever": ("import time\ndef build(p):\n    time.sleep(3600)\n", False, True),
        "memory_hog": ("def build(p):\n    x = []\n    while True:\n        x.append(b'\\x01' * (64 << 20))\n", False, True),
        "huge_file": ("def build(p):\n    open('big.bin', 'wb').write(b'0' * (40 << 20))\n" + ok_tail, False, None),
        "reads_env_var": ("import os\ndef build(p):\n    assert 'CADBOX_FAKE_KEY' not in os.environ, 'saw the key'\n" + ok_tail, True, None),
    }
    mac = {
        "network": ("import socket\ndef build(p):\n    socket.create_connection(('1.1.1.1', 53), timeout=3)\n" + ok_tail, False, False),
        "writes_outside": (f"def build(p):\n    open({str(outside)!r}, 'w').write('x')\n" + ok_tail, False, False),
        "reads_dotenv": (f"def build(p):\n    open({str(secret)!r}).read()\n" + ok_tail, False, False),
    }
    if sys.platform == "darwin" and os.path.exists(SANDBOX_EXEC):
        cases.update(mac)
    rows, bad = [], 0
    for name, (body, want_ok, want_killed) in cases.items():
        script = work / f"{name}.py"; script.write_text(head + body)
        r = run_part(script, name, out, wall_s=6, cpu_s=4, mem_mb=1200, file_mb=8)   # importing CadQuery alone takes about 470 MB
        good = r["ok"] == want_ok and (want_killed is None or r["killed"] == want_killed) and r["wall_s"] < 8
        if name == "writes_outside":
            good = good and not outside.exists()
        bad += not good
        rows.append({"case": name, "pass": good, "ok": r["ok"], "killed": r["killed"], "wall_s": r["wall_s"],
                     "error": (r.get("error") or "")[:90]})
        print(f"{'PASS' if good else 'FAIL'}  {name:15s} ok={r['ok']!s:5} killed={r['killed']!s:5} {r['wall_s']:5.2f} s  {(r.get('error') or '')[:90]}")
    isolation = r["isolation"]
    shutil.rmtree(work, ignore_errors=True)
    print("RESULT " + json.dumps({"cases": len(rows), "failed": bad, "isolation": isolation, "rows": rows}))
    return 1 if bad else 0


if __name__ == "__main__":
    if sys.argv[1:2] == ["--child"]:
        sys.exit(_child(json.loads(sys.argv[2])))
    if sys.argv[1:2] == ["--selftest"]:
        sys.exit(_selftest())
    print(__doc__)
    sys.exit(2)
