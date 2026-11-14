#!/bin/bash
# Runs ON the VM (Ubuntu 24.04), from /srv/agj, called by deploy/boxd.sh. Safe to run again.
# Installs system libraries, uv + the Python 3.12 venv, Blender 5.3, and the systemd service.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
BLENDER_DIR=/opt/blender
BLENDER_URL=${BLENDER_URL:-https://download.blender.org/release/Blender5.3/blender-5.3.0-linux-x64.tar.xz}
ARCH=$(uname -m)
cd "$APP"

echo "== system libraries (headless Blender, OpenCV and the CadQuery viewer wheels need them)"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl ca-certificates xz-utils \
  libgl1 libegl1 libglib2.0-0 libx11-6 libxi6 libxxf86vm1 libxfixes3 libxrender1 libxext6 libsm6 libice6 libxkbcommon0

echo "== Python 3.12 venv"
export PATH="$HOME/.local/bin:$PATH"
command -v uv >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh
[ -x .venv/bin/python ] || uv venv -p 3.12 .venv
uv pip install -q -p .venv/bin/python -r requirements.txt

echo "== Blender"
if [ "$ARCH" != "x86_64" ]; then
  echo "This VM is $ARCH. Blender publishes Linux builds for x86_64 only, so gate G1 FAILS on this host:"
  echo "run the pipeline on the Mac (make serve + make tunnel) and keep this VM for the API only."
elif [ ! -x "$BLENDER_DIR/blender" ]; then
  curl -fL --retry 3 "$BLENDER_URL" -o /tmp/blender.tar.xz
  sudo mkdir -p "$BLENDER_DIR"
  sudo tar -xJf /tmp/blender.tar.xz -C "$BLENDER_DIR" --strip-components=1
  rm -f /tmp/blender.tar.xz
fi
if [ -x "$BLENDER_DIR/blender" ]; then
  "$BLENDER_DIR/blender" -b --version | head -1
  grep -q '^BLENDER_BIN=' .env || echo "BLENDER_BIN=$BLENDER_DIR/blender" >> .env
fi
grep -q '^JOBS_DIR=' .env || echo "JOBS_DIR=$APP/jobs" >> .env

echo "== service"
sudo tee /etc/systemd/system/agj.service >/dev/null <<UNIT
[Unit]
Description=Assembly Guide Agent
After=network-online.target
Wants=network-online.target

[Service]
User=$(id -un)
WorkingDirectory=$APP
ExecStart=$APP/.venv/bin/python -m uvicorn server.app:create_app --factory --host 0.0.0.0 --port 8000 --timeout-graceful-shutdown 5
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable agj >/dev/null 2>&1
sudo systemctl restart agj

for _ in $(seq 1 30); do
  curl -fsS -m 2 http://127.0.0.1:8000/healthz >/dev/null 2>&1 && break
  sleep 1
done
KEY=$(sed -n 's/^DEMO_KEY=//p' .env | head -1)
echo "== healthz"
curl -fsS -m 5 -H "X-Demo-Key: $KEY" http://127.0.0.1:8000/healthz || { sudo journalctl -u agj -n 30 --no-pager; exit 1; }
echo
echo "logs: sudo journalctl -u agj -f"
