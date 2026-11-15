"""Phase-2 live perception (PLAN §4.5, §6): frames in over a WebSocket, guidance events out.

The request, the questions, the scheduling of requests and the decision logic for the plan's events come
from `pipeline/perception/` and `pipeline/check/decisions.py`, where they were measured against the real
API. This package is the server around them:

    routes.py     `router`: WS /ws/perception/{job_id}. The protocol is in README.md. Also
                  POST /api/perception/{job_id}/locate (locate.py: which step one picture is at)
    session.py    one session per job: devices, gates, acks, failure handling, logs; frames go through
                  `pipeline.perception.stream.Stream`
    wire.py       the binary frame layout
    guide.py      what is read from the job folder: manifest, sheet labels, names
    questions.py  the request for one frame: `pipeline.perception.request` plus one question
    decider.py    who answers: `pipeline.check.decisions`, or a scripted stand-in for tests and demos
    logic.py      `Coach`: `pipeline.perception.logic.Watcher` plus what it has no notion of (the current
                  action, confirmations, a head-mounted camera, the hand summary, mute)
    phrases.py    the sentence for each event in English and Greek, and the [PERCEPTION ...] line
    imaging.py    cheap local checks on a frame before a call is spent on it
    config.py     every tunable number of this package, and overrides for the pipeline's

Tools: eval/phase2/try_decision.py (one real request), eval/phase2/replay.py (clips through the route).
"""
