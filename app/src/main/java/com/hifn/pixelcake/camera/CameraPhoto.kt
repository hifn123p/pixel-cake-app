package com.hifn.pixelcake.camera

/**
 * 相机里的一个可拉取对象：句柄 + 元数据。
 *
 * `handle` 是 PTP 的对象句柄（`GetObject` / `GetObjectInfo` 都用它定位对象），
 * 元数据里的**文件名后缀**是判断类型的首选依据（相机常把 RAW 报成 `0x3000` Undefined）。
 */
data class CameraPhoto(val handle: Int, val info: PtpObjectInfo) {

    val filename: String get() = info.filename
    val sizeBytes: Long get() = info.compressedSizeBytes
    val isRaw: Boolean get() = info.looksLikeRaw

    /** RAW / HEIF / JPEG —— 用于列表展示。 */
    fun typeName(): String = when {
        isRaw -> "RAW"
        info.extension == "heic" || info.extension == "hif" -> "HEIF"
        info.looksLikeJpeg -> "JPEG"
        info.extension.isNotEmpty() -> info.extension.uppercase()
        else -> PtpProtocol.formatName(info.format)
    }

    /** 紧凑一行（列表用，比 `PtpObjectInfo.label()` 短）。 */
    fun label(): String = "%s · %s · %.1fMB".format(
        filename.ifBlank { "（无文件名 #$handle）" },
        typeName(),
        info.sizeMiB()
    )
}

/**
 * 相机胶卷枚举结果。
 *
 * 刻意保留「扫了多少个句柄 / 花了多久 / 有没有被截断」这些诊断字段：
 * 相机的对象表可能上万条，一次 `GetObjectInfo` 是一条 USB 事务，
 * 全量遍历会慢到不可接受，所以只查**最新的若干条**，并把这件事如实写出来。
 */
data class CameraPhotoList(
    /** 相机自报的对象总数（含目录等非照片对象）。 */
    val totalHandles: Int,
    /** 实际发起 `GetObjectInfo` 查询的条数。 */
    val inspected: Int,
    /** 照片列表，**新 → 旧**（UI 第 0 个即最新）。 */
    val photos: List<CameraPhoto>,
    val elapsedMs: Long,
    val notices: List<String>
) {
    val rawCount: Int get() = photos.count { it.isRaw }

    /** 是否只看了尾部一段（对象总数多于实际检查条数）。 */
    val truncated: Boolean get() = totalHandles > inspected

    fun summaryLines(): List<String> {
        val out = ArrayList<String>()
        out.add(
            "共 ${totalHandles} 个对象，检查最新 $inspected 个 → 识别出 ${photos.size} 张照片" +
                "（RAW ${rawCount} / 其他 ${photos.size - rawCount}），耗时 $elapsedMs ms"
        )
        if (truncated) out.add("提示：对象数多于检查条数，列表只覆盖最新的一段。")
        for (notice in notices) out.add("提示：$notice")
        return out
    }
}

/** 照片判定：优先看后缀，后缀缺失时退回 PTP 格式码。 */
object CameraPhotoFilter {

    val PHOTO_EXTENSIONS = setOf("arw", "dng", "jpg", "jpeg", "heic", "hif", "tif", "tiff", "png")

    fun isImageFormat(format: Int): Boolean = when (format) {
        PtpProtocol.FORMAT_EXIF_JPEG,
        PtpProtocol.FORMAT_TIFF,
        PtpProtocol.FORMAT_DNG,
        PtpProtocol.FORMAT_HEIF,
        PtpProtocol.FORMAT_ARW -> true
        else -> false
    }

    fun isPhoto(info: PtpObjectInfo): Boolean {
        if (info.extension in PHOTO_EXTENSIONS) return true
        // 少数实现不给文件名：退回格式码，避免把真实照片漏掉
        return info.filename.isBlank() && isImageFormat(info.format)
    }
}
