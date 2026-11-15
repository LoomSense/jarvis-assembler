# Guides we wrote ourselves

For a product that has no printed manual with us: the drawer on the demo table.

| File | What it is |
|---|---|
| `drawer-photo-guide.pdf` | 16 pages. Page 1 lists the parts, the hardware and six steps in words; pages 2–16 are the owner's photos of the real parts, the hardware, three moments of the assembly and the finished drawer, each with a caption. Written on Sun 15 Nov from 15 photos. This is what the build agent was given. |
| `drawer-assembly-guide.pdf` | 9 pages. The printed-style guide made from the job the agent built from the file above: `python -m pipeline.paper_guide <job_dir>`. Line drawings, numbered exploded steps, counts. |

Built on the demo host as job `1115-1145-c781c0` (agent on `claude-fable-5-2`, about 10 minutes).

Known limits:
- Sizes are estimates read off the photos (front panel about 44 × 22 cm); nothing was measured.
- Upload it as a PDF (`file`), not as photos (`pages`): the photo upload turns pictures into black line drawings, which suits a scanned booklet and ruins photos of real parts.
- The agent's drawing check compares renders with the guide's pictures. Here those are photos and a page of text, so it reported steps 3–6 as different; the flags in the plan say why.
- The two side panels are modelled as a left and a right part; the printed-style guide lists them as one entry, 2x.
