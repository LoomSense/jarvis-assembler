# Jarvis Assembler

Scan a flat-pack box with your phone. An agent finds the assembly guide, rebuilds it as an animated 3D model, and then guides the real build on your phone or in a Meta Quest 3, in Greek or English.

<https://agj.boxd.sh>

![The phone flow: home, find the guide, the 3D build, the ready guide, a step, build with help](docs/img/phone-flow.jpg)

## How it works, in six steps

### 1 · Show it your furniture

The box just arrived and it is sitting on your floor. Scan the code on it, type its name, take a photo of it, or upload the guide if you have the PDF. We find the right assembly guide for you.

The app → ![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white) &nbsp;·&nbsp; Knowing the box from a photo → ![Gemini](https://img.shields.io/badge/Gemini-4285F4?style=flat-square&logo=googlegemini&logoColor=white) &nbsp;·&nbsp; Finding the guide online → ![Claude Agent SDK](https://img.shields.io/badge/Claude_Agent_SDK-D97757?style=flat-square&logo=claude&logoColor=white)

### 2 · An agent reads the guide

Paper guides are drawings with no words. An AI agent goes through every page and works out which parts there are, how many screws, and what happens in each step.

Reading the guide → ![Claude Agent SDK](https://img.shields.io/badge/Claude_Agent_SDK-D97757?style=flat-square&logo=claude&logoColor=white)

### 3 · Every part is built in 3D

The agent draws each board and each screw as a real 3D shape, with the holes in the right places. If two parts do not fit together, it goes back and fixes them.

Drawing the parts → ![CadQuery](https://img.shields.io/badge/CadQuery-2E7D32?style=flat-square) &nbsp;·&nbsp; Checking that they fit → ![NumPy](https://img.shields.io/badge/NumPy-013243?style=flat-square&logo=numpy&logoColor=white) &nbsp;·&nbsp; Odd shapes → ![fal](https://img.shields.io/badge/fal-6E56CF?style=flat-square)

### 4 · The guide becomes a 3D animation

The parts come together one move at a time, like a short film of the build. Each step is compared with the drawing in the original guide, to make sure it shows the same thing.

The animation → ![Blender](https://img.shields.io/badge/Blender-E87D0D?style=flat-square&logo=blender&logoColor=white) &nbsp;·&nbsp; Comparing with the drawing → ![Gemini](https://img.shields.io/badge/Gemini-4285F4?style=flat-square&logo=googlegemini&logoColor=white)

### 5 · Follow it on your phone

Go through the steps and turn the model to see it from any side. On an iPhone you can put it on your table at its real size. If you want help, point the camera at your parts and a voice coach tells you whether you got it right.

The 3D view → ![three.js](https://img.shields.io/badge/three.js-000000?style=flat-square&logo=threedotjs&logoColor=white) &nbsp;·&nbsp; On your table → ![AR Quick Look](https://img.shields.io/badge/AR_Quick_Look-555555?style=flat-square&logo=apple&logoColor=white) &nbsp;·&nbsp; Checking through the camera → ![Gemini](https://img.shields.io/badge/Gemini-4285F4?style=flat-square&logo=googlegemini&logoColor=white) &nbsp;·&nbsp; The voice coach → ![Gemini Live](https://img.shields.io/badge/Gemini_Live-4285F4?style=flat-square&logo=googlegemini&logoColor=white)

### 6 · Or put on the headset

With a Meta Quest 3 you see the guide on top of the real furniture. It lights up the board to pick up, shows where it goes, and tells you if you are holding the wrong one. You can talk to it, so your hands stay free.

The headset app → ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white) ![Meta Spatial SDK](https://img.shields.io/badge/Meta_Spatial_SDK-0467DF?style=flat-square&logo=meta&logoColor=white) &nbsp;·&nbsp; Seeing the boards → ![OpenCV](https://img.shields.io/badge/OpenCV-5C3EE8?style=flat-square&logo=opencv&logoColor=white) &nbsp;·&nbsp; Checks and voice → ![Gemini](https://img.shields.io/badge/Gemini-4285F4?style=flat-square&logo=googlegemini&logoColor=white)

## How it fits together

![How it fits together](docs/img/architecture.svg)

![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white) Every model key stays on the server.

## For other agents

Any agent can use it through an MCP server with 11 tools, a command line, `/llms.txt` and an OpenAPI file. For example, a furniture shop's customer-support agent gets "I am stuck on step 4 of my wardrobe", finds that product's guide, and answers with the exact step in 3D and how to undo the mistake, with no person on the line.
