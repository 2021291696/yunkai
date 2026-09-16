#!/usr/bin/env python
# 云开 v2 图标：玻璃拟物——多彩渐变底 + 中央毛玻璃圆角片 + 白色对话气泡
from PIL import Image, ImageDraw, ImageFilter
import math

SIZE = 1024
CORNER = 210          # 图标整体圆角由桌面裁切，源图保持直角
GLASS = (210, 190, 815, 815)   # 玻璃片 box（左,上,右,下）≈ 中心 605px

def diag_gradient(size, c1, c2, c3):
    """135° 三段对角渐变：小图逐像素画再平滑放大（快且无接缝）"""
    s = 256
    small = Image.new('RGB', (s, s))
    px = small.load()
    for y in range(s):
        for x in range(s):
            t = (x + y) / (2 * (s - 1))
            if t < 0.5:
                c = tuple(int(c1[i] + (c2[i] - c1[i]) * (t * 2)) for i in range(3))
            else:
                c = tuple(int(c2[i] + (c3[i] - c2[i]) * ((t - 0.5) * 2)) for i in range(3))
            px[x, y] = c
    return small.resize((size, size), Image.BILINEAR)

def main():
    c1, c2, c3 = (46, 38, 112), (124, 58, 237), (236, 72, 153)  # 深靛→紫→品红
    base = diag_gradient(SIZE, c1, c2, c3)

    # 霓虹光斑：青（右上）、橙黄（左中）、粉（左下）
    def spot(img, box, color, blur):
        layer = Image.new('RGBA', img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        d.ellipse(box, fill=color)
        layer = layer.filter(ImageFilter.GaussianBlur(blur))
        return Image.alpha_composite(img.convert('RGBA'), layer)
    base = spot(base, (560, -160, 1180, 460), (34, 211, 238, 150), 110).convert('RGB')
    base = spot(base, (-220, 260, 340, 820), (251, 191, 36, 120), 120).convert('RGB')
    base = spot(base, (-160, 640, 420, 1240), (244, 114, 182, 130), 120).convert('RGB')
    base = base.convert('RGBA')

    # ===== 中央毛玻璃圆角片：采样底图局部 → 模糊 → 白罩 → 圆角贴回 =====
    x1, y1, x2, y2 = GLASS
    region = base.crop((x1, y1, x2, y2)).filter(ImageFilter.GaussianBlur(22))
    white = Image.new('RGBA', region.size, (255, 255, 255, 58))
    region = Image.alpha_composite(region.convert('RGBA'), white)
    mask = Image.new('L', region.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, region.size[0], region.size[1]], radius=150, fill=255)
    base.paste(region, (x1, y1), mask)

    overlay = Image.new('RGBA', base.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    # 蚀刻：白描边 + 内上高光线 + 外投影
    od.rounded_rectangle([x1, y1, x2, y2], radius=150, outline=(255, 255, 255, 140), width=6)
    od.rounded_rectangle([x1 + 8, y1 + 8, x2 - 8, y1 + 90], radius=80, fill=(255, 255, 255, 40))
    od.rounded_rectangle([x1 + 18, y1 + 18, x2 - 18, y2 - 18], radius=132,
                         outline=(255, 255, 255, 36), width=2)
    # 玻璃片外投影
    sh = Image.new('RGBA', base.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.rounded_rectangle([x1 + 10, y1 + 26, x2 + 10, y2 + 26], radius=150, fill=(20, 10, 60, 120))
    sh = sh.filter(ImageFilter.GaussianBlur(24))
    base = Image.alpha_composite(base, sh)
    base = Image.alpha_composite(base, overlay)

    # ===== 白色对话气泡 + 渐变三点 =====
    d = ImageDraw.Draw(base)
    # 气泡主体（圆角矩形）+ 左下尾巴
    d.rounded_rectangle([336, 372, 688, 596], radius=92, fill=(255, 255, 255, 242))
    d.polygon([(408, 580), (474, 580), (388, 676)], fill=(255, 255, 255, 242))
    # 三点（品红/紫/青 呼应底色）
    for cx, col in ((448, (124, 58, 237, 255)), (512, (236, 72, 153, 255)), (576, (34, 180, 220, 255))):
        d.ellipse([cx - 26, 448 - 26, cx + 26, 448 + 26], fill=col)

    # 裁圆角? 源图保持方形（桌面自动裁圆角）
    base.convert('RGB').save('AppScope/resources/base/media/app_icon.png')
    base.save('entry/src/main/resources/base/media/app_icon.png')
    base.save('entry/src/main/resources/base/media/startIcon.png')

    # 预览：缩小模拟桌面圆角
    prev = base.copy()
    m = Image.new('L', prev.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, SIZE, SIZE], radius=230, fill=255)
    out = Image.new('RGBA', prev.size, (240, 240, 245, 255))
    out.paste(prev, (0, 0), m)
    out.thumbnail((560, 560))
    out.save('/tmp/icon_v2_preview.png')
    print('v2 icon generated')

main()
