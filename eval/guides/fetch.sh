#!/usr/bin/env bash
# Re-download the test assembly guides from their original sources.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p ikea other
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
get() { curl -fsSL -A "$UA" -o "$2" "$1" && echo "ok  $2"; }
get "https://www.ikea.com/gb/en/assembly_instructions/lack-side-table-white__AA-2606170-1-100.pdf" ikea/lack-side-table__AA-2606170-1.pdf
get "https://www.ikea.com/us/en/assembly_instructions/lack-nesting-tables-set-of-__AA-1893614-1_pub.pdf" ikea/lack-nesting-tables__AA-1893614-1.pdf
get "https://www.ikea.com/us/en/assembly_instructions/lack-coffee-table-white__AA-472482-3-2.pdf" ikea/lack-coffee-table__AA-472482-3.pdf
get "https://www.ikea.com/us/en/assembly_instructions/lack-console-table-black-brown__AA-492774-5-1.pdf" ikea/lack-console-table__AA-492774-5.pdf
get "https://www.ikea.com/us/en/assembly_instructions/knagglig-box-pine__AA-1159337-7-101.pdf" ikea/knagglig-box__AA-1159337-7.pdf
get "https://www.ikea.com/gb/en/assembly_instructions/bekvaem-step-stool-black__AA-498351-9-100.pdf" ikea/bekvaem-step-stool__AA-498351-9.pdf
get "https://www.ikea.com/us/en/assembly_instructions/kallax-shelf-unit-white__AA-2326605-1-100.pdf" ikea/kallax-shelf-unit__AA-2326605-1.pdf
get "https://www.ikea.com/us/en/assembly_instructions/kallax-shelf-unit-white__AA-2730880-1-100.pdf" ikea/kallax-shelf-unit__AA-2730880-1.pdf
get "https://cdn.shopify.com/s/files/1/2660/5202/files/426293.pdf" other/sauder-2-shelf-bookcase-426293.pdf
get "https://cdn.shopify.com/s/files/1/2660/5202/files/427466.PDF" other/sauder-bergen-circle-bookcase-427466.pdf
get "https://cdn.shopify.com/s/files/1/2660/5202/files/424122.pdf" other/sauder-trestle-5-shelf-bookcase-424122.pdf
get "https://pdf.lowes.com/productdocuments/51a4b491-dede-4c0f-821c-fdb286ccbb23/68649643.pdf" other/sauder-willow-place-bookcase-lowes-68649643.pdf
get "https://media-www.canadiantire.ca/manuals/product/0687564/068-7564-en-fr-3900d500-5b81-4044-a8bf-e9520853d363.pdf" other/sauder-county-line-nightstand-417771.pdf
get "https://cdn.fusionplus.net/documents/teknik/5431916_assembly.pdf" other/teknik-bookcase-5431916.pdf
# Optional: IKEA-Manual dataset with ground-truth 3D parts and plans (174 MB, git-ignored)
if [[ "${WITH_DATASET:-0}" == "1" ]]; then
  mkdir -p ../datasets && cd ../datasets
  curl -fSL -o ikea_manual_dataset.zip https://download.cs.stanford.edu/viscam/ikea_manual/dataset.zip
  unzip -q -o ikea_manual_dataset.zip -x '__MACOSX/*' -d ikea_manual
fi
