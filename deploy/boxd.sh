#!/bin/sh
# Deploy the committed code to the boxd VM (PLAN §8.3, INF-05).  `make deploy-box`
#
# Before the first run, on this laptop:
#   curl -fsSL https://boxd.sh/downloads/install.sh | sh      # installs ~/.local/bin/boxd
#   boxd auth login                                           # browser sign-in; add a payment method
#   make env                                                  # .env with DEMO_KEY and GEMINI_API_KEY
#
# Run for real on Sun 15 Nov (boxd CLI v0.3.5, x86_64 VM): smoke PASS through the public URL.
# It deploys `git HEAD`, so commit first. Re-running it updates the code and restarts the service.
# Build the client first (`make client`): client/dist is uploaded only if it exists.
# Use an existing machine with VM=<name> deploy/boxd.sh. 2 vCPU / 8 GB is enough to pass the smoke test;
# for the demo: boxd machine resize <name> --vcpu 4 --memory 16G -y   (reboots; the service comes back by itself).
set -eu
VM=${VM:-agj}
REGION=${REGION:-fra}
VCPU=${VCPU:-4}
APP=/srv/agj
REPO=$(cd "$(dirname "$0")/.." && pwd)
PATH="$HOME/.local/bin:$PATH"

command -v boxd >/dev/null || { echo "boxd CLI not found. Install: curl -fsSL https://boxd.sh/downloads/install.sh | sh"; exit 1; }
[ -f "$REPO/.env" ] || { echo "no .env: run 'make env' and add GEMINI_API_KEY"; exit 1; }
grep -q '^DEMO_KEY=.\+' "$REPO/.env" || { echo "DEMO_KEY is empty in .env: the URL is public, so it is required"; exit 1; }

if ! boxd machine exec "$VM" -- true >/dev/null 2>&1; then
  echo "== creating machine $VM ($VCPU vCPU, region $REGION)"
  boxd machine new "$VM" --vcpu "$VCPU" --region "$REGION"
fi
# A build is CPU work with little network traffic; without this the VM may hibernate mid-job.
boxd machine config set "$VM" auto-hibernate.timeout 0 || echo "warning: could not turn off auto-hibernate"
boxd machine config set "$VM" auto-suspend.timeout 0 || echo "warning: could not turn off auto-suspend"
# The browser check answers API calls, the event feed and the camera socket with a challenge page. DEMO_KEY guards the routes.
boxd machine config set "$VM" bot-protection off || echo "warning: could not turn off bot protection"

ARCH=$(boxd machine exec "$VM" -- uname -m | tr -d '\r\n')
echo "== architecture: $ARCH   (record this in docs/PROGRESS.md, SET-03)"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
git -C "$REPO" archive --format=tar.gz -o "$TMP/src.tgz" HEAD
echo "== uploading code ($(git -C "$REPO" rev-parse --short HEAD))"
boxd machine cp "$TMP/src.tgz" "$VM:/tmp/agj-src.tgz"
boxd machine cp "$REPO/.env" "$VM:/tmp/agj.env"
if [ -f "$REPO/client/dist/index.html" ]; then
  tar -czf "$TMP/client.tgz" -C "$REPO" client/dist
  boxd machine cp "$TMP/client.tgz" "$VM:/tmp/agj-client.tgz"
fi

boxd machine exec "$VM" -- bash -lc "set -e
  sudo mkdir -p $APP && sudo chown \$(id -un):\$(id -gn) $APP
  tar -xzf /tmp/agj-src.tgz -C $APP
  [ -f /tmp/agj-client.tgz ] && tar -xzf /tmp/agj-client.tgz -C $APP || true
  mv /tmp/agj.env $APP/.env && chmod 600 $APP/.env
  rm -f /tmp/agj-src.tgz /tmp/agj-client.tgz
  cd $APP && bash deploy/remote_setup.sh"

boxd machine proxy set-port --vm "$VM" --port 8000 || echo "warning: could not pin the proxy to port 8000"
case "$REGION" in fra) URL="https://$VM.boxd.sh" ;; *) URL="https://$VM.$REGION.boxd.sh" ;; esac
echo "== deployed: $URL"
echo "   next: eval/smoke.sh $URL        # gate G1: the pipeline, with headless Blender, on this host"
