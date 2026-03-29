package com.example.sbsconverter.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
        dateTakenMs: Long? = null,
        isUltraHdr: Boolean = false
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
                if (!isUltraHdr) {
                    // Non-HDR: use ExifInterface (rewrites file, safe for plain JPEG)
                    writeExifDates(resolver, uri, dateTakenMs)
                } else {
                    // Ultra HDR: binary inject EXIF to preserve post-EOI gainmap.
                    // ExifInterface.saveAttributes() rewrites the file and strips
                    // the gainmap secondary image that lives after the EOI marker.
                    injectExifDateBinary(resolver, uri, dateTakenMs)
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

    private fun writeExifDates(resolver: ContentResolver, uri: Uri, dateTakenMs: Long) {
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

    /**
     * Inject EXIF date tags into a JPEG_R file via binary manipulation.
     * Unlike ExifInterface.saveAttributes(), this preserves post-EOI trailing data
     * where the Ultra HDR gainmap secondary image lives.
     *
     * Inserts a minimal EXIF APP1 segment after the SOI marker. Having both an
     * EXIF APP1 ("Exif\0\0") and an XMP APP1 (XMP namespace) is standard JPEG
     * convention — parsers distinguish them by header bytes.
     */
    private fun injectExifDateBinary(resolver: ContentResolver, uri: Uri, dateTakenMs: Long) {
        try {
            val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (original.size < 4 || original[0] != 0xFF.toByte() || original[1] != 0xD8.toByte()) return

            val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .format(Date(dateTakenMs))
            val tzOffset = SimpleDateFormat("XXX", Locale.US)
                .format(Date(dateTakenMs))

            val app1 = buildExifApp1(exifDate, tzOffset)

            // Find insertion point: after SOI, skip any APP0/JFIF segment
            var insertPos = 2
            if (original.size > 4 && original[2] == 0xFF.toByte() && original[3] == 0xE0.toByte()) {
                // APP0 present — read its length and skip past it
                val app0Len = ((original[4].toInt() and 0xFF) shl 8) or (original[5].toInt() and 0xFF)
                insertPos = 4 + app0Len
            }

            val result = ByteArrayOutputStream(original.size + app1.size)
            result.write(original, 0, insertPos)
            result.write(app1)
            result.write(original, insertPos, original.size - insertPos)

            resolver.openOutputStream(uri, "wt")?.use { it.write(result.toByteArray()) }
        } catch (e: Exception) {
            Log.w("GalleryUtils", "Binary EXIF injection failed: ${e.message}")
        }
    }

    /**
     * Build a minimal EXIF APP1 segment containing date tags.
     * Structure: APP1 marker + "Exif\0\0" + TIFF header + IFD0 + ExifIFD + string data.
     * Uses big-endian (Motorola) byte order.
     */
    private fun buildExifApp1(dateTime: String, tzOffset: String): ByteArray {
        // Date strings: "yyyy:MM:dd HH:mm:ss\0" = 20 bytes each
        val dateBytes = (dateTime + "\u0000").toByteArray(Charsets.US_ASCII)     // 20 bytes
        val subSecBytes = ("000\u0000").toByteArray(Charsets.US_ASCII)           // 4 bytes
        val tzBytes = (tzOffset + "\u0000").toByteArray(Charsets.US_ASCII)       // 7 bytes typically

        // IFD0: 2 entries (DateTime + ExifIFD pointer)
        // ExifIFD: 4 entries (DateTimeOriginal, DateTimeDigitized, SubSecTimeOriginal, OffsetTimeOriginal)
        val ifd0EntryCount = 2
        val exifIfdEntryCount = 4
        val ifd0Size = 2 + ifd0EntryCount * 12 + 4  // count(2) + entries + next_ifd_offset(4)
        val exifIfdSize = 2 + exifIfdEntryCount * 12 + 4

        // Offsets relative to TIFF header start (byte 0 of TIFF = 'M' or 'I')
        val tiffHeaderSize = 8  // byte order(2) + magic(2) + ifd0_offset(4)
        val ifd0Offset = tiffHeaderSize
        val exifIfdOffset = ifd0Offset + ifd0Size
        val dataAreaOffset = exifIfdOffset + exifIfdSize

        // String data offsets (from TIFF start)
        val dateTimeOffset = dataAreaOffset
        val dateTimeOrigOffset = dateTimeOffset + dateBytes.size
        val dateTimeDigOffset = dateTimeOrigOffset + dateBytes.size
        val subSecOffset = dateTimeDigOffset + dateBytes.size
        val tzOffsetPos = subSecOffset + subSecBytes.size

        val totalDataSize = dateBytes.size * 3 + subSecBytes.size + tzBytes.size
        val tiffSize = dataAreaOffset + totalDataSize

        // Build TIFF content (big-endian)
        val tiff = ByteBuffer.allocate(tiffSize)
        // TIFF header
        tiff.put(0x4D.toByte()); tiff.put(0x4D.toByte())  // "MM" = big-endian
        tiff.putShort(42)                                    // Magic
        tiff.putInt(ifd0Offset)                              // Offset to IFD0

        // IFD0
        tiff.putShort(ifd0EntryCount.toShort())
        // Entry: DateTime (0x0132), ASCII, count=20, offset
        writeIfdEntry(tiff, 0x0132, 2, dateBytes.size, dateTimeOffset)
        // Entry: ExifIFD pointer (0x8769), LONG, count=1, value=offset
        writeIfdEntry(tiff, 0x8769, 4, 1, exifIfdOffset)
        tiff.putInt(0) // Next IFD offset (0 = no more IFDs)

        // ExifIFD
        tiff.putShort(exifIfdEntryCount.toShort())
        // DateTimeOriginal (0x9003)
        writeIfdEntry(tiff, 0x9003, 2, dateBytes.size, dateTimeOrigOffset)
        // DateTimeDigitized (0x9004)
        writeIfdEntry(tiff, 0x9004, 2, dateBytes.size, dateTimeDigOffset)
        // SubSecTimeOriginal (0x9291)
        writeIfdEntry(tiff, 0x9291, 2, subSecBytes.size, subSecOffset)
        // OffsetTimeOriginal (0x9011)
        writeIfdEntry(tiff, 0x9011, 2, tzBytes.size, tzOffsetPos)
        tiff.putInt(0) // Next IFD offset

        // String data area
        tiff.put(dateBytes)       // DateTime value
        tiff.put(dateBytes)       // DateTimeOriginal value
        tiff.put(dateBytes)       // DateTimeDigitized value
        tiff.put(subSecBytes)     // SubSecTimeOriginal value
        tiff.put(tzBytes)         // OffsetTimeOriginal value

        // Wrap in APP1 segment: FFE1 + length + "Exif\0\0" + TIFF
        val exifHeader = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)  // 6 bytes
        val app1DataLen = exifHeader.size + tiffSize
        val app1 = ByteBuffer.allocate(2 + 2 + app1DataLen)  // marker(2) + length(2) + data
        app1.put(0xFF.toByte()); app1.put(0xE1.toByte())     // APP1 marker
        app1.putShort((app1DataLen + 2).toShort())            // Length (includes length field itself)
        app1.put(exifHeader)
        app1.put(tiff.array())

        return app1.array()
    }

    /** Write a single 12-byte IFD entry in big-endian format. */
    private fun writeIfdEntry(buf: ByteBuffer, tag: Int, type: Int, count: Int, valueOrOffset: Int) {
        buf.putShort(tag.toShort())
        buf.putShort(type.toShort())
        buf.putInt(count)
        buf.putInt(valueOrOffset)
    }
}
