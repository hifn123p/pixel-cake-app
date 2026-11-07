package com.hifn.pixelcake.ui.editor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 编辑器会话的**生命周期决策**（T2 最小层，审计 P1-12 的可测化切片）。
 *
 * `MainActivity.onBack` 是全文件最危险的一段回收（use-after-free 的历史温床）。
 * 本对象把其中**可脱离 Android 类型单测的两条决策**收进来，位图本体仍留在 UI 层：
 *
 * 1. [planRetirement] —— 「导出期间不手动回收」的判定：导出的降级路径会直接读
 *    `rendered` 去编码，那段时间它的归属权是**共享的**；手动回收必须等导出结束，
 *    而这几张图交给 GC 是更划算的交换。旧逻辑内联在 onBack 里，判据改一次就要
 *    重读一遍 1300 行 —— 现在它是一个有名字、有测试的函数。
 * 2. [retireInLock] —— 「回收动作进渲染锁」的执行：与所有像素写入串行化，
 *    是「回收那一刻没有写者」能被**证明**（而不是赌）的唯一办法。
 *
 * 位图回收本身（`Bitmap.recycle()`）通过 [retireInLock] 的 [release] 回调注入 ——
 * JVM 单测用计数 lambda 验证顺序与互斥，不需要 Robolectric。
 */
object EditorSessionLifecycle {

    /**
     * 退出时的回收决策（纯函数）。
     * @param exporting 导出进行中 ⇒ 共享归属权 ⇒ 返回空列表（全部交 GC）
     * @param candidates 候选资源（调用方已把 null 摘除亦可；本函数再次过滤，双保险）
     * @return 待回收资源，**保序**（回收顺序 = 候选顺序，不做任何重排）
     */
    fun <T> planRetirement(exporting: Boolean, candidates: List<T?>): List<T> =
        if (exporting) emptyList() else candidates.filterNotNull()

    /**
     * 在 [lock] 内逐个 [release]。所有像素写入者都持有同一把锁 ⇒ 授予锁即证明
     * 「此刻没有在途写者」。泛型让调用方直接传 `List<Bitmap>`，release 里拿到
     * 的就是 Bitmap（不必 `as` 强转—— 类型不匹配本来就该在编译期暴露）。
     */
    suspend fun <T> retireInLock(lock: Mutex, resources: List<T>, release: (T) -> Unit) {
        lock.withLock {
            for (r in resources) release(r)
        }
    }
}
