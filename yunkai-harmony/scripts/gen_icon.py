#!/usr/bin/env python
# 云开 app 图标生成：晨光渐变底 + 金太阳从白云后探出（1024x1024 分层图标）
from PIL import Image, ImageDraw, ImageFilter
import math

SIZE = 1024
CX = SIZE // 2

# ---------- 背景：晨光垂直渐变（顶浅黄 -> 中金 -> 底橙） ----------
def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

top, mid, bot = (198, 228, 255), (255, 213, 94), (255, 168, 60)
bg = Image.new('RGB', (SIZE, SIZE))
d = ImageDraw.Draw(bg)
for y in range(SIZE):
    t = y / SIZE
    c = lerp(top, mid, t / 0.55) if t < 0.55 else lerp(mid, bot, (t - 0.55) / 0.45)
    d.line([(0, y), (SIZE, y)], fill=c)

# 背景加两个大柔光圆（右上淡白高光、左下淡橙），模拟晨光层次
glow = Image.new('L', (SIZE, SIZE), 0)
gd = ImageDraw.Draw(glow)
gd.ellipse([600, -150, 1250, 500], fill=70)
glow = glow.filter(ImageFilter.GaussianBlur(120)) if hasattr(ImageFilter, 'GaussianBlur') else glow
white_layer = Image.new('RGB', (SIZE, SIZE), (255, 250, 235))
bg = Image.composite(white_layer, bg, glow)
bg.save('AppScope/resources/base/media/background.png')

# ---------- 前景：金太阳 + 白云（透明底，内容居中 66% 安全区） ----------
fg = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(fg)

# 太阳（中心偏上，部分被云遮）
sun_c, sun_r = (CX, 400), 170
# 光芒：12 根圆帽粗线，从 r+45 到 r+120
for i in range(12):
    ang = i * math.pi / 6
    x1 = sun_c[0] + (sun_r + 48) * math.cos(ang)
    y1 = sun_c[1] + (sun_r + 48) * math.sin(ang)
    x2 = sun_c[0] + (sun_r + 118) * math.cos(ang)
    y2 = sun_c[1] + (sun_r + 118) * math.sin(ang)
    d.line([x1, y1, x2, y2], fill=(255, 196, 40, 255), width=30)
    d.ellipse([x2 - 15, y2 - 15, x2 + 15, y2 + 15], fill=(255, 196, 40, 255))
# 太阳本体 + 高光
d.ellipse([sun_c[0] - sun_r, sun_c[1] - sun_r, sun_c[0] + sun_r, sun_c[1] + sun_r],
          fill=(255, 201, 61, 255), outline=(255, 255, 255, 200), width=10)
d.ellipse([sun_c[0] - sun_r * 0.55, sun_c[1] - sun_r * 0.62,
           sun_c[0] + sun_r * 0.1, sun_c[1] - sun_r * 0.05], fill=(255, 226, 130, 255))

# 白云：椭圆并集（蓬松三团），带轻投影
shadow = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
sd = ImageDraw.Draw(shadow)
sd.ellipse([190, 610, 840, 830], fill=(120, 90, 20, 60))
shadow = shadow.filter(ImageFilter.GaussianBlur(18))
fg = Image.alpha_composite(fg, shadow)
d = ImageDraw.Draw(fg)
cloud = [(170, 560, 560, 800), (420, 500, 850, 800), (300, 620, 760, 830)]
for box in cloud:
    d.ellipse(box, fill=(255, 255, 255, 255))

fg.save('AppScope/resources/base/media/foreground.png')

# ---------- entry 启动图标：透明底太阳云同款（无背景渐变） ----------
fg.save('entry/src/main/resources/base/media/startIcon.png')

# ---------- 预览拼图 ----------
preview = Image.new('RGBA', (SIZE * 2 + 60, SIZE), (250, 246, 239, 255))
preview.paste(Image.open('AppScope/resources/base/media/background.png').convert('RGBA'), (0, 0))
comp = Image.alpha_composite(Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0)), fg)
preview.paste(comp, (SIZE + 60, 0), comp)
preview.save('/tmp/icon_preview.png')
print('icons generated')
