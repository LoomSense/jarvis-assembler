"""What the perception server reads from a job folder: `manifest.json` (PLAN §4.3) for steps, actions and
names, the reference sheets' labels (`refs/step-NN-sheet.json`, D19) and, when it is there, the approved
plan for the Greek names. The questions themselves are read in `questions.py`."""
from __future__ import annotations

import json
from pathlib import Path

from server.perception.logic import Expect

# Greek nouns with the accusative article, for hardware whose plan entry carries no Greek name.
KIND_EL = {"screw": "τη βίδα", "bolt": "το μπουλόνι", "dowel": "την καβίλια", "cam_lock": "το έκκεντρο",
           "cam_bolt": "το μπουλόνι", "nail": "το καρφί", "nut": "το παξιμάδι", "washer": "τη ροδέλα"}


def _read_json(path: Path) -> dict | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


class Guide:
    def __init__(self, job_dir: Path):
        self.dir = Path(job_dir)
        manifest = _read_json(self.dir / "manifest.json")
        if not manifest or not manifest.get("steps"):
            raise ValueError("the job has no manifest.json with steps yet")
        self.manifest = manifest
        self.mtime = (self.dir / "manifest.json").stat().st_mtime_ns
        self.title = str(manifest.get("title") or "furniture")
        self.steps: dict[int, dict] = {int(s["n"]): s for s in manifest["steps"]}
        self.order = sorted(self.steps)
        plan = _read_json(self.dir / "plan.approved.json") or _read_json(self.dir / "plan.json") or {}
        from_plan = {str(p.get("id")): p for p in plan.get("parts", [])}
        from_plan.update({str(h.get("code")): h for h in plan.get("hardware", [])})
        self.items: dict[str, dict] = {}
        for p in manifest.get("parts", []):
            self._add(str(p["id"]), "part", p, from_plan)
        for h in manifest.get("hardware", []):
            self._add(str(h["code"]), "hardware", h, from_plan)

    def _add(self, key: str, kind: str, entry: dict, from_plan: dict) -> None:
        extra = from_plan.get(key, {})
        name = str(entry.get("name") or key)
        length = extra.get("length_mm") or (extra.get("params") or {}).get("length")
        self.items[key] = {"id": key, "kind": kind, "name": name, "short": str(entry.get("short") or name).lower(),
                           "el": entry.get("el") or extra.get("el"), "hw_kind": entry.get("kind") or extra.get("kind"),
                           "length": length if isinstance(length, (int, float)) else None}

    def stale(self) -> bool:
        """True when manifest.json was rewritten since it was read (the job was rebuilt)."""
        try:
            return (self.dir / "manifest.json").stat().st_mtime_ns != self.mtime
        except OSError:
            return False

    # ---- steps ----------------------------------------------------------------------------------

    def step(self, n) -> dict | None:
        return self.steps.get(n) if isinstance(n, int) and not isinstance(n, bool) else None

    def next_step(self, n: int) -> dict | None:
        i = self.order.index(n)
        return self.steps[self.order[i + 1]] if i + 1 < len(self.order) else None

    def page(self, n: int) -> int | None:
        page = (self.steps[n].get("cite") or {}).get("page")
        return page if isinstance(page, int) else None

    def actions(self, n: int) -> list[dict]:
        return [a for a in self.steps[n].get("actions") or [] if isinstance(a, dict)]

    def parts(self, n: int) -> list[str]:
        return [str(p) for p in self.steps[n].get("pickParts") or []]

    def hardware(self, n: int) -> list[tuple[str, int | None]]:
        return [(str(h["code"]), h.get("qty")) for h in self.steps[n].get("hardware") or [] if "code" in h]

    def turn_text(self, n: int) -> dict | None:
        """How the assembly must stand for step `n`: the text of the last `turn` action up to and including it."""
        for step in reversed([m for m in self.order if m <= n]):
            for action in reversed(self.actions(step)):
                text = action.get("text")
                if action.get("type") == "turn" and isinstance(text, dict) and text.get("en") and text.get("el"):
                    return {"en": str(text["en"]), "el": str(text["el"])}
        return None

    def next_items(self, n: int) -> list[str]:
        """Parts and hardware of the next step that this step does not use: bringing one over is out of order."""
        nxt = self.next_step(n)
        if nxt is None:
            return []
        mine = set(self.parts(n)) | {c for c, _ in self.hardware(n)}
        theirs = self.parts(nxt["n"]) + [c for c, _ in self.hardware(nxt["n"])]
        return [i for i in theirs if i not in mine]

    def expect(self, n: int, action: int | None = None) -> Expect:
        actions = self.actions(n)
        act = actions[action] if action is not None and 0 <= action < len(actions) else None
        kind = act.get("type") if act else None
        item = (act.get("code") or act.get("partId")) if kind == "fasten" else act.get("partId") if kind == "place" else None
        nxt = self.next_step(n)
        times = {c: q for c, q in self.hardware(n) if isinstance(q, int) and not isinstance(q, bool) and q > 1}
        for part in self.parts(n):                                   # a part placed several times in one step
            placed = sum(1 for a in actions if a.get("type") == "place" and str(a.get("partId")) == part)
            if placed > 1:
                times[part] = placed
        return Expect(step=n, page=self.page(n), parts=frozenset(self.parts(n)),
                      hardware=frozenset(c for c, _ in self.hardware(n)), times=tuple(sorted(times.items())),
                      action=action if act else None, action_kind=kind, action_id=str(item) if item else None,
                      last_action=act is None or action == len(actions) - 1, last_step=nxt is None,
                      next_step=nxt["n"] if nxt else None, next_page=self.page(nxt["n"]) if nxt else None)

    # ---- the reference sheet ----------------------------------------------------------------------

    def _inside(self, rel: str | None) -> Path | None:
        if not rel:
            return None
        base = self.dir.resolve()
        path = (base / rel).resolve()
        return path if path.is_relative_to(base) and path.is_file() else None

    def sheet_image(self, n: int) -> Path | None:
        return self._inside(self.steps[n].get("refSheet") or f"refs/step-{n:02d}-sheet.png")

    def sheet(self, n: int) -> dict:
        """The labels printed on the sheet and why each is there. Without the file, the candidates are
        worked out from the manifest the same way the pipeline does."""
        rel = self.steps[n].get("refSheet") or f"refs/step-{n:02d}-sheet.png"
        path = self._inside(str(Path(rel).with_suffix(".json")))
        data = _read_json(path) if path else None
        if data and isinstance(data.get("candidates"), list):
            return data
        out = [{"label": p, "kind": "part", "why": "this_step"} for p in self.parts(n)]
        out += [{"label": c, "kind": "hardware", "why": "this_step"} for c, _ in self.hardware(n)]
        seen = {c["label"] for c in out}
        for item in self.next_items(n):
            out.append({"label": item, "kind": self.kind(item) or "part", "why": "next_step"})
            seen.add(item)
        out += [{"label": k, "kind": "hardware", "why": "other_hardware"}
                for k, v in self.items.items() if v["kind"] == "hardware" and k not in seen]
        return {"step": n, "left": "the correct result of this step", "right": "labelled candidate parts and hardware",
                "candidates": out}

    # ---- names ------------------------------------------------------------------------------------

    def kind(self, item: str | None) -> str | None:
        return self.items[item]["kind"] if item in self.items else None

    def name(self, item: str) -> str:
        return self.items[item]["name"] if item in self.items else str(item)

    def short(self, item: str) -> str:
        return self.items[item]["short"] if item in self.items else str(item)

    def label(self, item: str | None) -> str | None:
        """How an item is named in an event and in a [PERCEPTION] line: `long screw 108363`, `plain slat`."""
        if item is None:
            return None
        if self.kind(item) == "hardware":
            return f"{self.short(item)} {item}"
        return self.short(item)

    def spoken_en(self, item: str) -> str:
        """`the long screw, 108363` / `the plain slat`."""
        if self.kind(item) == "hardware":
            return f"the {self.short(item)}, {item}"
        return f"the {self.short(item)}" if item in self.items else str(item)

    def spoken_el(self, item: str) -> str | None:
        """A Greek object phrase in the accusative (`μακριά βίδα, 108363`, `τη σανίδα με τη λαβή`), or
        None when the job carries no Greek name for a part."""
        info = self.items.get(item)
        if info is None:
            return None
        if info["kind"] == "hardware":
            if info["el"]:
                return f"{info['el']}, {item}"
            return f"{KIND_EL.get(str(info['hw_kind']), 'το εξάρτημα')} {item}"
        return info["el"] or None
