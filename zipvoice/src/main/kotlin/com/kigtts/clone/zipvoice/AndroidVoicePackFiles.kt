package com.kigtts.clone.zipvoice

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal object AndroidVoicePackFiles {
    fun normalizeAvatar(source: File, destination: File) {
        require(source.isFile && source.length() in 1..8L * 1024 * 1024) { "头像图片不能超过 8 MB" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.path,bounds)
        require(bounds.outMimeType in setOf("image/png","image/jpeg","image/webp") &&
            bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "请选择有效的 PNG、JPEG 或 WebP 图片" }
        val longest=max(bounds.outWidth,bounds.outHeight)
        var sample=1
        while (longest/sample>512) sample *= 2
        val image=BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply { inSampleSize=sample })
            ?: error("无法读取头像")
        try {
            val scale=minOf(1f,256f/max(image.width,image.height))
            val scaled=Bitmap.createScaledBitmap(image,(image.width*scale).roundToInt().coerceAtLeast(1),
                (image.height*scale).roundToInt().coerceAtLeast(1),true)
            try { destination.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { if(scaled!==image) scaled.recycle() }
        } finally { image.recycle() }
    }

    /** User-selected share, using an app-owned Downloads URI (not a private host storage path). */
    fun share(context: Context, source: File, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        require(source.isFile && source.length() in 1..6L * 1024 * 1024)
        val resolver = context.contentResolver
        val uri=runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/KIGTTS")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }) }.getOrNull() ?: return false
        try {
            requireNotNull(resolver.openOutputStream(uri,"w")).use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            check(resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)==1)
            val send=Intent(Intent.ACTION_SEND).apply {
                type="application/zip"
                putExtra(Intent.EXTRA_STREAM,uri)
                clipData=ClipData.newUri(resolver,name,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send,"分享语音包")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri,null,null) }
            return false
        }
    }
}
