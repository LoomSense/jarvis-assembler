# Test material: assembly guides

Downloaded Saturday 14 Nov 2026 for testing the pipeline (`docs/PLAN.md` §5, §9). **Not committed yet, for team review.**

The PDFs are copyrighted by their manufacturers (IKEA, Sauder, Teknik). Use them for internal testing only. Before anything goes to the public repo, prefer committing only this README and `fetch.sh`, which downloads the PDFs from their original URLs, rather than the PDFs themselves.

## Recommended use

| Role | Guide | Why |
|---|---|---|
| **Golden guide (phase 1)** | `ikea/knagglig-box__AA-1159337-7.pdf` | 8 clear numbered steps, all parts are boards or slats (ideal for CadQuery), only 2 hardware types (4× and 24× screws), single product on every page. Cheap to buy, so it can also be the physical product for phase 2. |
| **Smoke test** | `ikea/lack-side-table__AA-2606170-1.pdf` | Smallest possible case: a top, 4 legs, 4 screws, 4 pages. Also in the IKEA-Manual dataset with ground-truth 3D parts (see below). |
| **Stretch guide with ground truth** | `ikea/bekvaem-step-stool__AA-498351-9.pdf` | 6 steps, mixed shapes (treads, angled legs, a brace bar), left/right mirrored parts ("L" and "R" callouts), which tests orientation checks. Ground truth in the dataset as `Chair/bekvam_3017`. |
| **Hard case** | `ikea/kallax-shelf-unit__AA-2326605-1.pdf` | 24 pages, dowels and cam locks, multi-language warnings. Tests plan extraction at scale. |
| **Different drawing style** | `other/sauder-*.pdf`, `other/teknik-bookcase-5431916.pdf` | Non-IKEA guides: lettered parts, text instructions mixed with drawings, cam locks and dowels. Tests that we are not overfitting to IKEA's style. |

## Inventory

| File | Product | Brand | Pages | Source |
|---|---|---|---|---|
| `ikea/lack-side-table__AA-2606170-1.pdf` | LACK side table | IKEA | 4 | [ikea.com/gb](https://www.ikea.com/gb/en/assembly_instructions/lack-side-table-white__AA-2606170-1-100.pdf) |
| `ikea/lack-nesting-tables__AA-1893614-1.pdf` | LACK nesting tables, set of 2 | IKEA | 4 | [ikea.com/us](https://www.ikea.com/us/en/assembly_instructions/lack-nesting-tables-set-of-__AA-1893614-1_pub.pdf) |
| `ikea/lack-coffee-table__AA-472482-3.pdf` | LACK coffee table | IKEA | 8 | [ikea.com/us](https://www.ikea.com/us/en/assembly_instructions/lack-coffee-table-white__AA-472482-3-2.pdf) |
| `ikea/lack-console-table__AA-492774-5.pdf` | LACK console table | IKEA | 8 | [ikea.com/us](https://www.ikea.com/us/en/assembly_instructions/lack-console-table-black-brown__AA-492774-5-1.pdf) |
| `ikea/knagglig-box__AA-1159337-7.pdf` | KNAGGLIG box, pine | IKEA | 8 | [ikea.com/us](https://www.ikea.com/us/en/assembly_instructions/knagglig-box-pine__AA-1159337-7-101.pdf) |
| `ikea/bekvaem-step-stool__AA-498351-9.pdf` | BEKVÄM step stool | IKEA | 12 | [ikea.com/gb](https://www.ikea.com/gb/en/assembly_instructions/bekvaem-step-stool-black__AA-498351-9-100.pdf) |
| `ikea/kallax-shelf-unit__AA-2326605-1.pdf` | KALLAX shelf unit | IKEA | 24 | [ikea.com/us](https://www.ikea.com/us/en/assembly_instructions/kallax-shelf-unit-white__AA-2326605-1-100.pdf) |
| `ikea/kallax-shelf-unit__AA-2730880-1.pdf` | KALLAX shelf unit (newer version) | IKEA | 24 | [ikea.com/us](https://www.ikea.com/us/en/assembly_instructions/kallax-shelf-unit-white__AA-2730880-1-100.pdf) |
| `other/sauder-2-shelf-bookcase-426293.pdf` | 2-shelf bookcase, model 426293 | Sauder | 20 | [cdn.shopify.com](https://cdn.shopify.com/s/files/1/2660/5202/files/426293.pdf) |
| `other/sauder-bergen-circle-bookcase-427466.pdf` | Bergen Circle bookcase, model 427466 | Sauder | 24 | [cdn.shopify.com](https://cdn.shopify.com/s/files/1/2660/5202/files/427466.PDF) |
| `other/sauder-trestle-5-shelf-bookcase-424122.pdf` | Trestle 5-shelf bookcase, model 424122 | Sauder | 24 | [cdn.shopify.com](https://cdn.shopify.com/s/files/1/2660/5202/files/424122.pdf) |
| `other/sauder-willow-place-bookcase-lowes-68649643.pdf` | Willow Place bookcase | Sauder | 32 | [pdf.lowes.com](https://pdf.lowes.com/productdocuments/51a4b491-dede-4c0f-821c-fdb286ccbb23/68649643.pdf) |
| `other/sauder-county-line-nightstand-417771.pdf` | County Line side table / nightstand, model 417771 | Sauder | 24 | [canadiantire.ca](https://media-www.canadiantire.ca/manuals/product/0687564/068-7564-en-fr-3900d500-5b81-4044-a8bf-e9520853d363.pdf) |
| `other/teknik-bookcase-5431916.pdf` | Hudson 2-shelf bookcase, model 5431916 | Teknik | 16 | [fusionplus.net](https://cdn.fusionplus.net/documents/teknik/5431916_assembly.pdf) |

Step counts were only checked by eye for KNAGGLIG (8 steps) and BEKVÄM (6 steps).

## Dataset with ground truth: IKEA-Manual (Stanford, NeurIPS 2022)

Location: `eval/datasets/ikea_manual/` (491 MB unpacked, plus the 174 MB zip). It is **git-ignored** by `eval/datasets/.gitignore`, so it can't be committed by accident (GitHub rejects files over 100 MB).

- **Contents:** 102 IKEA objects (57 chairs, 19 tables, 11 misc, 8 benches, 4 desks, 3 shelves). Each has:
  - its manual PDF (`pdfs/<Category>/<name>/0.pdf`)
  - its 3D parts as OBJ files (`parts/<Category>/<name>/NN.obj`)
  - the step-by-step assembly plan and part connections (`main_data.json`)
  - part masks and camera matrices per step
- **Code licence:** MIT (`code/part_assembly/README.md`). The licence of the PDFs and models is not stated, so use them for internal evaluation only.
- **Overlap with our guides:**

  | Object | Steps | Parts | Plan |
  |---|---|---|---|
  | `Table/lack` | 2 | 6 | step 1 joins the top with 4 legs; step 2 adds the shelf |
  | `Chair/bekvam_3017` | 5 | 8 | |
  | `Desk/flisat` | 9 | 15 | |
  | `Table/vittsjo_1` | 3 | 5 | |

  The dataset's manual versions may differ from the current ones in `ikea/`, so compare against the dataset's own `0.pdf`.

**What it gives us:**
- **Plan extraction:** score our `plan.json` against the ground-truth steps and connections (AGT-04).
- **Generated parts:** compare our CadQuery parts with the real OBJ parts for proportions (CAD-02).
- **Placement:** check that our final assembly matches the ground-truth arrangement (CAD-04).

## Recreating the folder

Run `./fetch.sh` from this folder to download every guide again from the source URLs above.
