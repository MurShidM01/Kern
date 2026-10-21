"""Optimize the demo GIF for README use with ffmpeg.

The source is 18 MB / 2786 frames at 400x892 with a ~93s loop. GitHub
renders a README GIF inline, so the whole file is downloaded before it
plays and a large payload makes the page sluggish.

ffmpeg's two-pass palette workflow (palettegen then paletteuse) is what
produces a small file that still looks clean: a single shared palette
plus real frame-to-frame differencing, which Pillow's GIF writer does
not do.
"""
import os
import subprocess
import sys
import tempfile

import imageio_ffmpeg

FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()


def run(args):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def optimize(src, out, fps=15, width=400, colors=128, max_seconds=None):
    duration = ["-t", str(max_seconds)] if max_seconds else []

    with tempfile.TemporaryDirectory() as tmp:
        pal = os.path.join(tmp, "palette.png")
        # Pass 1: build a global palette from the whole clip.
        run([
            "-i", src,
            *duration,
            "-vf", f"fps={fps},scale={width}:-1:flags=lanczos,palettegen=stats_mode=diff",
            pal,
        ])
        # Pass 2: apply the palette and enable frame differencing.
        run([
            "-i", src,
            "-i", pal,
            *duration,
            "-lavfi", f"fps={fps},scale={width}:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3",
            "-loop", "0",
            out,
        ])
    return out, os.path.getsize(out)


if __name__ == "__main__":
    src = sys.argv[1]
    out = sys.argv[2]
    fps = int(sys.argv[3]) if len(sys.argv) > 3 else 15
    width = int(sys.argv[4]) if len(sys.argv) > 4 else 400
    path, size = optimize(src, out, fps, width)
    print(f"{path}\n  size={size / 1024 / 1024:.2f} MB")
