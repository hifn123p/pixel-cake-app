"""模拟器 UI 全链路走查（黑盒，坐标来自 uiautomator dump 的文本/语义定位）。

产出 audit-art/shots/*.png + audit-art/xml/*.xml。每步失败不中断（走查要尽量走完全程），
但每个关键动作的结果打进 stdout 供 workflow 日志审查。

定位策略：Compose 控件在 uiautomator dump 里以 text / content-desc 出现
（ParamSlider 已并 contentDescription=label；图标按钮都有 content-desc）。
找不到目标时记录 dump 片段，不盲点。
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SHOTS = "audit-art/shots"
XMLS = "audit-art/xml"
os.makedirs(SHOTS, exist_ok=True)
os.makedirs(XMLS, exist_ok=True)

ADB = "adb"
results = []


def run(cmd, timeout=30):
    return subprocess.run([ADB] + cmd, capture_output=True, text=True, timeout=timeout)


def dump(name):
    """uiautomator dump 到本地文件并解析；返回 (root, xml_text)。"""
    for attempt in range(3):
        r = run(["shell", "uiautomator", "dump", "/sdcard/ui.xml"], timeout=30)
        if "dumped" not in (r.stdout + r.stderr).lower():
            time.sleep(1.5)
            continue
        pull = run(["pull", "/sdcard/ui.xml", f"{XMLS}/{name}.xml"])
        if pull.returncode == 0:
            try:
                return ET.parse(f"{XMLS}/{name}.xml").getroot(), open(f"{XMLS}/{name}.xml", encoding="utf-8").read()
            except ET.ParseError:
                time.sleep(1.5)
        time.sleep(1.5)
    return None, ""


def shot(name, delay=0.8):
    time.sleep(delay)
    # screencap 是二进制 —— 必须 capture_output 原始字节（text=True 会坏 PNG）
    raw = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    if raw.stdout and len(raw.stdout) > 1000:
        with open(f"{SHOTS}/{name}.png", "wb") as f:
            f.write(raw.stdout)
        results.append(f"SHOT {name} ok ({len(raw.stdout)} bytes)")
        return True
    results.append(f"SHOT {name} FAIL")
    return False


def bounds_center(node):
    b = node.get("bounds", "[0,0][0,0]")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_nodes(root, attr, value):
    if root is None:
        return []
    return [n for n in root.iter("node") if n.get(attr) == value]


def find_contains(root, attr, value):
    if root is None:
        return []
    return [n for n in root.iter("node") if n.get(attr) and value in n.get(attr)]


def tap(attr, value, name, wait=1.5, contains=False, allow_missing=False):
    """按属性定位可点节点并点其中心。返回 True/False。"""
    root, _ = dump(name)
    nodes = find_contains(root, attr, value) if contains else find_nodes(root, attr, value)
    if not nodes:
        results.append(f"TAP {name}: NOT FOUND {attr}={value}")
        if not allow_missing:
            print(f"!! {name}: cannot locate {attr}={value}")
        return False
    # 取面积最小（最内层）的命中节点
    def area(n):
        m = re.findall(r"\d+", n.get("bounds", ""))
        if len(m) != 4:
            return 1 << 60
        return (int(m[2]) - int(m[0])) * (int(m[3]) - int(m[1]))
    for n in sorted(nodes, key=area):
        c = bounds_center(n)
        if c:
            run(["shell", "input", "tap", str(c[0]), str(c[1])])
            results.append(f"TAP {name}: {attr}={value} at {c}")
            time.sleep(wait)
            return True
    results.append(f"TAP {name}: zero-size bounds")
    return False


def swipe(x1, y1, x2, y2, ms=400, wait=1.0):
    run(["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)])
    time.sleep(wait)


def screen_size():
    r = run(["shell", "wm", "size"])
    m = re.search(r"(\d+)x(\d+)", r.stdout)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


W, H = screen_size()
print(f"screen {W}x{H}")


def back():
    run(["shell", "input", "keyevent", "4"])
    time.sleep(1.2)


def wake_and_start_app():
    run(["shell", "input", "keyevent", "224"])  # wake
    run(["shell", "am", "start", "-n", "com.hifn.pixelcake/.MainActivity"])
    time.sleep(4.0)


# ———— A. 首页 ————
wake_and_start_app()
root, txt = dump("home")
shot("01_home")

# B. 相册导入（Photo Picker）
tap("text", "相册", "02_tap_gallery", wait=3.0)
root, txt = dump("picker")
shot("03_photo_picker")
# Photo Picker 里图片以 content-desc 含文件名或纯图片节点出现：优先文本/描述含 portrait_test
tapped = tap("content-desc", "portrait_test", "04_pick_image", wait=1.0, contains=True, allow_missing=True)
if not tapped:
    tapped = tap("text", "portrait_test", "04b_pick_image_text", wait=1.0, contains=True, allow_missing=True)
if not tapped:
    # 兜底：点图集第一格（常见位置 —— 网格第一项通常在标题下方）
    root, _ = dump("picker_retry")
    imgs = [n for n in (root.iter("node") if root else []) if n.get("class", "").endswith("ImageView")]
    if imgs:
        c = bounds_center(imgs[0])
        if c:
            run(["shell", "input", "tap", str(c[0]), str(c[1])])
            results.append(f"picker fallback tap ImageView at {c}")
    else:
        results.append("PICK: no fallback node")
    time.sleep(2.0)
# 选定后编辑器渲染需要时间（解码 + 首帧 + ML 探测）
time.sleep(6.0)
root, txt = dump("editor")
shot("05_editor_first_frame")

# ———— C. Rail 六工具 + 各 deck ————
for i, rail in enumerate(["明暗", "颜色", "曲线", "细节", "预设", "人像"]):
    ok = tap("text", rail, f"10_rail_{rail}", wait=2.0)
    shot(f"11_deck_{rail}", delay=1.0)

# ———— D. 双通道：照片横拖调参（在明暗页，横拖 = 曝光）————
tap("text", "明暗", "20_rail_light", wait=1.5)
swipe(int(W * 0.30), int(H * 0.35), int(W * 0.65), int(H * 0.35), ms=600, wait=1.0)
shot("21_dual_channel_drag", delay=0.3)

# ———— E. 曲线拖动（切到曲线，拖中锚点）————
tap("text", "曲线", "22_rail_curve", wait=2.0)
shot("23_curve_deck")

# ———— F. 预设应用 + 强度环 ————
tap("text", "预设", "24_rail_preset", wait=2.0)
root, _ = dump("preset_deck")
shot("25_presets")
# 点第一个预设卡（找 class ImageView 的缩略图）
if root is not None:
    imgs = [n for n in root.iter("node") if n.get("class", "").endswith("ImageView")]
    if imgs:
        c = bounds_center(imgs[0])
        if c:
            run(["shell", "input", "tap", str(c[0]), str(c[1])])
            results.append(f"preset tap at {c}")
            time.sleep(2.0)
    shot("26_preset_applied")

# ———— G. 顶栏动作：撤销 / 旋转 / 自动增强 ————
tap("content-desc", "撤销", "30_undo", wait=1.0, contains=True, allow_missing=True)
tap("content-desc", "旋转", "31_rotate", wait=2.5, contains=True, allow_missing=True)
shot("32_after_rotate")
tap("content-desc", "撤销", "33_undo_rotate", wait=2.5, contains=True, allow_missing=True)
tap("content-desc", "自动增强", "34_auto_enhance", wait=3.0, contains=True, allow_missing=True)
shot("35_auto_enhanced")

# ———— H. 缩放：双击放大 + 复位 ————
run(["shell", "input", "tap", str(int(W * 0.45)), str(int(H * 0.30))])
time.sleep(0.15)
run(["shell", "input", "tap", str(int(W * 0.45)), str(int(H * 0.30))])
time.sleep(1.5)
shot("40_double_tap_zoom")
# 复位胶囊
tap("text", "复位", "41_zoom_reset", wait=1.0, contains=True, allow_missing=True)

# ———— I. 导出全链路 ————
tap("text", "导出", "50_open_export", wait=2.0)
shot("51_export_sheet")
# Sheet 里的主按钮「导出」
tap("text", "导出", "52_export_go", wait=1.0, allow_missing=True)
# 等 JPEG 导出完成（含回执出现）
time.sleep(6.0)
root, _ = dump("receipt")
shot("53_export_receipt")
tap("text", "完成", "54_receipt_done", wait=1.5, allow_missing=True)

# ———— J. 退出编辑器 → 首页 ————
tap("content-desc", "返回", "60_back_to_home", wait=2.5, contains=True, allow_missing=True)
root, _ = dump("home_after")
shot("61_home_after_back")

# ———— K. 设置页走查 ————
tap("text", "设置", "62_open_settings", wait=2.0)
shot("63_settings")
tap("text", "查看", "64_model_center", wait=2.0, allow_missing=True)
shot("65_model_center")
back()
shot("66_settings_back")
# 恢复默认（确认弹窗出现 → 取消）
tap("text", "恢复", "67_reset_tap", wait=1.5, allow_missing=True)
shot("68_reset_confirm")
tap("text", "取消", "69_reset_cancel", wait=1.0, allow_missing=True)

# ———— L. 边界：tiny.jpg ————
tap("text", "调色台", "70_back_darkroom", wait=1.5, contains=True, allow_missing=True)
tap("text", "相册", "71_open_gallery2", wait=3.0, allow_missing=True)
tap("content-desc", "tiny", "72_pick_tiny", wait=1.0, contains=True, allow_missing=True)
time.sleep(4.0)
shot("73_tiny_editor")
tap("content-desc", "返回", "74_back2", wait=2.0, contains=True, allow_missing=True)

print("\n=== WALKTHROUGH RESULT ===")
for r in results:
    print(r)
print("=== END ===")
