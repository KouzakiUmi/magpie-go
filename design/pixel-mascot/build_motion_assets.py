"""Prepare the side-view hop sprite and the demo's full-head thumbnail."""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DRAW = ROOT / 'app/src/main/res/drawable-nodpi'
ASSETS = ROOT / 'app/src/main/assets/branding'


def sprite(im: Image.Image, grid: int, colors: int = 32) -> Image.Image:
    im = im.convert('RGBA')
    im = im.crop(im.getchannel('A').getbbox())
    im.thumbnail((grid, grid), Image.Resampling.NEAREST)
    im = im.quantize(colors=colors, method=Image.Quantize.FASTOCTREE).convert('RGBA')
    im.putalpha(im.getchannel('A').point(lambda value: 255 if value >= 128 else 0))
    return im


def contain(im: Image.Image, size: int, margin: float = .94) -> Image.Image:
    out = Image.new('RGBA', (size, size))
    ratio = min(size * margin / im.width, size * margin / im.height)
    scaled = im.resize((round(im.width * ratio), round(im.height * ratio)), Image.Resampling.NEAREST)
    out.alpha_composite(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2))
    return out


jump = sprite(Image.open(HERE / 'jump-magpie.png'), 192)
jump.save(HERE / 'master' / 'jump-sprite.png')
contain(jump, 512, .96).save(DRAW / 'magpie_jump.png')

portrait = Image.open(HERE / 'kxyai-02fb0b12.png').convert('RGBA')
# Full crest and face, without the wing-dominated lower half of the portrait.
head = portrait.crop((170, 25, 935, 790))
head = sprite(head, 128)
head.save(HERE / 'master' / 'head-sprite.png')
contain(head, 256, .98).save(ASSETS / 'mascot-head.png')
print('Prepared side-view jump sprite and full-head demo thumbnail')
