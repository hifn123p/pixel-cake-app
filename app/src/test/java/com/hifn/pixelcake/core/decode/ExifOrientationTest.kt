package com.hifn.pixelcake.core.decode

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-2 M-1：EXIF 方向 → 旋转角的映射表（翻转类方向 -1，由位图变换分支处理）。
 * 数值与 `Decoder.applyExifOrientation` 的 when 分支必须同源。
 */
class ExifOrientationTest {

    @Test
    fun rotateCases_mapToDegrees() {
        assertEquals(90, Decoder.orientationRotationDegrees(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, Decoder.orientationRotationDegrees(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, Decoder.orientationRotationDegrees(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun normalAndUnknown_needNoRotation() {
        assertEquals(-1, Decoder.orientationRotationDegrees(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(-1, Decoder.orientationRotationDegrees(0))
        assertEquals(-1, Decoder.orientationRotationDegrees(99))
    }

    @Test
    fun flipCases_routeToMirrorBranch() {
        // 2/4/5/7 都带镜像分量，不是纯旋转 —— 必须落到 -1（镜像分支），否则竖拍带镜像的
        // 方向会被转成对方的镜像（左右翻）。
        for (o in intArrayOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE
        )) {
            assertEquals(-1, Decoder.orientationRotationDegrees(o))
        }
    }
}
