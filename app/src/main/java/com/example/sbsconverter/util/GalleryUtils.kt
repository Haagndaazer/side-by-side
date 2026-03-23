package com.example.sbsconverter.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                val exif = ExifInterface(stream)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: return@use null

                val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
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
            // Step 1: Write JPEG data
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }

            // Step 2: Write EXIF date tags into the JPEG BEFORE clearing IS_PENDING.
            // The MediaStore scanner runs when IS_PENDING flips to 0 and overwrites
            // DATE_TAKEN from the JPEG's EXIF DateTimeOriginal. Without EXIF tags,
            // the scanner defaults to "now".
            if (dateTakenMs != null) {
                val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .format(Date(dateTakenMs))
                val tzOffset = SimpleDateFormat("XXX", Locale.US)
                    .format(Date(dateTakenMs))

                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
                    exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
                    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, tzOffset)
                    // Workaround for Android bug where DATE_TAKEN returns null
                    // without SubSecDateTimeOriginal (Issue #385996093)
                    exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "000")
                    exif.saveAttributes()
                }
            }

            // Step 3: Clear IS_PENDING and re-assert date values.
            // Setting dates in the same update() that clears IS_PENDING allows
            // updating otherwise-immutable columns.
            val updateValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                if (dateTakenMs != null) {
                    put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
                    put(MediaStore.Images.Media.DATE_ADDED, dateTakenMs / 1000)
                    put(MediaStore.Images.Media.DATE_MODIFIED, dateTakenMs / 1000)
                }
            }
            resolver.update(uri, updateValues, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
