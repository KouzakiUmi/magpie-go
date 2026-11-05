"""Prepare approved original character art; do not redraw with smoothing filters."""
from pathlib import Path
from PIL import Image
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
MASTER = HERE / 'master'
MASTER.mkdir(exist_ok=True)
OUT = ROOT / 'app/src/main/assets/branding'
OUT.mkdir(parents=True, exist_ok=True)
RES = ROOT / 'app/src/main/res'
DRAW = RES / 'drawable-nodpi'
DRAW.mkdir(exist_ok=True)


def opaque_sprite(source, size=128):
    im = Image.open(source).convert('RGBA')
    box = im.getchannel('A').getbbox()
    im = im.crop(box)
    # The generated artwork is aligned to 15-16 pixel blocks. Reduce to a
    # deliberate sprite grid, with one palette sample per block.
    im.thumbnail((size, size), Image.Resampling.NEAREST)
    im = im.quantize(colors=28, method=Image.Quantize.FASTOCTREE).convert('RGBA')
    a = im.getchannel('A').point(lambda v: 255 if v >= 128 else 0)
    im.putalpha(a)
    return im


def contained(im, w, h, ratio=.84):
    dst = Image.new('RGBA', (w, h))
    sw, sh = im.size
    scale = min(w * ratio / sw, h * ratio / sh)
    s = im.resize((round(sw * scale), round(sh * scale)), Image.Resampling.NEAREST)
    dst.alpha_composite(s, ((w - s.width) // 2, (h - s.height) // 2))
    return dst

portrait_source = HERE / 'kxyai-02fb0b12.png'
(MASTER / portrait_source.name).write_bytes(portrait_source.read_bytes())

portrait = opaque_sprite(portrait_source)
portrait.save(MASTER / 'portrait-sprite.png')
contained(portrait, 512, 512, .62).save(DRAW / 'magpie_mascot.png')
contained(portrait, 512, 512).save(OUT / 'mascot.png')
contained(portrait, 512, 512).save(OUT / 'apple-touch-icon.png')

# Android's themed icon is a silhouette, not the full colored sprite.
mono = contained(portrait, 256, 256, .62)
mono.putalpha(mono.getchannel('A'))
mono.paste((0, 0, 0, 255), (0, 0, 256, 256), mono)
mono.save(RES / 'drawable' / 'ic_launcher_monochrome_art.png')

manifest = {
    'character_status': 'turnaround approved by user',
    'turnaround_sha256': '9fee94d4fe854f8503ea2217d2833191fd558f5fe843d49841fd97b69d6723c6',
    'pixel_style_reference_sha256': 'cfc64bb5acd216adf0e39775df065102d847efb7cea865f3cfcb481ca2750b10',
    'inputs': {portrait_source.name: hashlib.sha256(portrait_source.read_bytes()).hexdigest()},
    'outputs': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.is_file()},
}
(HERE / 'asset-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print('Prepared portrait, adaptive-icon safe zone and monochrome assets')
