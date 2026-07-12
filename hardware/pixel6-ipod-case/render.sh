#!/bin/sh
set -eu

MODEL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE=pixel6_ipod_case.scad
IMAGE=${OPENSCAD_DOCKER_IMAGE:-openscad/openscad:2021.01}
PLATFORM=${OPENSCAD_DOCKER_PLATFORM:-linux/amd64}
CHECK=0
PULL=0
ONLY=all
STALE=0

usage() {
    cat <<'EOF'
Usage: ./render.sh [options]

Render the Pixel 6 enclosure with OpenSCAD's official Docker image.

Options:
  --check               Fail if checked-in renders are stale.
  --pull                Pull the pinned renderer image first.
  --only all|stl|preview
                        Render only the selected output group.
  --image IMAGE         Override the renderer image.
  --platform PLATFORM   Override the Docker platform.
  -h, --help            Show this help.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --check) CHECK=1 ;;
        --pull) PULL=1 ;;
        --only)
            [ "$#" -ge 2 ] || { echo "--only requires a value" >&2; exit 2; }
            ONLY=$2
            shift
            ;;
        --image)
            [ "$#" -ge 2 ] || { echo "--image requires a value" >&2; exit 2; }
            IMAGE=$2
            shift
            ;;
        --platform)
            [ "$#" -ge 2 ] || { echo "--platform requires a value" >&2; exit 2; }
            PLATFORM=$2
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

case "$ONLY" in
    all|stl|preview) ;;
    *) echo "--only must be all, stl, or preview" >&2; exit 2 ;;
esac

command -v docker >/dev/null 2>&1 || {
    echo "Docker is required; the host OpenSCAD app is intentionally never invoked." >&2
    exit 2
}

docker info --format '{{.ServerVersion}}' >/dev/null
if [ "$PULL" -eq 1 ]; then
    docker pull --platform "$PLATFORM" "$IMAGE"
fi

TEMP_DIR=$(mktemp -d "$MODEL_DIR/.render.XXXXXX")
TEMP_NAME=$(basename "$TEMP_DIR")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

docker_run() {
    docker run --rm \
        --platform "$PLATFORM" \
        --user "$(id -u):$(id -g)" \
        --env HOME=/tmp \
        --volume "$MODEL_DIR:/work" \
        --workdir /work \
        "$IMAGE" "$@"
}

render_stl() {
    part=$1
    filename=$2
    output="$TEMP_DIR/$filename"
    docker_run openscad \
        -D "part=\"$part\"" \
        --export-format asciistl \
        -o "/work/$TEMP_NAME/$filename" \
        "$SOURCE"
    [ -s "$output" ] || { echo "Empty STL: $filename" >&2; exit 1; }
    grep -q '^solid ' "$output" || { echo "Invalid ASCII STL: $filename" >&2; exit 1; }
    grep -q '^endsolid OpenSCAD_Model$' "$output" || {
        echo "Incomplete ASCII STL: $filename" >&2
        exit 1
    }
    facets=$(grep -c 'facet normal' "$output")
    [ "$facets" -gt 0 ] || { echo "STL has no facets: $filename" >&2; exit 1; }
    echo "Validated $filename: $facets facets"
}

render_preview() {
    output="$TEMP_DIR/assembly-preview.png"
    docker run --rm --init \
        --platform "$PLATFORM" \
        --user "$(id -u):$(id -g)" \
        --env HOME=/tmp \
        --volume "$MODEL_DIR:/work" \
        --workdir /work \
        "$IMAGE" \
        xvfb-run -a openscad \
        -D 'part="assembly"' \
        --imgsize=1200,1200 \
        --projection=p \
        --autocenter \
        --viewall \
        --camera=0,0,0,58,0,28,0 \
        -o "/work/$TEMP_NAME/assembly-preview.png" \
        "$SOURCE"
    [ -s "$output" ] || { echo "Empty preview PNG" >&2; exit 1; }
    file "$output" | grep -q 'PNG image data' || { echo "Invalid preview PNG" >&2; exit 1; }
}

publish_or_check() {
    generated=$1
    destination=$2
    if [ "$CHECK" -eq 1 ]; then
        if [ -f "$destination" ] && cmp -s "$generated" "$destination"; then
            echo "Up to date: ${destination#"$MODEL_DIR/"}"
        else
            echo "Stale: ${destination#"$MODEL_DIR/"}" >&2
            STALE=1
        fi
    else
        mkdir -p "$(dirname "$destination")"
        mv "$generated" "$destination"
        echo "Wrote ${destination#"$MODEL_DIR/"}"
    fi
}

if [ "$ONLY" = all ] || [ "$ONLY" = stl ]; then
    render_stl cradle pixel6-ipod-cradle.stl
    render_stl faceplate pixel6-ipod-faceplate.stl
    publish_or_check \
        "$TEMP_DIR/pixel6-ipod-cradle.stl" \
        "$MODEL_DIR/stl/pixel6-ipod-cradle.stl"
    publish_or_check \
        "$TEMP_DIR/pixel6-ipod-faceplate.stl" \
        "$MODEL_DIR/stl/pixel6-ipod-faceplate.stl"
fi

if [ "$ONLY" = all ] || [ "$ONLY" = preview ]; then
    render_preview
    publish_or_check \
        "$TEMP_DIR/assembly-preview.png" \
        "$MODEL_DIR/assembly-preview.png"
fi

if [ "$STALE" -ne 0 ]; then
    echo "Rendered outputs are stale. Run: ./render.sh" >&2
    exit 1
fi
