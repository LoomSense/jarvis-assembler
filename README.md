# Jarvis Assembler

**Scan a flat-pack box. An AI agent turns its paper guide into a 3D build you follow on your phone or in a Quest 3.**

<https://agj.boxd.sh>

**Status, Sun 15 Nov, 16:10 Athens (corrected 16:35).** On the Quest 3 the app guided the team's own drawer from step 2 to the first peg of step 6 without the Next button, confirmed each of the four cam bolts aloud, reported a wrong item in the hand, and read the boards on the floor by size with the face that is up. In a later session (16:21 to 16:28) it reached the finish, with four presses on Next at the cam locks and the last two pegs not confirmed in the hand. Not yet shown on the headset: a whole build with no press on Next. Not reliable: which way round a side panel goes, a cam bolt against a peg in the hand, board recognition in direct sunlight. Each claim with its log event: [docs/SUBMISSION.md](docs/SUBMISSION.md).

**Who it's for**

- Anyone who buys flat-pack furniture and builds it at home.
- The shops that sell it, who take the calls when a build goes wrong.

**The problem**

You need three hours to build one drawer, because the guide is all drawings and not a single word. It will not tell you which of two look-alike boards you are holding, or which way round it goes. You find out three steps later, with the screwdriver still in your hand.

**Our solution**

Give it one of four things: the code on the box, a photo of the box, the product name, or the PDF guide. An AI agent rebuilds the furniture in 3D, one move at a time, so you can see what you are building before you touch a screw. The same 3D model is what the phone and the headset use to follow your real build and check each part.

Under the hood it combines a coding agent that reads the drawings and writes CAD (Claude Agent SDK, CadQuery), 3D animation in Blender, decision models that check live camera frames (Gemini Decisions API), a voice agent (Gemini Live), and object tracking on the Quest 3 itself with OpenCV that locks the 3D guide onto the real boards. It is hosted on one boxd VM, because the build needs a whole Linux machine for headless Blender and the agent's code, behind a public HTTPS address.

![The phone flow in six screens: start, scan the code on the box, the agent reads the guide, a part being built in 3D, the guide is ready, a step](docs/img/phone-flow.jpg)

## How it works

![Four steps build the 3D guide, then you follow it on your phone or in a Quest 3](docs/img/steps.svg)

## How it fits together

![How it fits together](docs/img/architecture.svg)

## For other agents

Any agent can use it through an MCP server with 11 tools. For example, a furniture shop's customer-support agent gets "I am stuck on step 3 of my box", asks the guide, and answers with the exact step and the part to pick up, with no person on the line.

The support agent calls one tool:

```json
{ "tool": "ask_guide", "job_id": "1115-1432-ef587a", "step": 3, "question": "What do I do in this step?" }
```

and gets back an answer it can say, the part to light up in the 3D view, and the page of the guide it came from:

```json
{
  "answer": "Turn the assembly upside down. Then place the second side panel against the open end of the bottom panel, as shown on page 4.",
  "actions": [{ "type": "highlight_part", "part_id": "side_panel" }],
  "undo": null,
  "cites": { "page": 4, "step": 3 },
  "latency_ms": 4116,
  "model": "gemini-4-hydrogen"
}
```
