#!/bin/sh
# docs/media/shoku-demo.gif for the README: 480 px wide, 12 fps, one shared 64-colour palette
# (the video is monochrome, so that is plenty). Run after `npm run render`.
set -e
cd "$(dirname "$0")/../.."
FFMPEG="${FFMPEG:-ffmpeg}"
IN=docs/media/shoku-demo.mp4
OUT=docs/media/shoku-demo.gif
FILTERS="fps=12,scale=480:-1:flags=lanczos"
"$FFMPEG" -v error -y -i "$IN" -vf "$FILTERS,palettegen=max_colors=64:stats_mode=diff" /tmp/shoku-palette.png
"$FFMPEG" -v error -y -i "$IN" -i /tmp/shoku-palette.png -lavfi "$FILTERS [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" "$OUT"
ls -lh "$OUT"
