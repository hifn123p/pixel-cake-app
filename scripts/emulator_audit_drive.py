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


def tap(attr, value, name, wait=1.5, contains=False, allow_missing=False, attempts=1):
    """按属性定位可点节点并点其中心。返回 True/False。

    ⚠️ uiautomator dump 在动画期间会返回**竞态快照**（实测：回执入场动画里
    「完成」节点整个缺失 ⇒ tap 静默跳过，回执卡永久停留）。attempts > 1 时
    找不到就隔 2s 重新 dump 再找 —— 关键路径（完成/返回/导出主按钮）用大 attempts。
    """
    nodes = []
    for attempt in range(attempts):
        root, _ = dump(name if attempt == 0 else f"{name}_retry{attempt}")
        nodes = find_contains(root, attr, value) if contains else find_nodes(root, attr, value)
        if nodes:
            break
        time.sleep(2.0)
    if not nodes:
        results.append(f"TAP {name}: NOT FOUND {attr}={value} (attempts={attempts})")
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


PKG = "com.hifn.pixelcake.debug"  # debug 变体的 applicationId（suffix=.debug）


def foreground():
    r = run(["shell", "dumpsys", "window", "window", "|", "grep", "-E", "mCurrentFocus|mFocusedApp"])
    # dumpsys 的 grep 由设备端 shell 执行 —— 直接管道不走本地 grep
    r2 = run(["shell", "dumpsys", "window"])
    for line in r2.stdout.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            return line.strip()
    return ""


def wake_and_start_app():
    run(["shell", "input", "keyevent", "224"])  # wake
    # ⚠️ 组件名 = 「applicationId/真实类路径」：debug 变体 applicationId 带 .debug 后缀，
    # 但 Activity 类仍在 com.hifn.pixelcake 包下 —— 首轮走查用错包名导致整轮在桌面游荡。
    run(["shell", "am", "start", "-n", f"{PKG}/com.hifn.pixelcake.MainActivity"])
    time.sleep(4.0)
    # 前台校验：不在 App 内就用 monkey 兜底再试一次；仍不在则记录（后续步骤会全部 NOT FOUND）
    focus = foreground()
    if PKG not in focus:
        results.append(f"am start miss (focus={focus!r}) -> monkey retry")
        run(["shell", "monkey", "-p", PKG, "1"])
        time.sleep(4.0)
        focus = foreground()
    results.append(f"foreground={focus!r}")
    print("foreground:", focus)


# ———— A. 首页 ————
wake_and_start_app()
root, txt = dump("home")
shot("01_home")

# B. 导入（主路：SAF「文件 ARW」—— 直接文件系统，绕开 MediaStore 惰性扫描；
#     后备：Photo Picker）。SAF 的 */* 接受 jpg：openInEditor 按 mime/后缀路由解码。
def import_via_saf(tag):
    """打开「文件 ARW」→ Recent 直接点目标文件；不在 Recent 就走根目录导航。"""
    if not tap("text", "文件 ARW", f"{tag}_saf_open", wait=3.0):
        return False
    root, _ = dump(f"{tag}_saf")
    node = find_contains(root, "text", "portrait_test.jpg")
    if not node:
        # Recent 没有 → 抽屉 → 根目录 → Pictures
        tap("content-desc", "Show roots", f"{tag}_roots", wait=1.5, allow_missing=True)
        if not tap("text", "Pictures", f"{tag}_pictures", wait=2.0, allow_missing=True):
            swipe(int(W * 0.5), int(H * 0.75), int(W * 0.5), int(H * 0.35), ms=500, wait=1.0)
            tap("text", "Pictures", f"{tag}_pictures2", wait=2.0, allow_missing=True)
        node_root, _ = dump(f"{tag}_pics_dir")
        node = find_contains(node_root, "text", "portrait_test.jpg")
    if not node:
        results.append(f"{tag}: SAF navigation failed (portrait_test.jpg not found)")
        back()  # 关掉 SAF，回到首页让 fallback 接手
        time.sleep(1.5)
        return False
    c = bounds_center(node[0])
    if not c:
        return False
    run(["shell", "input", "tap", str(c[0]), str(c[1])])
    results.append(f"{tag}: SAF tapped portrait_test.jpg at {c}")
    time.sleep(2.0)
    return True


saf_ok = import_via_saf("B")
if not saf_ok:
    # 后备：Photo Picker（MediaStore 就绪时才有效）
    tap("text", "相册", "02_tap_gallery", wait=3.0)
    root, txt = dump("picker")
    shot("03_photo_picker")
    root, _ = dump("04_pick_image")
    photo_nodes = find_contains(root, "content-desc", "Photo taken on")
    if photo_nodes:
        c = bounds_center(photo_nodes[0])
        if c:
            run(["shell", "input", "tap", str(c[0]), str(c[1])])
            results.append(f"picker tap first 'Photo taken on' at {c}")
            time.sleep(2.0)
    else:
        results.append("PICK: no 'Photo taken on' nodes")
# 选定后编辑器渲染需要时间（解码 + 首帧 + ML 探测）
time.sleep(6.0)
root, txt = dump("editor")
shot("05_editor_first_frame")
# 进入校验：编辑器的顶栏特征文案（撤销/导出）。没进去就记录，后续步骤自会 NOT FOUND。
editor_in = "导出" in txt or "撤销" in txt
results.append(f"EDITOR OPEN: {editor_in}")
print("editor open:", editor_in)
# ⚠️ 等首帧渲染完成再动参数（最多 90s）：「渲染中…」消失 = 首帧就绪。
# 首帧时长 = 解码 + 全分辨率线性转换 + ML 预热 —— 模拟器软渲染下可能数十秒。
first_frame = False
for i in range(45):
    root, t = dump("render_wait")
    if t and ("渲染中" not in t):
        first_frame = True
        results.append(f"first frame ready after ~{i * 2}s")
        break
    time.sleep(2)
if not first_frame:
    results.append("FIRST FRAME STALL: 渲染中… 未消失（90s 超时）—— 渲染管线/ML 挂起嫌疑")
shot("05b_editor_first_ready")

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
    # —— v0.7.3 复测：强度环拖动（P1-1 回归 —— 旧版第一格拖动即取消预设）——
    root2, t2 = dump("26b_dial")
    dial = find_contains(root2, "content-desc", "预设强度")
    if dial:
        c = bounds_center(dial[0])
        if c:
            # 圆环拖动：从环心右下 45° 向内/向上扫（角度 → 值变化）
            run(["shell", "input", "swipe", str(c[0] + 30), str(c[1] + 40), str(c[0] - 10), str(c[1] - 30), "500"])
            time.sleep(1.0)
            root3, t3 = dump("26c_dial_after")
            results.append(f"DIAL DRAG: before={('预设强度' in t2)} after-desc-changed={t2 != t3}")
            shot("26b_strength_dial_dragged", delay=0.3)
    else:
        results.append("DIAL: not found (preset not applied?)")

# ———— G. 顶栏动作：撤销 / 旋转 / 自动增强 ————
tap("content-desc", "撤销", "30_undo", wait=1.0, contains=True, allow_missing=True)
tap("content-desc", "旋转", "31_rotate", wait=2.5, contains=True, allow_missing=True)
shot("32_after_rotate")
tap("content-desc", "撤销", "33_undo_rotate", wait=2.5, contains=True, allow_missing=True)
tap("content-desc", "自动增强", "34_auto_enhance", wait=3.0, contains=True, allow_missing=True)
shot("35_auto_enhanced")
# —— v0.7.3 复测：拾取蒙版 + 琥珀虚线轮廓（MaskContour 上屏验证）——
tap("content-desc", "点选作用域", "36_pick_enter", wait=1.5, contains=True, allow_missing=True, attempts=3)
run(["shell", "input", "tap", str(int(W * 0.25)), str(int(H * 0.30))])
time.sleep(2.5)
shot("37_pick_mask_contour")
tap("content-desc", "退出点选作用域", "38_pick_exit", wait=1.0, contains=True, allow_missing=True, attempts=3)

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
# ⚠️ Sheet 主按钮是「导出到相册（JPEG）」—— 顶栏也有一个「导出」，按面积最小编号
# 会误选顶栏（首轮走查实测：导出从未发生，后面全卡在 Sheet 上）。用独有文案。
tap("text", "导出到相册", "52_export_go", wait=1.5, contains=True, allow_missing=True, attempts=6)
# 轮询回执（导出含全分辨率渲染 + EXIF 写回，数秒～数十秒）。
# ⚠️ 匹配到的那个 dump 里**顺手取「完成」坐标**：导出后 uiautomator 对该界面
# 会持续返回竞态快照（实测 6 次重试都缺回执节点），靠后续 dump 点按不可靠。
receipt = False
done_coords = None
for _ in range(14):
    root, t = dump("receipt_poll")
    if t and ("已导出" in t and "分享" in t):
        receipt = True
        dn = find_nodes(root, "text", "完成")
        if dn:
            done_coords = bounds_center(dn[0])
        break
    time.sleep(2.5)
results.append(f"RECEIPT SHOWN: {receipt} done_at={done_coords}")
shot("53_export_receipt")
if done_coords:
    run(["shell", "input", "tap", str(done_coords[0]), str(done_coords[1])])
    results.append(f"receipt 完成 tapped at {done_coords}")
    time.sleep(1.5)
else:
    tap("text", "完成", "54_receipt_done", wait=1.5, allow_missing=True, attempts=6)
# 兜底：Sheet 还开着（回执未出）就系统返回一次，让后续设置页步骤能走
root, t = dump("after_done")
if t and "导出到系统相册" in t:
    back()
    results.append("export sheet still open -> back()")

# ———— J. 退出编辑器 → 首页 ————
# 退出编辑器用系统返回键：回执已关（showExport=false）⇒ BackHandler 链落到
# editorOnBack —— 与顶栏「返回」完全同一条路径，且不依赖动画期的 uiautomator dump。
run(["shell", "input", "keyevent", "4"])
time.sleep(2.5)
results.append(f"focus after back={foreground()!r}")

# —— v0.7.3 复测：进程死亡会话恢复 —— 编辑态刚落盘（导出+调整），强杀重启应无缝续上
run(["shell", "am", "force-stop", "com.hifn.pixelcake.debug"])
time.sleep(1.5)
run(["shell", "am", "start", "-n", "com.hifn.pixelcake.debug/com.hifn.pixelcake.MainActivity"])
time.sleep(8.0)
root, t = dump("61b_session_restore")
restored = "导出" in t and "撤销" in t
results.append(f"SESSION RESTORED: {restored}")
shot("61b_session_restored")
# 退出恢复出来的编辑器（清档，保持后续步骤干净）
run(["shell", "input", "keyevent", "4"])
time.sleep(2.0)

# —— v0.7.3 复测：fontScale 1.3 人体工学（Rail heightIn / deck scale / 布局不裁切）——
run(["shell", "settings", "put", "system", "font_scale", "1.3"])
time.sleep(2.0)
run(["shell", "am", "force-stop", "com.hifn.pixelcake.debug"])
time.sleep(1.0)
run(["shell", "am", "start", "-n", "com.hifn.pixelcake.debug/com.hifn.pixelcake.MainActivity"])
time.sleep(6.0)
shot("63c_font13_home")
run(["shell", "am", "force-stop", "com.hifn.pixelcake.debug"])
run(["shell", "settings", "put", "system", "font_scale", "1.0"])
time.sleep(1.0)
run(["shell", "am", "start", "-n", "com.hifn.pixelcake.debug/com.hifn.pixelcake.MainActivity"])
time.sleep(4.0)
root, _ = dump("home_after")
shot("61_home_after_back")

# ———— K. 设置页走查 ————
tap("text", "设置", "62_open_settings", wait=2.0)
shot("63_settings")
# 滚动到设置页下半部（相机直连组 / 模型统计行在 LazyColumn 更下方）
swipe(int(W * 0.5), int(H * 0.75), int(W * 0.5), int(H * 0.22), ms=500, wait=1.0)
swipe(int(W * 0.5), int(H * 0.75), int(W * 0.5), int(H * 0.22), ms=500, wait=1.0)
shot("63b_settings_scrolled")
root, st = dump("settings_scrolled")
results.append(f"CAMERA GROUP VISIBLE: {'相机直连' in st}")
results.append(f"MODEL STATS ROW: {'推理次数' in st}")
tap("text", "查看", "64_model_center", wait=2.0, allow_missing=True, attempts=4)
shot("65_model_center")
# 滚动 Sheet 到统计/SHA 行
swipe(int(W * 0.5), int(H * 0.70), int(W * 0.5), int(H * 0.30), ms=500, wait=1.0)
shot("65b_model_center_stats")
root, mt = dump("65c_model_stats")
results.append(f"MODEL STATS ROW: {'推理次数' in mt} SHA ROW: {'SHA-256' in mt}")
back()
back()
shot("66_settings_back")
# 恢复默认（确认弹窗出现 → 取消）
tap("text", "恢复", "67_reset_tap", wait=1.5, allow_missing=True)
shot("68_reset_confirm")
tap("text", "取消", "69_reset_cancel", wait=1.0, allow_missing=True)

# ———— L. 边界：tiny.jpg ————
tap("text", "调色台", "70_back_darkroom", wait=1.5, contains=True, allow_missing=True)
# tiny 走同一条 SAF 路：Pictures 里点 tiny.jpg（文件名可点，无歧义）
if not tap("text", "文件 ARW", "71_saf_open", wait=3.0, allow_missing=True):
    tap("text", "相册", "71_open_gallery2", wait=3.0, allow_missing=True)
root, _ = dump("72_saf_tiny")
t_node = find_contains(root, "text", "tiny.jpg")
if not t_node:
    tap("content-desc", "Show roots", "72_roots", wait=1.5, allow_missing=True)
    tap("text", "Pictures", "72_pictures", wait=2.0, allow_missing=True)
    root, _ = dump("72_pics_dir")
    t_node = find_contains(root, "text", "tiny.jpg")
if t_node:
    c = bounds_center(t_node[0])
    run(["shell", "input", "tap", str(c[0]), str(c[1])])
    results.append(f"tiny SAF tap at {c}")
else:
    results.append("TINY: saf navigation failed")
time.sleep(6.0)
root, ttxt = dump("tiny_editor")
shot("73_tiny_editor")
results.append(f"TINY EDITOR OPEN: {'导出' in ttxt or '撤销' in ttxt}")
# —— 平色疑点复现探针（§19.5）：首帧渲染期间就横拖参数 ——
# tier=low 模拟器首帧可达十几秒；此窗口内拖曝光曾在旧轮次复现「渲染完成后短暂平色」。
# 若 shot 75（渲染沉降后）出现均匀平色 + 直方图三尖峰，疑点坐实（app-debuglog 同步取证）；
# 否则继续记账为未复现。
swipe(int(W * 0.30), int(H * 0.40), int(W * 0.70), int(H * 0.40), ms=500, wait=0.5)
shot("74_drag_during_first_render", delay=0.3)
time.sleep(12)
shot("75_after_render_settle")
tap("content-desc", "返回", "74_back2", wait=2.0, contains=True, allow_missing=True)

print("\n=== WALKTHROUGH RESULT ===")
for r in results:
    print(r)
print("=== END ===")
