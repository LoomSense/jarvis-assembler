PY   := .venv/bin/python
PORT ?= 8000
BASE ?= http://localhost:$(PORT)

.PHONY: help setup env dev serve tunnel client test smoke models secrets deploy-box

help:            ## list the targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | expand -t 14

setup:           ## Python 3.12 venv with every server and pipeline package
	uv venv -p 3.12 .venv
	uv pip install -p $(PY) -r requirements.txt

env:             ## create .env from .env.example with a fresh DEMO_KEY (never overwrites)
	@test -f .env || { cp .env.example .env && \
	  sed -i.bak "s|^DEMO_KEY=.*|DEMO_KEY=$$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')|" .env && \
	  rm -f .env.bak && chmod 600 .env && echo "created .env with a new DEMO_KEY; add GEMINI_API_KEY to it"; }

dev: env         ## run the server with auto-reload on :8000
	$(PY) -m uvicorn server.app:create_app --factory --reload --reload-dir server --reload-dir pipeline \
	  --host 0.0.0.0 --port $(PORT) --timeout-graceful-shutdown 3

serve: env       ## run the server as on the demo host (no reload)
	$(PY) -m uvicorn server.app:create_app --factory --host 0.0.0.0 --port $(PORT) --timeout-graceful-shutdown 5

tunnel:          ## public HTTPS URL for the local server (phones and the headset need HTTPS)
	cloudflared tunnel --url http://localhost:$(PORT)

client:          ## build the Vite client into client/dist, which the server then serves at /
	cd client && npm ci && npm run build

test:            ## unit tests
	$(PY) -m pytest -q

smoke:           ## end to end on a running server: upload the golden guide, approve, wait for the GLB
	eval/smoke.sh $(BASE)

models:          ## call every Gemini model once (SET-02); needs GEMINI_API_KEY in .env
	$(PY) eval/smoke_models.py

secrets:         ## scan the working tree and the git history for keys (run before every push)
	@! git grep -nE "AIza[0-9A-Za-z_-]{20,}" -- . || { echo "a Google API key is in the tree"; exit 1; }
	@if command -v gitleaks >/dev/null; then gitleaks detect --no-banner --redact; else echo "gitleaks not installed: only the AIza check ran"; fi

deploy-box:      ## deploy to the boxd VM `agj` (needs `boxd auth login` first)
	deploy/boxd.sh
