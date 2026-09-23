package com.hifn.pixelcake.core.edit

/**
 * 像素管线的测试专用入口（**不是**产品 API）。
 *
 * ## 为什么需要它们
 *
 * 批次 3 起 `PixelProgram.applyLinear` / `applySrgb8` 多收一对**长边归一化坐标** `(u, v)`，
 * 而且刻意**不给默认值**（给了默认值，「某个调用点忘了传坐标」就会退化成「暗角 / 颗粒
 * 静默失效」，编译不过比跑错好）。代价是大量只测**逐像素**参数的既有用例都得补上一对
 * 与断言无关的坐标 —— 这对坐标会盖住真正要测的东西。
 *
 * 所以这里给两个「坐标钉在画面中心」的入口，让那些用例保持「输入 3 个数、输出 3 个数」的形态；
 * 而**几何本身**的用例（`EffectMathTest`）一律直接调 `applyXxx(..., u, v)`，把坐标当成被断言的对象。
 *
 * ## 为什么是 `internal` 而不是各文件一个 `private`
 *
 * 三个测试文件同属一个包。file-private 的同名扩展在跨文件时是否算重定义，属于
 * 「编译器说了算、而本项目本地没有编译器」的那一类问题（CI 是唯一的编译器）。
 * 定成一处 `internal` 既躲开这个问题，也保证三个文件用的是**同一对**坐标 ——
 * 否则 `linearAndSrgbEntriesAgreeOnSameInput`（断言两条入口同源）会拿「坐标不同」
 * 当「管线不同」，把真正要守的不变量架空。
 *
 * 中心坐标 `(0.5, 0.5)` 与 `PixelProgram` 默认的 1×1 画幅配套，就是正中心 ⇒
 * 暗角权重恒为 0（中心不动是 `buildVignetteLut` 的承诺），颗粒与其他逐像素参数无关。
 */
internal fun PixelProgram.srgbAtCenter(r: Int, g: Int, b: Int): Int =
    applySrgb8(r, g, b, 0.5f, 0.5f)

/** 见 [srgbAtCenter]：16-bit 线性入口的同源版本。 */
internal fun PixelProgram.linearAtCenter(r: Int, g: Int, b: Int): Int =
    applyLinear(r, g, b, 0.5f, 0.5f)
