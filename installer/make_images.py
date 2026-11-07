"""Makes the installer pictures. Put your own art in installer/images/ (PNG) and it is used instead:
   installer/images/background.png  -> big picture on top of the installer window (about 1000 x 300)
Nothing there? A dark red picture with the launcher logo is drawn."""
import os
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
logo_path = os.path.join(here, "..", "src", "main", "resources", "vt", "logo.png")
bg_path = os.path.join(here, "images", "background.png")


def font(size, bold=False):
    for name in (("segoeuib.ttf" if bold else "segoeui.ttf"), "arialbd.ttf" if bold else "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            pass
    return ImageFont.load_default()


def gradient(w, h, top=(122, 10, 20), bottom=(14, 14, 18)):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def cover(img, w, h):
    """Scale to fill w x h and crop the middle."""
    r = max(w / img.width, h / img.height)
    img = img.resize((max(w, round(img.width * r)), max(h, round(img.height * r))), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


logo = Image.open(logo_path).convert("RGBA") if os.path.exists(logo_path) else None

# --- art on top of the installer (1000 x 300) ---
if os.path.exists(bg_path):
    art = cover(Image.open(bg_path).convert("RGB"), 1000, 300)
    print("using", bg_path)
else:
    art = gradient(1000, 300)
    if logo:
        lg = logo.resize((150, 150), Image.LANCZOS)
        art.paste(lg, (60, 75), lg)
    d = ImageDraw.Draw(art)
    d.text((240, 105), "VT Launcher", font=font(64, True), fill=(255, 255, 255))
    d.text((244, 190), "Minecraft Java launcher", font=font(26), fill=(230, 200, 205))
art.save(os.path.join(here, "art.bmp"), "BMP")

# --- standard wizard pictures (finish page, header) ---
big = gradient(164, 314)
if logo:
    lg = logo.resize((108, 108), Image.LANCZOS)
    big.paste(lg, (28, 98), lg)
big.save(os.path.join(here, "wizard-large.bmp"), "BMP")
small = Image.new("RGB", (55, 55), (14, 14, 18))
if logo:
    lg2 = logo.resize((44, 44), Image.LANCZOS)
    small.paste(lg2, (5, 5), lg2)
small.save(os.path.join(here, "wizard-small.bmp"), "BMP")
print("installer pictures ready")
