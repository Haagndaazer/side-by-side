package com.example.sbsconverter.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GalleryUtils {

    /**
     * Extract the date taken from a source image URI via EXIF data so the
     * exported SBS appears next to the original in the user's album.
     * Uses EXIF rather than MediaStore queries because the photo picker
     * returns content URIs that don't support MediaStore column queries.
     */
    fun getDateTaken(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                // Try DATETIME_ORIGINAL first, then DATETIME
                val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                    ?: return@use null

                // EXIF date format: "yyyy:MM:dd HH:mm:ss"
                val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                sdf.parse(dateStr)?.time
            }
        } catch (_: Exception) { null }
    }

    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String = "SBS_${System.currentTimeMillis()}",
        dateTakenMs: Long? = null
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SBS Converter")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (dateTakenMs != null) {
                put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
                put(MediaStore.Images.Media.DATE_ADDED, dateTakenMs / 1000)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@withContext null

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
