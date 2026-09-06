package com.khwlab.hub.feature.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/** 截图存相册:通过 MediaStore 写入 Pictures/LabHub(免存储权限,API 29+)。 */
object ScreenshotSaver {

    /** 返回最终 Uri,失败抛异常。调用方保证 API 28- 的 WRITE_EXTERNAL_STORAGE 已授权。 */
    fun save(context: Context, bitmap: Bitmap, displayName: String = "khwlab_capture.jpg"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LabHub")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("创建 MediaStore 条目失败")
        } else {
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IllegalStateException("创建 MediaStore 条目失败")
        }
        try {
            resolver.openOutputStream(uri).use { out ->
                out ?: throw IllegalStateException("无法写入输出流")
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
