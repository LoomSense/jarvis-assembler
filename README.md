# Jarvis Assembler

**Scan a flat-pack box. An AI agent turns its paper guide into a 3D build you follow on your phone or in a Quest 3.**

<https://agj.boxd.sh>

**Who it's for**

- Anyone who buys flat-pack furniture and builds it at home.
- The shops that sell it, who take the calls when a build goes wrong.

**The problem**

You need three hours to build one drawer, because the guide is all drawings and not a single word. It will not tell you which of two look-alike boards you are holding, or which way round it goes. You find out three steps later, with the screwdriver still in your hand.

**Our solution**

Give it one of four things: the code on the box, a photo of the box, the product name, or the PDF guide. An AI agent rebuilds the furniture in 3D, one move at a time, so you can see what you are building before you touch a screw. The same 3D model is what the phone and the headset use to follow your real build and check each part.

![The phone flow in six screens: start, give the guide, built in 3D, your guide is ready, step by step, down to the screw](docs/img/phone-flow.jpg)

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
