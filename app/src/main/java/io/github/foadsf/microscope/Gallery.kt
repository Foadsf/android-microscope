package io.github.foadsf.microscope

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What is known about a capture, written to EXIF so the scale travels with the picture. */
data class CaptureMeta(
    val description: String,
    val focalMm: Float,
    val fNumber: Float?,
    val iso: Int?,
    val exposureNs: Long?,
    /** Specimen size of one output pixel, if the lens reports metric focus distances. */
    val umPerPixel: Float?,
)

object Gallery {
    private const val FOLDER = "Microscope"

    /** A file-name stem for one capture, shared by everything saved from it. */
    fun stem(now: Date = Date()): String = "MICRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

    /** Saves [bitmap] as [stem].jpg in Pictures/Microscope via MediaStore and returns its URI. */
    fun save(context: Context, bitmap: Bitmap, meta: CaptureMeta, stem: String = stem()): Uri {
        val now = Date()
        val name = "$stem.jpg"

        // ExifInterface edits files in place, so build the JPEG in the app cache first.
        val tmp = File(context.cacheDir, "pending.jpg")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(tmp.absolutePath).apply {
            val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            setAttribute(ExifInterface.TAG_DATETIME, stamp)
            setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            setAttribute(ExifInterface.TAG_SOFTWARE, "Microscope ${BuildConfigCompat.versionName(context)}")
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.description)
            setAttribute(ExifInterface.TAG_USER_COMMENT, meta.description)
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(meta.focalMm * 1000).toInt()}/1000")
            meta.fNumber?.let { setAttribute(ExifInterface.TAG_F_NUMBER, "${(it * 100).toInt()}/100") }
            meta.iso?.let { setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
            meta.exposureNs?.let { setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
            meta.umPerPixel?.let {
                // Pixels per centimetre on the specimen, the standard EXIF way to carry a scale.
                val perCm = 10_000.0 / it
                setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "3")
                setAttribute(ExifInterface.TAG_X_RESOLUTION, "${(perCm * 100).toLong()}/100")
                setAttribute(ExifInterface.TAG_Y_RESOLUTION, "${(perCm * 100).toLong()}/100")
            }
            saveAttributes()
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused the new image")
        try {
            resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                ?: error("Cannot write the image")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            tmp.delete()
        }
        return uri
    }

    /** Saves [bytes] as Documents/Microscope/[name] (for data files such as point clouds). */
    fun saveDocument(context: Context, name: String, mime: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: error("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}

private object BuildConfigCompat {
    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
