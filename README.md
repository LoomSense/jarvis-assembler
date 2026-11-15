# Jarvis Assembler

Scan a flat-pack box with your phone. An agent finds the assembly guide, rebuilds it as an animated 3D model, and then guides the real build on your phone or in a Meta Quest 3, in Greek or English.

Gen AI Summit Hackathon, Athens, 14–15 Nov 2026 · Try it: <https://agj.boxd.sh>

![The phone flow: home, find the guide, the 3D build, the ready guide, a step, build with help](docs/img/phone-flow.jpg)

## What the person does, in six steps

| # | Step | Built with |
|---|---|---|
| 1 | **Give the guide.** Scan the QR code or barcode on the box, type the product code or name, photograph the box, or upload the PDF. | ![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white) ![Gemini 4 Hydrogen](https://img.shields.io/badge/Gemini_4_Hydrogen-4285F4?style=flat-square&logo=googlegemini&logoColor=white) ![Claude Agent SDK](https://img.shields.io/badge/Claude_Agent_SDK-D97757?style=flat-square&logo=claude&logoColor=white)<br/>Phone web app. Gemini recognises the product from a photo; a web-search agent fetches the maker's PDF. |
| 2 | **The agent reads it.** It works out every part, screw and action in the guide. | ![Claude Agent SDK](https://img.shields.io/badge/Claude_Agent_SDK-D97757?style=flat-square&logo=claude&logoColor=white)<br/>The build agent. |
| 3 | **The parts are built in 3D.** Each part is written as CAD code and corrected until everything fits. | ![CadQuery](https://img.shields.io/badge/CadQuery-2E7D32?style=flat-square) ![NumPy](https://img.shields.io/badge/NumPy-013243?style=flat-square&logo=numpy&logoColor=white) ![fal](https://img.shields.io/badge/fal-6E56CF?style=flat-square)<br/>CAD code and our own fit checks; image-to-3D for shapes CAD cannot draw. |
| 4 | **The guide is animated.** One action at a time, each step checked against the guide's own drawing. | ![Blender 5.3](https://img.shields.io/badge/Blender_5.3-E87D0D?style=flat-square&logo=blender&logoColor=white) ![Gemini Decision 1](https://img.shields.io/badge/Gemini_Decision_1-4285F4?style=flat-square&logo=googlegemini&logoColor=white)<br/>Headless Blender makes the glTF and USDZ; Gemini compares each step with the drawing. |
| 5 | **Follow it on the phone.** A 3D walkthrough; life-size on your table on an iPhone. Or hold the camera over the parts and a coach checks them and speaks. | ![three.js](https://img.shields.io/badge/three.js-000000?style=flat-square&logo=threedotjs&logoColor=white) ![AR Quick Look](https://img.shields.io/badge/AR_Quick_Look-555555?style=flat-square&logo=apple&logoColor=white) ![Gemini Decision 1](https://img.shields.io/badge/Gemini_Decision_1-4285F4?style=flat-square&logo=googlegemini&logoColor=white) ![Gemini Live](https://img.shields.io/badge/Gemini_Live-4285F4?style=flat-square&logo=googlegemini&logoColor=white)<br/>Camera frames go over a WebSocket; the coach answers by voice. |
| 6 | **Or build with the headset.** It finds each board on the floor, puts the 3D guide on the real part, checks the part in your hand, speaks, and takes voice commands. Nothing is printed or stuck on the furniture. | ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white) ![Meta Spatial SDK](https://img.shields.io/badge/Meta_Spatial_SDK-0467DF?style=flat-square&logo=meta&logoColor=white) ![OpenCV](https://img.shields.io/badge/OpenCV-5C3EE8?style=flat-square&logo=opencv&logoColor=white) ![Gemini](https://img.shields.io/badge/Gemini-4285F4?style=flat-square&logo=googlegemini&logoColor=white)<br/>Native Quest 3 app: passthrough camera, hand tracking, vision on the device. |

## How it fits together

![How it fits together](docs/img/architecture.svg)

![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white) Every model key stays on the server.

## For other agents

Any agent can use it through an MCP server with 11 tools, a command line, `/llms.txt` and an OpenAPI file. For example, a furniture shop's customer-support agent gets "I am stuck on step 4 of my wardrobe", finds that product's guide, and answers with the exact step in 3D and how to undo the mistake, with no person on the line.
