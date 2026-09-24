package com.hifn.pixelcake.core.edit

import android.graphics.Bitmap
import com.hifn.pixelcake.core.decode.LinearImage
import com.hifn.pixelcake.core.decode.RawLinearSource
import com.hifn.pixelcake.core.edit.retouch.RetouchLayer
import com.hifn.pixelcake.diag.DebugLog
import java.util.concurrent.Executors

/**
 * 把 [EditParams]（+ retouch 层 + 对象作用域图层）应用到位图上。
 *
 * 三条入口共用同一条像素管线（[PixelProgram]），只是底图来源不同：
 *  - [renderIntoSrgb]        ：底图是 8-bit sRGB 位图（JPEG/HEIF，或 ARW 的秒开占位图）；
 *  - [renderIntoLinear]      ：底图是 [LinearImage]（ARW 的 16-bit 线性母版，滑块实时预览走这条）；
 *  - [renderLinearFile]      ：导出专用，全分辨率 RAW 边解码边分带渲染，不落 JVM 堆。
 *
 * 内存与性能（FIX_LIST F05/F06/F08；P1b-4 `docs/P1b_DESIGN.md` §2）：
 *  - 一律**分带**处理：一次只持有 `BAND_ROWS` 行的源/目标缓冲，
 *    不再出现「整幅 IntArray + jbyteArray + Bitmap」同时在世的 500MB 峰值；
 *  - 带内**按行切片**并行（见 [runBand]），每帧只构建一次 [PixelProgram]，逐像素零装箱；
 *  - 三条路径都把**长边归一化坐标** `(u, v)` 喂给 [PixelProgram] —— 批次 3 的暗角 / 颗粒
 *    要用它，而口径统一是「预览所见 = 导出所得」在几何量上的唯一保证（见 `EditParams` 末尾）；
 *  - 带与带之间检查取消回调，滑块连续拖动时可协作取消，不再排队积压；
 *  - tonal 渲染完成后，若传入 [RetouchState] 则在已物化的目标 Bitmap 上跑 retouch 整图 pass
 *    （磨皮/液化/祛瑕/追色），复用目标 Bitmap，不额外搬 16-bit 母版。
 *
 * ## 批次 5：对象作用域图层（`docs/OBJECT_TONE_DESIGN.md`）
 *
 * [LayerStack] 里的 `N` 层**在同一个像素循环内**各自算完再插值（见 [applyObjectLayers]）——
 * 不需要 N 份整幅缓冲、不需要 N 遍循环，代价只是「一次循环里算 N 遍」。
 * 三条入口都只多收一个带默认值的参数：[LayerStack.EMPTY] 时逐像素路径上只多一次
 * `list.isEmpty()` 判断（分支条件对整帧恒定），**没有对象层的用户一次多余的乘加都不付**。
 *
 * ⚠️ 层的目标值是 `layerProgram.applySrgb8(整图结果的三个字节, u, v)` ——
 * **三条入口写的是同一个表达式**（见 [applyObjectLayers] 里那句注释为什么必须如此）。
 *
 * **参数顺序约定**：`retouch` / `mask` / `faceAnchor` / `layers` 放在**取消/进度 lambda 之前**，
 * 使尾随 lambda 永远绑定到 `isCancelled` / `onProgress`（避免 Kotlin 尾随 lambda 误绑末尾参数的
 * 编译错误，见 CI run `34481989723`）。
 */
object EditEngine {

    /** 每带行数。越大并行效率越高、内存占用越大；32 行在 33MP 下约 2.7MB 源缓冲。 */
    private const val BAND_ROWS = 32

    /** 小于这个像素数的带直接串行，避免线程切换开销盖过收益。 */
    private const val PARALLEL_THRESHOLD = 4096

    /**
     * 预览单帧超过这个毫秒数才打一条 WARN 日志。
     *
     * 批次 1 / 2 / 3 / 4 把可调项从 10 项扩到 81 项，**「变慢了」必须是有数据的判断**，
     * 否则只能靠感觉。只在超阈值时打点：拖滑块时每帧都写日志既刷屏
     * （`DebugLog` 每次 write 都 `flush`）又反过来拖慢渲染，把要测的东西本身污染掉。
     *
     * ⚠️ 批次 3 是**第一次需要坐标**的一批：`runBand` 从扁平下标分片改成按行分片，
     * 就是为了不让 `i % w` / `i / w` 落进热循环（那条除法在 33MP 上是几百毫秒）。
     * 暗角 / 颗粒默认关闭时层级整段跳过，所以本批的**默认**性能开销只有多算一对坐标。
     *
     * ⚠️ 批次 5 是**第一次逐像素代价随层数增长**的一批（`1 + N` 次 `applySrgb8`，
     * `N` = 生效层数）。日志里因此带上 `layers` —— 看到「变慢了」时要能立刻分清是
     * 「用户建了 3 层」还是「管线本身退化了」。
     */
    private const val SLOW_RENDER_MS = 150L

    /** 跨核分片的线程池（懒初始化，大小对齐可用核心数）。 */
    private val pool by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        Executors.newFixedThreadPool(cores) { r ->
            Thread(r, "pxcake-render").apply { isDaemon = true }
        }
    }

    private val cores: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /** 8-bit sRGB 底图 -> 目标位图（复用同一目标位图，避免每帧重分配）。 */
    fun renderIntoSrgb(
        target: Bitmap,
        base: Bitmap,
        p: EditParams,
        layers: LayerStack = LayerStack.EMPTY
    ) {
        val w = base.width
        val h = base.height
        if (target.width != w || target.height != h) {
            DebugLog.e(
                DebugLog.TAG_EDIT, "render size mismatch",
                mapOf("target" to "${target.width}x${target.height}", "base" to "${w}x$h")
            )
            return
        }
        val program = PixelProgram(p, frameW = w, frameH = h)
        val ls = layers.layers
        val band = IntArray(BAND_ROWS * w)
        // 坐标一律「长边归一化」：`u = (x + 0.5) / L`。倒数在带循环外只算一次。
        val invL = 1f / maxOf(w, h).toFloat()
        var y = 0
        while (y < h) {
            val rows = minOf(BAND_ROWS, h - y)
            base.getPixels(band, 0, w, 0, y, w, rows)
            runBand(w, y, rows) { x, py, i ->
                val cp = band[i]
                val u = (x + 0.5f) * invL
                val v = (py + 0.5f) * invL
                val out = program.applySrgb8(
                    (cp shr 16) and 0xff, (cp shr 8) and 0xff, cp and 0xff, u, v
                )
                band[i] = applyObjectLayers(out, u, v, x, py, ls)
            }
            target.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
        // 细节（批次 4）：整条管线里唯一的**邻域**阶段，在已物化的目标位图上再跑一遍分带。
        // 它**只在整图**——对象层进不来（邻域算子要一张低频参考图，见 ObjectLayer 的剥离规则）。
        applyDetailPass(target, p, logAlways = false)
    }

    /**
     * 16-bit 线性底图 -> 目标位图。返回 false 表示被取消。
     *
     * @param faceAnchor 液化锚点（P1p-2c，绝对像素，与 [target] 同尺寸）；`null` = 退回蒙版质心猜。
     * @param layers 对象作用域图层（批次 5）；默认 [LayerStack.EMPTY] = 逐像素路径零额外代价。
     *   插在 [isCancelled] **之前**，以保住「尾随 lambda = isCancelled」的调用写法。
     */
    fun renderIntoLinear(
        target: Bitmap,
        base: LinearImage,
        p: EditParams,
        retouch: RetouchState? = null,
        mask: RetouchMask? = null,
        faceAnchor: RetouchLayer.FaceAnchor? = null,
        layers: LayerStack = LayerStack.EMPTY,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val w = base.width
        val h = base.height
        if (target.width != w || target.height != h) {
            DebugLog.e(
                DebugLog.TAG_EDIT, "render size mismatch",
                mapOf("target" to "${target.width}x${target.height}", "base" to "${w}x$h")
            )
            return false
        }
        val t0 = System.nanoTime()
        val program = PixelProgram(p, frameW = w, frameH = h)
        val ls = layers.layers
        val band = IntArray(BAND_ROWS * w)
        val invL = 1f / maxOf(w, h).toFloat()
        var y = 0
        while (y < h) {
            if (isCancelled()) return false
            val rows = minOf(BAND_ROWS, h - y)
            runBand(w, y, rows) { x, py, i ->
                val o = (py * w + x) * 3
                val u = (x + 0.5f) * invL
                val v = (py + 0.5f) * invL
                val out = program.applyLinear(
                    base.data[o].toInt() and 0xffff,
                    base.data[o + 1].toInt() and 0xffff,
                    base.data[o + 2].toInt() and 0xffff,
                    u, v
                )
                band[i] = applyObjectLayers(out, u, v, x, py, ls)
            }
            target.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
        if (retouch != null) RetouchLayer.apply(target, retouch, mask, faceAnchor)
        // 细节（批次 4）：必须排在精修**之后**，且三条渲染入口同序。理由见 DetailPass 的类 KDoc
        // （锐化挨着磨皮时量程会被吃掉一半）。预览用 logAlways = false —— 拖滑块时每帧写日志
        // 既刷屏、又会因 DebugLog 的 flush 反过来污染要测的耗时本身。
        applyDetailPass(target, p, logAlways = false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms >= SLOW_RENDER_MS) {
            DebugLog.w(
                DebugLog.TAG_EDIT, "slow preview render",
                mapOf("ms" to ms, "w" to w, "h" to h, "layers" to ls.size)
            )
        }
        return true
    }

    /**
     * 全分辨率 RAW 导出：打开一次线性会话，边取行边渲染，全程不把整幅线性图搬进 JVM 堆。
     *
     * @param onProgress 0..100 的进度回调，返回 false 表示取消。
     * @return 渲染完成的位图；失败返回 null。
     */
    fun renderLinearFile(
        path: String,
        maxLongSide: Int,
        p: EditParams,
        retouch: RetouchState? = null,
        mask: RetouchMask? = null,
        faceAnchor: RetouchLayer.FaceAnchor? = null,
        layers: LayerStack = LayerStack.EMPTY,
        onProgress: (Int) -> Boolean = { true }
    ): Bitmap? {
        val src = RawLinearSource.open(path, maxLongSide) ?: return null
        return try {
            val w = src.width
            val h = src.height
            val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val t0 = System.nanoTime()
            val program = PixelProgram(p, frameW = w, frameH = h)
            val ls = layers.layers
            val srcBand = ShortArray(BAND_ROWS * w * 3)
            val dstBand = IntArray(BAND_ROWS * w)
            val invL = 1f / maxOf(w, h).toFloat()
            var y = 0
            var ok = true
            while (y < h && ok) {
                val rows = minOf(BAND_ROWS, h - y)
                if (src.readRows(y, rows, srcBand) != rows) {
                    DebugLog.e(DebugLog.TAG_EDIT, "export readRows failed", mapOf("y" to y))
                    ok = false
                    break
                }
                runBand(w, y, rows) { x, py, i ->
                    val o = i * 3
                    val u = (x + 0.5f) * invL
                    val v = (py + 0.5f) * invL
                    val out = program.applyLinear(
                        srcBand[o].toInt() and 0xffff,
                        srcBand[o + 1].toInt() and 0xffff,
                        srcBand[o + 2].toInt() and 0xffff,
                        u, v
                    )
                    dstBand[i] = applyObjectLayers(out, u, v, x, py, ls)
                }
                target.setPixels(dstBand, 0, w, 0, y, w, rows)
                y += rows
                ok = onProgress(y * 100 / h)
            }
            if (ok) {
                if (retouch != null) RetouchLayer.apply(target, retouch, mask, faceAnchor)
                // 细节（批次 4）：导出路径同样排在精修**之后**，与两条预览路径**同序** ——
                // 三条入口一旦有一处顺序不同，「预览所见 = 导出所得」这句话就不成立了。
                // 导出是低频操作，所以每次都写日志（这正是要盯的性能基线，见下一段说明）。
                applyDetailPass(target, p, logAlways = true)
                // 导出耗时**每次都打**（导出是低频操作，且这正是要盯的性能基线）。
                // 带上 px 是为了能换算「每百万像素多少毫秒」，从而在不同机型/不同分辨率之间可比；
                // 带上 layers 是为了分清「用户建了几层」与「管线本身退化了」（批次 5）。
                val ms = (System.nanoTime() - t0) / 1_000_000
                DebugLog.i(
                    DebugLog.TAG_EDIT, "export linear ok",
                    mapOf("w" to w, "h" to h, "px" to (w * h), "ms" to ms, "layers" to ls.size)
                )
                target
            } else {
                target.recycle()
                null
            }
        } finally {
            src.close()
        }
    }

    /**
     * 一个像素的最终结果：**整图程序的结果 → 依次朝每一层插值**（批次 5）。
     *
     * ## 三条入口共用这一处，是「预览所见 = 导出所得」在对象层上的落点
     *
     * 层的目标值统一写作 `layerProgram.applySrgb8(整图结果的三个字节, u, v)`，
     * 即**层是「叠加量」**：作用在整图调色之后的结果上，与 Lightroom 的局部调整一致
     * （`docs/OBJECT_TONE_DESIGN.md` §5.2 有完整取舍论证）。
     *
     * 为什么这句必须**只出现一次**：另一种候选语义是「层作用在**原始**像素上」，
     * 那会让线性入口写成 `applyLinear(原始 r16/g16/b16)`、sRGB 入口写成
     * `applySrgb8(原始 r8/g8/b8)` —— 两条入口各写一遍、各改各的，
     * 于是「预览与导出一致」从一条**结构性事实**退化成一句需要逐条维护的承诺。
     *
     * ## 零分配
     *
     * `blendStack8` 与这里的两个 lambda 全是 `inline`，捕获的是本函数的局部量
     * （`base` / `u` / `v` / `x` / `py` / `ls`）⇒ 逐像素路径上没有任何对象产生。
     *
     * @param base 整图程序算出的 0xAARRGGBB
     * @param ls **已过滤**的层列表（见 `buildLayerStack`：强度 0 / 中性 / 无蒙版的层都不在里面）；
     *   空列表时本函数是恒等映射，`blendStack8` 会直接返回 `base`
     */
    private inline fun applyObjectLayers(
        base: Int,
        u: Float,
        v: Float,
        x: Int,
        py: Int,
        ls: List<AppliedLayer>
    ): Int {
        if (ls.isEmpty()) return base
        val r8 = (base shr 16) and 0xff
        val g8 = (base shr 8) and 0xff
        val b8 = base and 0xff
        return blendStack8(
            base = base,
            count = ls.size,
            // ⚠️ 层的输入是**整图结果**（r8/g8/b8 = base 的三个字节），不是原始像素。
            // 改成原始像素就等于把语义从「叠加量」换成「绝对值」，见上面那段论证。
            targetAt = { k -> ls[k].program.applySrgb8(r8, g8, b8, u, v) },
            weightAt = { k -> ls[k].mask.sample(x, py) * ls[k].weight }
        )
    }

    /**
     * 在一个带内**按行切片**并行执行 [block]（各片写不同下标，天然无竞争）。
     *
     * [block] 收到三个量，全都是「拿来就能用」的：
     *  - `x`  —— **绝对**列号（0..w−1）
     *  - `y`  —— **绝对**行号（带起点已加好）
     *  - `i`  —— **带内**扁平下标 = `(y − y0) · w + x`，即读写源/目标 `band` 缓冲用的下标
     *
     * ## 为什么从「扁平下标分片」改成「按行分片」（批次 3）
     *
     * 旧签名只递一个扁平下标，调用方要拿到坐标就得 `i % w` / `i / w` —— 整数除法约 20 个周期，
     * 33MP 上是**几百毫秒**的纯开销，而且它还落在最内层的热循环里。按行分片之后，
     * `y` 在行内是常量（每行只算一次），`x` 就是内层循环计数器：热循环里一个除法都没有。
     *
     * 代价是负载均衡从「按像素均分」变成「按行均分」。`BAND_ROWS = 32` 下 8 核各 4 行、
     * 16 核各 2 行；而每一行内部本来就是串行的，所以最坏不均衡也只是一行的粒度 ——
     * 相比省下的几百毫秒，这个代价可以忽略。
     *
     * ⚠️ 批次 5 起，同一个 [PixelProgram] 实例仍被多核同时调用，**层程序也一样**。
     * 所以 `AppliedLayer.program` 不能持有任何可变状态（`PixelProgram` 的构造期字段都是 `val`，
     * 逐像素路径只读）—— 「共享可变缓冲」这类写法在这里是数据竞争，不是优化。
     */
    private inline fun runBand(w: Int, y0: Int, rows: Int, crossinline block: (Int, Int, Int) -> Unit) {
        val n = cores
        if (rows * w < PARALLEL_THRESHOLD || n <= 1) {
            for (r in 0 until rows) {
                val y = y0 + r
                val base = r * w
                for (x in 0 until w) block(x, y, base + x)
            }
            return
        }
        val slices = n.coerceAtMost(rows)
        val chunk = (rows + slices - 1) / slices
        val jobs = ArrayList<java.util.concurrent.Future<*>>(slices)
        for (c in 0 until slices) {
            val start = c * chunk
            if (start >= rows) break
            val end = minOf(start + chunk, rows)
            jobs.add(pool.submit {
                for (r in start until end) {
                    val y = y0 + r
                    val base = r * w
                    for (x in 0 until w) block(x, y, base + x)
                }
            })
        }
        // 等所有分片完成再落盘，保证 setPixels 看到完整结果。
        jobs.forEach { it.get() }
    }

    fun render(
        base: Bitmap,
        p: EditParams,
        recycleBase: Boolean = false,
        layers: LayerStack = LayerStack.EMPTY
    ): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        renderIntoSrgb(out, base, p, layers)
        if (recycleBase) base.recycle()
        return out
    }
}
