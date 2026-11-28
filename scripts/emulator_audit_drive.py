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


def _is_tree(root):
    # v0.9.2：dump() 返回 (tree, text) —— 曾有调用点把 text 当 tree 传进 find_*，
    # 'str'.iter → AttributeError 杀整轮（23a 一行的陈年错位，被 || true 掩成假绿）。
    # 在入口免疫：str/None 一律空结果，调用点按 NOT FOUND 记录继续走。
    return root is not None and not isinstance(root, str)


def find_nodes(root, attr, value):
    if not _is_tree(root):
        return []
    if root is None:
        return []
    return [n for n in root.iter("node") if n.get(attr) == value]


def find_contains(root, attr, value):
    if not _is_tree(root):
        return []
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
    # v0.7.4：SAF Recent 首次打开可能为空（ExternalStorageProvider 首扫竞态）
    # ⇒ 轮询最多 30s，而不是一次 dump 定成败。
    node = None
    for attempt in range(10):
        root, _ = dump(f"{tag}_saf_try{attempt}")
        for want in ("portrait_test.jpg", "landscape_test.jpg", "tiny.jpg"):
            node = find_contains(root, "text", want)
            if node:
                results.append(f"{tag}: SAF found {want} (try {attempt})")
                break
        if node:
            break
        time.sleep(3.0)
    if not node:
        # Recent 没有 → 抽屉 → 根目录 → Pictures
        tap("content-desc", "Show roots", f"{tag}_roots", wait=1.5, allow_missing=True)
        if not tap("text", "Pictures", f"{tag}_pictures", wait=2.0, allow_missing=True):
            swipe(int(W * 0.5), int(H * 0.75), int(W * 0.5), int(H * 0.35), ms=500, wait=1.0)
            tap("text", "Pictures", f"{tag}_pictures2", wait=2.0, allow_missing=True)
        node_root, _ = dump(f"{tag}_pics_dir")
        node = None
        for want in ("portrait_test.jpg", "landscape_test.jpg", "tiny.jpg"):
            node = find_contains(node_root, "text", want)
            if node:
                break
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


# v0.9.2：导入环节整体重试 ×3（SAF Recent / Photo Picker 都有 MediaStore·
# DocumentsProvider 层竞态 —— 单次失败不代表图库未就绪，回首页再来一轮）。
editor_opened = False
for import_attempt in range(3):
    results.append(f"IMPORT ATTEMPT {import_attempt}")
    saf_ok = import_via_saf(f"B{import_attempt}")
    if not saf_ok:
        # 后备：Photo Picker（MediaStore 就绪时才有效）；picker 列表加载也带重试
        tap("text", "相册", f"02_tap_gallery{import_attempt}", wait=3.0)
        shot("03_photo_picker")
        photo_nodes = []
        for pick_try in range(4):
            root, txt = dump(f"04_pick_image{import_attempt}_{pick_try}")
            photo_nodes = find_contains(root, "content-desc", "Photo taken on")
            if photo_nodes:
                break
            time.sleep(3.0)
        if photo_nodes:
            c = bounds_center(photo_nodes[0])
            if c:
                run(["shell", "input", "tap", str(c[0]), str(c[1])])
                results.append(f"picker tap first 'Photo taken on' at {c}")
                time.sleep(2.0)
        else:
            results.append(f"PICK: no 'Photo taken on' nodes (attempt {import_attempt})")
    # 编辑器是否已开（重试轮里任一路成功即算）——没开就回首页重试
    time.sleep(6.0)
    root_chk, t_chk = dump(f"editor_check{import_attempt}")
    if "导出" in (t_chk or "") or "撤销" in (t_chk or ""):
        editor_opened = True
        results.append(f"IMPORT OK on attempt {import_attempt}")
        break
    back()
    time.sleep(1.5)
    root_h, t_h = dump(f"home_again{import_attempt}")
    if "相册" not in (t_h or ""):
        run(["shell", "am", "start", "-n", f"{PKG}/com.hifn.pixelcake.MainActivity"])
        time.sleep(4.0)
if not editor_opened:
    results.append("IMPORT: all 3 attempts failed (infrastructure race)")
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
# —— v0.7.4 复测：Deck 三档收起（用户痛点 1/2：预览被固定背景遮挡）——
# 上拖手柄 → Deck 吸附收起为 44dp 单行 → 预览面积 +148dp。
# 复测修正：手柄坐标用 dump 定位（估算坐标会打在 Deck 内容上）。
root, hDump = dump("23a_handle")
handle = find_contains(root, "content-desc", "调整参数面板高度")  # v0.9.2：hDump 是文本，曾错传杀整轮
if handle:
    c = bounds_center(handle[0])
    if c:
        # v0.7.5 复测修正：**下拖** = Deck 变矮（上拖是变高）—— 旧方向反了
        run(["shell", "input", "swipe", str(c[0]), str(c[1]),
            str(c[0]), str(c[1] + int(H * 0.30)), "500"])
    else:
        results.append("HANDLE: zero-size bounds (race snapshot) -> estimated swipe")
        run(["shell", "input", "swipe", str(int(W * 0.5)), str(int(H * 0.45)),
            str(int(W * 0.5)), str(int(H * 0.25)), "500"])
else:
    results.append("HANDLE: not found")
    run(["shell", "input", "swipe", str(int(W * 0.5)), str(int(H * 0.45)),
        str(int(W * 0.5)), str(int(H * 0.25)), "500"])
time.sleep(1.5)
shot("23b_deck_collapsed")
root, ct = dump("23c_collapsed_dump")
results.append("DECK COLLAPSED: " + str("展开参数面板" in ct))
# 点任意处展开回 S 档
run(["shell", "input", "tap", str(int(W * 0.5)), str(int(H * 0.85))])
time.sleep(1.2)
# —— v0.7.4 复测：滑块 ± 步进钮（用户痛点 3：修图不好控制）——
root, st = dump("23d_stepper")
plus = find_contains(root, "content-desc", "加一格")
if plus:
    c = bounds_center(plus[0])
    run(["shell", "input", "tap", str(c[0]), str(c[1])])
    run(["shell", "input", "tap", str(c[0]), str(c[1])])
    time.sleep(1.0)
    root2, st2 = dump("23e_stepper_after")
    results.append("STEPPER TAPPED x2: value-changed=" + str(st != st2))
    shot("23f_stepper_after", delay=0.3)
else:
    results.append("STEPPER: not found")

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

# ———— F2. v0.9.0 滤镜页（3D cube LUT chips + 强度滑块诚实可见）————
try:
    tap("text", "滤镜", "F2_lut_tab", wait=2.0)
    root, _ = dump("F2_lut_deck")
    shot("24b_lut_filter_deck")
    results.append("LUT CHIPS: " + str("青橙对比" in (_ or "")))
    tap("text", "青橙对比", "F2_lut_apply", wait=2.0, contains=True)
    time.sleep(1.5)
    shot("24c_lut_applied")
    t2 = ""
    for attempt in range(3):
        root2, t2 = dump(f"F2_lut_intensity_try{attempt}")
        if "滤镜强度" in (t2 or ""):
            break
        time.sleep(2.0)
    results.append("LUT INTENSITY SLIDER: " + str("滤镜强度" in (t2 or "")))
    tap("content-desc", "撤销", "F2_lut_undo", wait=1.5, contains=True, allow_missing=True)
except Exception as e:
    results.append(f"F2 PROBE CRASHED: {e!r}")

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

# ———— G2. v0.8.0 裁剪模式（历史面板头部入口 → 覆盖层 + 比例 chips）————
try:
    tap("content-desc", "历史", "G2_open_history", wait=2.0, contains=True, allow_missing=True)
    tap("content-desc", "裁剪", "G2_open_crop", wait=2.5, contains=True, allow_missing=True, attempts=3)
    time.sleep(1.5)
    # v0.9.2：单次 dump 有竞态 —— 轮询 3 次 × 2s 再下结论
    t = ""
    for attempt in range(3):
        root, t = dump(f"G2_crop_mode_try{attempt}")
        if "应用裁剪" in (t or ""):
            break
        time.sleep(2.0)
    shot("30b_crop_mode")
    results.append("CROP MODE UI: " + str("应用裁剪" in (t or "")))
    tap("text", "1:1", "G2_crop_aspect", wait=1.5, contains=True, allow_missing=True)
    time.sleep(1.0)
    shot("30c_crop_aspect")
    root2, t2 = dump("G2_crop_aspect_dump")
    results.append("CROP ASPECT APPLIED: " + str(("重置" in (t2 or "")) and ("取消" in (t2 or ""))))
    tap("text", "取消", "G2_crop_cancel", wait=1.5, contains=True, allow_missing=True)
except Exception as e:
    results.append(f"G2 PROBE CRASHED: {e!r}")

# ———— H. 缩放：双击放大 + 复位 ————
run(["shell", "input", "tap", str(int(W * 0.45)), str(int(H * 0.30))])
time.sleep(0.15)
run(["shell", "input", "tap", str(int(W * 0.45)), str(int(H * 0.30))])
time.sleep(1.5)
shot("40_double_tap_zoom")
# —— v0.7.4 复测：直方图悬浮小卡 → 展开全宽条 ——
tap("content-desc", "RGB 直方图", "40b_hist_expand", wait=1.2, contains=True, allow_missing=True, attempts=3)
shot("40c_hist_expanded")
# —— v0.7.4 复测：直方图悬浮小卡 → 展开全宽条 ——
tap("content-desc", "RGB 直方图", "40b_hist_expand", wait=1.2, contains=True, allow_missing=True, attempts=3)
shot("40c_hist_expanded")
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
# —— v0.7.3 复测：进程死亡会话恢复 —— 此刻编辑器仍开着、会话已由防抖落盘
# （导出+自动增强+旋转都发生过）；强杀重启应无缝续上（v0.7.2 新能力的运行时验收）。
run(["shell", "am", "force-stop", "com.hifn.pixelcake.debug"])
time.sleep(1.5)
run(["shell", "am", "start", "-n", "com.hifn.pixelcake.debug/com.hifn.pixelcake.MainActivity"])
time.sleep(9.0)
root, t = dump("61b_session_restore")
restored = "导出" in t and "撤销" in t
# v0.9.2：恢复设计 = 首页「继续上次编辑」胶囊（不自动进编辑器）—— 旧预期过时
restored2 = "继续上次编辑" in (t or "")
results.append(f"SESSION PILL: {restored2} (editor-open-check={restored})")
shot("61b_session_restored")
# 退出编辑器用系统返回键：回执已关（showExport=false）⇒ BackHandler 链落到
# editorOnBack —— 与顶栏「返回」完全同一条路径，且不依赖动画期的 uiautomator dump。
run(["shell", "input", "keyevent", "4"])
time.sleep(2.5)
results.append(f"focus after back={foreground()!r}")

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

# ———— K2. v0.9.1 相机直连面板（无真机：扫描/未连接态渲染；快门钮需设备才出现）————
try:
    tap("text", "相机直连", "K2_open_camera", wait=2.5, contains=True, allow_missing=True, attempts=3)
    time.sleep(1.5)
    root, t = dump("K2_camera_panel")
    shot("41b_camera_panel")
    # v0.9.2：实测面板文案是「检测 USB 设备 / 连接并握手」（旧关键词是臆测的）
    results.append("CAMERA PANEL: " + str(("检测 USB 设备" in (t or "")) or ("连接并握手" in (t or ""))))
    back()
except Exception as e:
    results.append(f"K2 PROBE CRASHED: {e!r}")

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
    if c:
        run(["shell", "input", "tap", str(c[0]), str(c[1])])
        results.append(f"tiny SAF tap at {c}")
    else:
        results.append("tiny: zero-size bounds (race snapshot) -> skipped")
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
