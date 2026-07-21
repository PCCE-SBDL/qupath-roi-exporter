#!/usr/bin/env bash
#
# single_file_example.sh — example of running run_export.sh on a single
# sample OME-TIFF / GeoJSON pair.
#
# Uses the small sample dataset checked into examples/sample_data/ (a
# ~5000x5000px crop of a real slide, generated with
# tools/crop_sample_ome_tiff.groovy) so this runs out of the box right
# after cloning the repo — no external data needed.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_EXPORT="${SCRIPT_DIR}/../bin/run_export.sh"
SAMPLE_DIR="${SCRIPT_DIR}/sample_data"

"$RUN_EXPORT" \
    "${SAMPLE_DIR}/sample_data.ome.tif" \
    "${SAMPLE_DIR}/annotations.geojson" \
    "${SAMPLE_DIR}/roi_crops" \
    --roi-mode circle \
    --radius 100 \
    --magnifications 20,80 \
    --format png
