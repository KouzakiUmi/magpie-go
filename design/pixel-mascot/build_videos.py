"""Build the original 672x448 landscape demo with the approved magpie actor.

Each generated keyframe uses the corresponding upstream frame as its action and
composition target. The upstream person is not present in the generated art.
The webpage still owns all cards, placeholders, controls and the 27.5 s clock.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import math
import subprocess
import numpy as np

HERE = Path(__file__).resolve().parent
KEY = HERE / 'keyframes'
OUT = HERE.parents[1] / 'app/src/main/assets/branding'
W, H, FPS, DURATION = 672, 448, 24, 27.5
N = round(FPS * DURATION)
# Action anchors follow Magpie's own eight-step timeline.
ACTIONS = [
    (0.0, 'key-00.png'),
    (5.4, 'key-06.png'),
    (7.8, 'key-09.png'),
    (10.6, 'key-12.png'),
    (12.8, 'key-15.png'),
    (15.6, 'key-18.png'),
    (18.8, 'key-21.png'),
    (23.8, 'key-26.png'),
]
LIGHT = (255, 255, 255)
DARK = (28, 28, 30)


def load_key(path: Path, dark: bool) -> Image.Image:
    im = Image.open(path).convert('RGBA').resize((W, H), Image.Resampling.NEAREST)
    if dark:
        a = np.array(im).copy()
        y = np.arange(H)[:, None]
        r, g, b = a[..., 0], a[..., 1], a[..., 2]
        # Brown tabletop only; feather, notebook and cheek colors stay intact.
        table = ((y > H * .72) & (r.astype(int) > g.astype(int) + 18)
                 & (g.astype(int) > b.astype(int) + 12) & (r > 100))
        a[..., :3] = np.where(table[..., None],
                              (a[..., :3].astype(float) * np.array([.43, .42, .40])).astype(np.uint8),
                              a[..., :3])
        im = Image.fromarray(a)
    plate = Image.new('RGBA', (W, H), DARK if dark else LIGHT)
    plate.alpha_composite(im)
    return plate.convert('RGB')


def render(frames: list[Image.Image], t: float) -> Image.Image:
    index = 0
    for i, (at, _) in enumerate(ACTIONS):
        if t >= at:
            index = i
    current = frames[index]
    # A short eased cross-dissolve joins the action poses without moving or
    # cropping the desk. Long holds get subtle 1-pixel breathing motion.
    if index > 0:
        previous_at = ACTIONS[index - 1][0]
        at = ACTIONS[index][0]
        blend = min(1.0, max(0.0, (t - at) / .42))
        if blend < 1.0:
            blend = blend * blend * (3 - 2 * blend)
            current = Image.blend(frames[index - 1], current, blend)
        elif index == 4:  # raised-wing sending gesture
            phase = round(math.sin((t - at) * math.tau * 1.5))
            if phase:
                moved = Image.new('RGB', (W, H), current.getpixel((0, 0)))
                moved.paste(current, (phase, 0))
                current = moved
        elif t - at > .6 and index not in (2, 3, 6):
            bob = round(math.sin((t - previous_at) * math.tau / 3.2))
            if bob:
                moved = Image.new('RGB', (W, H), current.getpixel((0, 0)))
                moved.paste(current, (0, bob))
                current = moved
    return current


def main() -> None:
    for theme, dark in [('light', False), ('dark', True)]:
        frames = [load_key(KEY / name, dark) for _, name in ACTIONS]
        frames[0].save(OUT / f'xq_{theme}.jpg', quality=94, optimize=True)
        out = OUT / f'xq_{theme}.webm'
        cmd = [
            'ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
            '-f', 'rawvideo', '-pixel_format', 'rgb24', '-video_size', f'{W}x{H}',
            '-framerate', str(FPS), '-i', '-', '-an', '-c:v', 'libvpx-vp9',
            '-pix_fmt', 'yuv420p', '-b:v', '0', '-crf', '31', '-row-mt', '1',
            '-deadline', 'good', '-cpu-used', '2', '-g', '48', '-t', str(DURATION), str(out),
        ]
        process = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        try:
            for i in range(N):
                process.stdin.write(render(frames, i / FPS).tobytes())
        finally:
            process.stdin.close()
        code = process.wait()
        if code:
            raise RuntimeError(f'ffmpeg {theme} failed: {code}')
        print(out, out.stat().st_size)

    # A contact sheet for human review of the actual composed landscape frames.
    light_frames = [load_key(KEY / name, False) for _, name in ACTIONS]
    sheet = Image.new('RGB', (W * 2, (H + 26) * 4), 'white')
    draw = ImageDraw.Draw(sheet)
    for i, ((at, _), frame) in enumerate(zip(ACTIONS, light_frames)):
        x, y = (i % 2) * W, (i // 2) * (H + 26)
        draw.text((x + 8, y + 7), f'{at:.1f}s', fill='black')
        sheet.paste(frame, (x, y + 26))
    sheet.save(HERE / 'master' / 'landscape-contact.png')


if __name__ == '__main__':
    main()
