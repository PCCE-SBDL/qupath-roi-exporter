#!/usr/bin/env bash
#
# batch_example.sh — example of running run_export.sh across multiple samples.
#
# Replace the placeholder paths below with your own data locations, or adapt
# the loop to read sample roots from a CSV/text file. This assumes each
# sample directory contains an "H&E" subfolder with an *.ome.tif file and an
# "Annotations" subfolder with a matching .geojson file.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_EXPORT="${SCRIPT_DIR}/../bin/run_export.sh"

SAMPLE_ROOTS=(
    "/path/to/data/sample_01"
    "/path/to/data/sample_02"
    "/path/to/data/sample_03"
)

for root in "${SAMPLE_ROOTS[@]}"; do
    ome_tiff="${root}/H&E/"*.ome.tif
    geojson="${root}/Annotations/annotation.geojson"
    output_dir="${root}/Annotations/roi_crops"

    "$RUN_EXPORT" $ome_tiff "$geojson" "$output_dir" \
        --roi-mode circle \
        --radius 300 \
        --magnifications 20,60,120 \
        --format jpg \
        --jpeg-quality 0.95 \
        --draw-boundary true
done
