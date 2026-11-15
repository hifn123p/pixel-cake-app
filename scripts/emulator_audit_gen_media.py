"""生成模拟器走查用的测试图片（与 D:/Android/testmedia 同源，供 CI 在 runner 上生成）。
输出到 ./media_out/。无需 numpy，仅 Pillow。"""
import os
from PIL import Image, ImageDraw

os.makedirs("media_out", exist_ok=True)


def portrait():
    W, H = 1600, 2000
    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(30 + 80 * t), int(20 + 60 * t), int(60 + 50 * (1 - t))))
    cx, cy = W // 2, int(H * 0.38)
    rx, ry = int(W * 0.16), int(H * 0.15)
    for i in range(80):
        a = 1 - i / 80
        d.ellipse([cx - rx * a, cy - ry * a, cx + rx * a, cy + ry * a],
                  fill=(int(224 - 30 * a), int(180 - 20 * a), int(150 - 10 * a)))
    d.ellipse([cx - 60, cy - 40, cx - 20, cy - 10], fill=(60, 40, 35))
    d.ellipse([cx + 20, cy - 40, cx + 60, cy - 10], fill=(60, 40, 35))
    d.arc([cx - 50, cy + 20, cx + 50, cy + 80], 20, 160, fill=(150, 60, 60), width=8)
    d.pieslice([cx - int(rx * 1.3), cy - int(ry * 1.5), cx + int(rx * 1.3), cy + int(ry * 1.1)],
               150, 390, fill=(70, 45, 30))
    d.pieslice([cx - int(W * 0.28), cy + int(ry * 0.9),
                cx + int(W * 0.28), cy + int(ry * 0.9) + int(H * 0.5)], 180, 360, fill=(80, 110, 150))
    for i, c in enumerate([(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
                           (0, 255, 255), (255, 0, 255), (255, 255, 255), (0, 0, 0)]):
        x0 = 40 + (i % 4) * 90
        y0 = H - 260 + (i // 4) * 90
        d.rectangle([x0, y0, x0 + 80, y0 + 80], fill=c)
    for i in range(6):
        v = int(i * 51)
        d.rectangle([W - 580 + i * 88, H - 260, W - 580 + i * 88 + 80, H - 170], fill=(v, v, v))
    img.save("media_out/portrait_test.jpg", quality=92)


def landscape():
    img = Image.new("RGB", (2000, 1400), (0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(1400):
        t = y / 1400
        d.line([(0, y), (2000, y)], fill=(int(120 - 60 * t), int(160 - 80 * t), int(220 - 40 * t)))
    d.rectangle([0, 1000, 2000, 1400], fill=(90, 120, 60))
    d.rectangle([300, 600, 500, 1050], fill=(120, 100, 80))
    d.polygon([(400, 350), (250, 650), (550, 650)], fill=(40, 80, 40))
    d.ellipse([1400, 200, 1900, 700], fill=(240, 200, 60))
    img.save("media_out/landscape_test.jpg", quality=90)


def tiny():
    Image.new("RGB", (200, 120), (180, 120, 90)).save("media_out/tiny.jpg", quality=95)


portrait()
landscape()
tiny()
print("media_out:", os.listdir("media_out"))
