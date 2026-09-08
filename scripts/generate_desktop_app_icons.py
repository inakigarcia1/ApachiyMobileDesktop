"""Regenerate desktop window/taskbar/installer icons from the Apachiy master asset."""

from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ICONS_DIR = ROOT / "composeApp/src/desktopMain/resources/icons"
COMPOSE_DRAWABLE = ROOT / "composeApp/src/commonMain/composeResources/drawable"

MASTER_NAME = "apachiy-app-icon-transparent.png"
VARIANT_KEYS = [
    "original",
    "arctic_blue",
    "emerald",
    "rose_gold",
    "copper",
    "graphite",
]

ACCENT_COLORS = {
    "original": (184, 160, 106),
    "arctic_blue": (96, 196, 255),
    "emerald": (72, 210, 140),
    "rose_gold": (232, 150, 170),
    "copper": (214, 140, 88),
    "graphite": (168, 176, 188),
}


def load_master() -> Image.Image:
    legacy = ICONS_DIR / "nuvio-app-icon-transparent.png"
    current = ICONS_DIR / MASTER_NAME
    source = current if current.exists() else legacy
    if not source.exists():
        raise FileNotFoundError(f"Missing Apachiy master icon at {source}")
    return Image.open(source).convert("RGBA")


def recolor_accent(image: Image.Image, accent_rgb: tuple[int, int, int]) -> Image.Image:
    base_accent = ACCENT_COLORS["original"]
    rgba = image.copy()
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            distance = sum((channel - base) ** 2 for channel, base in zip((r, g, b), base_accent))
            if distance > 55 * 55:
                continue
            luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            pixels[x, y] = (
                min(255, round(accent_rgb[0] * luminance + 40)),
                min(255, round(accent_rgb[1] * luminance + 40)),
                min(255, round(accent_rgb[2] * luminance + 40)),
                a,
            )
    return rgba


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def save_ico(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    image.save(path, format="ICO", sizes=sizes)


def save_icns(image: Image.Image, path: Path) -> None:
    try:
        import icnsutil as icns
    except ImportError:
        existing = ICONS_DIR / "nuvio-app-icon-transparent.icns"
        if existing.exists():
            path.write_bytes(existing.read_bytes())
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    img = icns.Image()
    for size in (16, 32, 64, 128, 256, 512, 1024):
        resized = image.resize((size, size), Image.Resampling.LANCZOS)
        img.add(resized)
    img.write(path)


def on_black(image: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", image.size, (0, 0, 0, 255))
    canvas.alpha_composite(image)
    return canvas.convert("RGB")


def generate() -> None:
    master = load_master()
    save_png(master, ICONS_DIR / MASTER_NAME)

    for key in VARIANT_KEYS:
        variant = recolor_accent(master, ACCENT_COLORS[key]) if key != "original" else master
        desktop_base = ICONS_DIR / f"app-icon-{key}-transparent"
        save_png(variant, desktop_base.with_suffix(".png"))
        save_ico(variant, desktop_base.with_suffix(".ico"))
        save_icns(variant, desktop_base.with_suffix(".icns"))

        compose_base = COMPOSE_DRAWABLE / f"app_icon_{key}"
        save_png(on_black(variant), compose_base.with_suffix(".png"))
        save_png(variant, compose_base.with_name(f"{compose_base.name}_transparent.png"))

    installer_base = ICONS_DIR / "apachiy-app-icon-transparent"
    save_png(master, installer_base.with_suffix(".png"))
    save_ico(master, installer_base.with_suffix(".ico"))
    save_icns(master, installer_base.with_suffix(".icns"))


if __name__ == "__main__":
    generate()
