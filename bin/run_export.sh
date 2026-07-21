#!/usr/bin/env bash
#
# run_export.sh — command-line wrapper for export_multires_ROIs.groovy
#
# Usage:
#   ./run_export.sh <ome_tiff> <geojson> <output_dir> [options]
#
# Required positional arguments:
#   <ome_tiff>    Path to the H&E OME-TIFF file
#   <geojson>     Path to the matching GeoJSON annotation file
#   <output_dir>  Directory to write exported crops into
#
# Optional flags (all have defaults matching the .groovy script's defaults):
#   --roi-mode <original|circle>       (default: circle)
#   --radius <microns>                 (default: 250.0)      [only used if --roi-mode circle]
#   --crop-mode <annotation|fixed_fov> (default: fixed_fov)
#   --magnifications <csv>             (default: 10,20,40,60,100,200)
#   --format <png|tif|jpg>             (default: png)
#   --jpeg-quality <0.0-1.0>           (default: 0.95)        [only used if --format jpg]
#   --tiff-compression <LZW|Deflate|NONE> (default: LZW)      [only used if --format tif]
#   --png-compression-level <0-9>      (default: 6)           [only used if --format png]
#   --draw-boundary <true|false>       (default: true)
#   --boundary-width <pixels>          (default: 3.0)
#   --add-scale-bar <true|false>       (default: true)
#   --scale-bar-position <bottom-left|bottom-right> (default: bottom-right)
#   --add-circle-to-hierarchy <true|false> (default: false)
#   --qupath <path>                    Path to QuPath executable
#                                       (default: $QUPATH_EXE env var if set, else auto-detected
#                                       common install location for your OS — see README.md)
#   --script <path>                    Path to the .groovy script
#                                       (default: ../scripts/export_multires_ROIs.groovy, next to this file)
#
# Example:
#   ./run_export.sh /data/slide01.ome.tiff /data/slide01.geojson /data/out \
#       --roi-mode circle --radius 200 --magnifications 20,40 --format png
#
# QuPath executable resolution order:
#   1. --qupath <path> flag
#   2. $QUPATH_EXE environment variable
#   3. Common per-OS default install locations (see README.md for details)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---- Defaults ----
ROI_MODE="circle"
RADIUS="250.0"
CROP_MODE="fixed_fov"
MAGNIFICATIONS="10,20,40,60,100,200"
FORMAT="png"
JPEG_QUALITY="0.95"
TIFF_COMPRESSION="LZW"
PNG_COMPRESSION_LEVEL="6"
DRAW_BOUNDARY="true"
BOUNDARY_WIDTH="3.0"
ADD_SCALE_BAR="true"
SCALE_BAR_POSITION="bottom-right"
ADD_CIRCLE_TO_HIERARCHY="false"
QUPATH_EXE="${QUPATH_EXE:-}"
GROOVY_SCRIPT="${PACKAGE_ROOT}/scripts/export_multires_ROIs.groovy"

# ---- Auto-detect QuPath if not set via env var ----
find_qupath_auto() {
    local candidates=()
    case "$(uname -s)" in
        Darwin)
            # The executable inside Contents/MacOS is not always named "QuPath" —
            # e.g. "QuPath-0.7.0-arm64.app" contains "QuPath-0.7.0-arm64". Take
            # whatever single executable file is actually in there.
            while IFS= read -r -d '' app; do
                local macos_dir="$app/Contents/MacOS"
                if [ -d "$macos_dir" ]; then
                    while IFS= read -r -d '' exe; do
                        candidates+=("$exe")
                    done < <(find "$macos_dir" -maxdepth 1 -type f -perm -u+x -print0 2>/dev/null)
                fi
            done < <(find /Applications -maxdepth 1 -iname "QuPath*.app" -print0 2>/dev/null)
            ;;
        Linux)
            candidates+=(
                "$HOME/QuPath/bin/QuPath"
                "/opt/QuPath/bin/QuPath"
                "/usr/local/QuPath/bin/QuPath"
            )
            while IFS= read -r -d '' bin; do
                candidates+=("$bin")
            done < <(find "$HOME" /opt /usr/local -maxdepth 4 -iname "QuPath" -type f -print0 2>/dev/null)
            ;;
        MINGW*|MSYS*|CYGWIN*)
            candidates+=(
                "/c/Program Files/QuPath/QuPath.exe"
                "/c/Program Files/QuPath.app/QuPath.exe"
            )
            while IFS= read -r -d '' exe; do
                candidates+=("$exe")
            done < <(find "/c/Program Files" -maxdepth 3 -iname "QuPath.exe" -print0 2>/dev/null)
            ;;
    esac
    for c in "${candidates[@]}"; do
        if [ -x "$c" ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

if [ -z "$QUPATH_EXE" ]; then
    QUPATH_EXE="$(find_qupath_auto || true)"
fi

usage() {
    grep '^#' "${BASH_SOURCE[0]}" | sed 's/^#//' | sed '1d'
    exit 1
}

# ---- Required positional args ----
case "${1:-}" in -h|--help) usage ;; esac
if [ "$#" -lt 3 ]; then
    echo "ERROR: missing required arguments." >&2
    usage
fi

OME_TIFF="$1"; shift
GEOJSON="$1"; shift
OUTPUT_DIR="$1"; shift

# ---- Parse optional flags ----
while [ "$#" -gt 0 ]; do
    case "$1" in
        --roi-mode) ROI_MODE="$2"; shift 2 ;;
        --radius) RADIUS="$2"; shift 2 ;;
        --crop-mode) CROP_MODE="$2"; shift 2 ;;
        --magnifications) MAGNIFICATIONS="$2"; shift 2 ;;
        --format) FORMAT="$2"; shift 2 ;;
        --jpeg-quality) JPEG_QUALITY="$2"; shift 2 ;;
        --tiff-compression) TIFF_COMPRESSION="$2"; shift 2 ;;
        --png-compression-level) PNG_COMPRESSION_LEVEL="$2"; shift 2 ;;
        --draw-boundary) DRAW_BOUNDARY="$2"; shift 2 ;;
        --boundary-width) BOUNDARY_WIDTH="$2"; shift 2 ;;
        --add-scale-bar) ADD_SCALE_BAR="$2"; shift 2 ;;
        --scale-bar-position) SCALE_BAR_POSITION="$2"; shift 2 ;;
        --add-circle-to-hierarchy) ADD_CIRCLE_TO_HIERARCHY="$2"; shift 2 ;;
        --qupath) QUPATH_EXE="$2"; shift 2 ;;
        --script) GROOVY_SCRIPT="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "ERROR: unknown option '$1'" >&2; usage ;;
    esac
done

# ---- Validate inputs ----
if [ ! -f "$OME_TIFF" ]; then
    echo "ERROR: OME-TIFF not found: $OME_TIFF" >&2
    exit 1
fi
if [ ! -f "$GEOJSON" ]; then
    echo "ERROR: GeoJSON not found: $GEOJSON" >&2
    exit 1
fi
if [ -z "$QUPATH_EXE" ] || [ ! -x "$QUPATH_EXE" ]; then
    echo "ERROR: QuPath executable not found${QUPATH_EXE:+ or not executable: $QUPATH_EXE}." >&2
    echo "       Fix this by either:" >&2
    echo "         1. Passing the path explicitly:  --qupath /path/to/QuPath" >&2
    echo "         2. Setting the QUPATH_EXE environment variable (see README.md)" >&2
    exit 1
fi
if [ ! -f "$GROOVY_SCRIPT" ]; then
    echo "ERROR: Groovy script not found: $GROOVY_SCRIPT" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

echo "Running QuPath export:"
echo "  Image:      $OME_TIFF"
echo "  GeoJSON:    $GEOJSON"
echo "  Output:     $OUTPUT_DIR"
echo "  ROI mode:   $ROI_MODE (radius=${RADIUS}um)"
echo "  Crop mode:  $CROP_MODE"
echo "  Magnifications: $MAGNIFICATIONS"
echo "  Format:     $FORMAT"
echo ""

"$QUPATH_EXE" script "$GROOVY_SCRIPT" \
    -i "$OME_TIFF" \
    -a "geojson=${GEOJSON}" \
    -a "output=${OUTPUT_DIR}" \
    -a "roiMode=${ROI_MODE}" \
    -a "radiusMicrons=${RADIUS}" \
    -a "cropMode=${CROP_MODE}" \
    -a "magnifications=${MAGNIFICATIONS}" \
    -a "format=${FORMAT}" \
    -a "jpegQuality=${JPEG_QUALITY}" \
    -a "tiffCompression=${TIFF_COMPRESSION}" \
    -a "pngCompressionLevel=${PNG_COMPRESSION_LEVEL}" \
    -a "drawBoundary=${DRAW_BOUNDARY}" \
    -a "boundaryWidthPx=${BOUNDARY_WIDTH}" \
    -a "addScaleBar=${ADD_SCALE_BAR}" \
    -a "scaleBarPosition=${SCALE_BAR_POSITION}" \
    -a "addCircleToHierarchy=${ADD_CIRCLE_TO_HIERARCHY}"

echo ""
echo "Done. Check ${OUTPUT_DIR} for exported images."
