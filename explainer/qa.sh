#!/usr/bin/env bash
# QA pack for out/gekko-explainer.mp4: contact sheet, poster, waveform, probe.
# Usage: ./qa.sh [video] [seconds-per-tile]
# Tiles run left to right, top to bottom: tile n shows second n*STEP (this ffmpeg has no drawtext).
set -euo pipefail
cd "$(dirname "$0")"
V="${1:-out/gekko-explainer.mp4}"
STEP="${2:-2}"
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$V")
N=$(python3 -c "import math;print(math.ceil($DUR/$STEP))")
COLS=6
ROWS=$(python3 -c "import math;print(math.ceil($N/$COLS))")
ffmpeg -loglevel error -y -i "$V" \
  -vf "fps=1/$STEP,scale=384:-1,tile=${COLS}x${ROWS}:padding=8:margin=8:color=0xdddddd" \
  -frames:v 1 out/contact-sheet.png
ffmpeg -loglevel error -y -i "$V" -frames:v 1 out/poster.png
ffmpeg -loglevel error -y -i "$V" -filter_complex "showwavespic=s=1600x240:colors=0x2F6FE4" -frames:v 1 out/waveform.png
echo "--- probe"
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,r_frame_rate,pix_fmt -of csv=p=0 "$V"
ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,sample_rate,channels -of csv=p=0 "$V"
echo "duration: $DUR s"
echo "size: $(du -h "$V" | cut -f1)"
echo "--- audio levels"
ffmpeg -hide_banner -i "$V" -af volumedetect -vn -f null - 2>&1 | grep -E "mean_volume|max_volume"
echo "--- outputs: out/contact-sheet.png out/poster.png out/waveform.png"
