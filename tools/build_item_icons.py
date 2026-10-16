"""把图像生成的 2x2 战甲概念图稳定转换为 Minecraft 32x32 物品图标。"""

from pathlib import Path
from PIL import Image, ImageDraw, ImageEnhance


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "art" / "item-icons"
OUTPUT_DIR = ROOT / "src" / "main" / "resources" / "assets" / "flight_armor" / "textures" / "item" / "armor"
ICON_SIZE = 32
CONTENT_SIZE = 29
VARIANTS = ("vanguard", "swift", "sentinel")
PARTS = (
    ("helmet", (0.0, 0.0, 0.5, 0.5)),
    ("chestplate", (0.5, 0.0, 1.0, 0.5)),
    ("leggings", (0.0, 0.5, 0.5, 1.0)),
    ("boots", (0.5, 0.5, 1.0, 1.0)),
)


def trim(image: Image.Image) -> Image.Image:
    alpha = image.getchannel("A")
    mask = alpha.point(lambda value: 255 if value >= 20 else 0)
    bounds = mask.getbbox()
    if bounds is None:
        raise ValueError("源图没有可见内容")
    return image.crop(bounds)


def pixel_icon(image: Image.Image, content_size: int = CONTENT_SIZE) -> Image.Image:
    image = trim(image.convert("RGBA"))
    image.thumbnail((content_size, content_size), Image.Resampling.LANCZOS)
    image = ImageEnhance.Sharpness(image).enhance(1.45)
    alpha = image.getchannel("A").point(lambda value: 0 if value < 40 else 255)
    rgb = image.convert("RGB").quantize(colors=56, method=Image.Quantize.MEDIANCUT).convert("RGB")
    rgb.putalpha(alpha)
    canvas = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(rgb, ((ICON_SIZE - rgb.width) // 2, (ICON_SIZE - rgb.height) // 2))
    return canvas


def crop_sheet(variant: str) -> None:
    source = Image.open(SOURCE_DIR / f"{variant}-alpha.png").convert("RGBA")
    width, height = source.size
    for part, relative in PARTS:
        box = tuple(round(value * size) for value, size in zip(
            relative, (width, height, width, height), strict=True))
        pixel_icon(source.crop(box)).save(OUTPUT_DIR / f"{variant}_{part}.png", optimize=True)


def build_core() -> None:
    source = Image.open(SOURCE_DIR / "navigation-core-alpha.png").convert("RGBA")
    pixel_icon(source, 30).save(OUTPUT_DIR / "navigation_core.png", optimize=True)


def validate() -> None:
    expected = [OUTPUT_DIR / f"{variant}_{part}.png" for variant in VARIANTS for part, _ in PARTS]
    expected.append(OUTPUT_DIR / "navigation_core.png")
    for path in expected:
        image = Image.open(path)
        if image.mode != "RGBA" or image.size != (ICON_SIZE, ICON_SIZE):
            raise ValueError(f"{path.name}: 必须为 32x32 RGBA")
        alpha = image.getchannel("A")
        coverage = sum(value > 0 for value in alpha.getdata()) / (ICON_SIZE * ICON_SIZE)
        if not 0.10 <= coverage <= 0.90:
            raise ValueError(f"{path.name}: 可见像素占比异常 {coverage:.1%}")
        if any(alpha.getpixel(point) != 0 for point in ((0, 0), (31, 0), (0, 31), (31, 31))):
            raise ValueError(f"{path.name}: 四角必须透明")
        print(f"{path.name}: 32x32 RGBA, coverage={coverage:.1%}")


def build_preview() -> None:
    cell = 72
    canvas = Image.new("RGBA", (cell * 4, cell * 4), (8, 17, 24, 255))
    draw = ImageDraw.Draw(canvas)
    accents = {"vanguard": (54, 244, 255, 255), "swift": (209, 76, 255, 255),
               "sentinel": (255, 190, 59, 255)}
    for row, variant in enumerate(VARIANTS):
        for column, (part, _) in enumerate(PARTS):
            x, y = column * cell, row * cell
            draw.rectangle((x + 3, y + 3, x + 68, y + 68),
                           fill=(12, 29, 39, 255), outline=accents[variant], width=2)
            icon = Image.open(OUTPUT_DIR / f"{variant}_{part}.png").convert("RGBA")
            canvas.alpha_composite(icon.resize((64, 64), Image.Resampling.NEAREST), (x + 4, y + 4))
    draw.rectangle((3, cell * 3 + 3, cell * 4 - 4, cell * 4 - 4),
                   fill=(12, 29, 39, 255), outline=(101, 238, 255, 255), width=2)
    core = Image.open(OUTPUT_DIR / "navigation_core.png").convert("RGBA")
    canvas.alpha_composite(core.resize((64, 64), Image.Resampling.NEAREST),
                           ((canvas.width - 64) // 2, cell * 3 + 4))
    canvas.save(SOURCE_DIR / "final-preview.png", optimize=True)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for variant in VARIANTS:
        crop_sheet(variant)
    build_core()
    validate()
    build_preview()


if __name__ == "__main__":
    main()

