#!/usr/bin/env python3
"""生成应用图标。

自适应图标（Android 8.0+）用矢量 XML 描述，这个脚本只负责两件事：
1. 渲染预览图，方便在提交前看效果；
2. 生成旧版启动器需要的位图，几何形状与矢量版保持一致。

画布沿用自适应图标的 108x108 坐标系，其中中央 72x72 是一定会显示的区域。
"""

import os
from PIL import Image, ImageDraw

BASE = 108          # 自适应图标画布尺寸
VISIBLE = 72        # 遮罩后保证可见的中央区域
SS = 8              # 超采样倍率，用来抹掉锯齿

GRADIENT_START = (32, 43, 138)    # 深靛蓝
GRADIENT_END = (0, 168, 150)      # 青绿
WHITE = (255, 255, 255, 255)

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
LEGACY_DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def _lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _draw_headphones(draw, scale):
    """头梁是半圆弧，两个耳罩是圆角胶囊。

    弧的外缘（bbox 边界）与耳罩外缘都落在 x=31 / x=77 上，接缝处才不会出现台阶；
    耳罩顶端比弧的端点高 6，让弧的平端头整个藏进耳罩里，避免出现缺口。
    """
    draw.arc(
        [31 * scale, 31 * scale, 77 * scale, 77 * scale],
        start=180, end=360, fill=WHITE, width=6 * scale,
    )
    draw.rounded_rectangle(
        [31 * scale, 48 * scale, 43 * scale, 76 * scale], radius=6 * scale, fill=WHITE
    )
    draw.rounded_rectangle(
        [65 * scale, 48 * scale, 77 * scale, 76 * scale], radius=6 * scale, fill=WHITE
    )


def render_canvas():
    """渲染完整的 108x108 画布（含背景），返回超采样后的大图。"""
    n = BASE * SS
    img = Image.new("RGBA", (n, n))
    draw = ImageDraw.Draw(img)
    for i in range(2 * n):
        draw.line([(i, 0), (0, i)], fill=_lerp(GRADIENT_START, GRADIENT_END, i / (2 * n - 1)) + (255,))
    _draw_headphones(draw, SS)
    return img


def render_foreground():
    """只有耳机、背景透明的前景层，用于预览单色图标效果。"""
    n = BASE * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    _draw_headphones(ImageDraw.Draw(img), SS)
    return img


def _rounded_mask(size, radius_ratio):
    mask = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * radius_ratio), fill=255
    )
    return mask.resize((size, size), Image.LANCZOS)


def _circle_mask(size):
    mask = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def visible_area(canvas, size):
    """裁出遮罩下真正会显示的中央区域并缩放到目标尺寸。"""
    inset = (BASE - VISIBLE) // 2 * SS
    cropped = canvas.crop((inset, inset, canvas.width - inset, canvas.height - inset))
    return cropped.resize((size, size), Image.LANCZOS)


def write_legacy_icons():
    canvas = render_canvas()
    for density, size in LEGACY_DENSITIES.items():
        icon = visible_area(canvas, size)
        square = icon.copy()
        square.putalpha(_rounded_mask(size, 0.22))
        round_icon = icon.copy()
        round_icon.putalpha(_circle_mask(size))

        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        square.save(os.path.join(folder, "ic_launcher.png"))
        round_icon.save(os.path.join(folder, "ic_launcher_round.png"))
        print(f"mipmap-{density}: {size}x{size}")


def write_preview(path, size=320):
    canvas = render_canvas()
    icon = visible_area(canvas, size)

    squircle = icon.copy()
    squircle.putalpha(_rounded_mask(size, 0.22))
    circle = icon.copy()
    circle.putalpha(_circle_mask(size))

    mono = visible_area(render_foreground(), size)
    mono_bg = Image.new("RGBA", (size, size), (210, 214, 220, 255))
    mono_bg.alpha_composite(mono)
    mono_bg.putalpha(_rounded_mask(size, 0.22))

    gap = 32
    sheet = Image.new("RGBA", (size * 3 + gap * 4, size + gap * 2), (245, 245, 247, 255))
    for index, image in enumerate((squircle, circle, mono_bg)):
        sheet.alpha_composite(image, (gap + index * (size + gap), gap))
    sheet.convert("RGB").save(path)
    print(f"预览已生成：{path}")


if __name__ == "__main__":
    import sys

    if "--legacy" in sys.argv:
        write_legacy_icons()
    else:
        write_preview(sys.argv[1] if len(sys.argv) > 1 else "icon_preview.png")
