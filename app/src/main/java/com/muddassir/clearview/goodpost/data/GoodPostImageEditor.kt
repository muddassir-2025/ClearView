package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editing, done on the device before anything is uploaded (§ media).
 *
 * Three decisions shape this file, and each one is about the server:
 *
 *  1. **The edit happens locally.** Cropping, rotating and filtering a photo
 *     takes milliseconds here and would take a round trip, an upload of the
 *     original, a server-side transform that has to be trusted, and a second
 *     copy in storage there. None of that is needed, so none of it happens: the
 *     server only ever sees the finished file.
 *  2. **One decode, two renders.** The preview decodes the picture once at a
 *     bounded size and draws it through a colour matrix; the export renders the
 *     SAME matrix at full size. A preview that is computed differently from the
 *     export is how a filter ends up looking different in the post than it did
 *     in the editor.
 *  3. **Nothing is kept.** The edited file is written into the app's cache,
 *     which Android may reclaim, so an abandoned edit leaves no trace and there
 *     is no gallery to clean up.
 */

/** The crop shapes offered. `ratio` is width ÷ height; null means "as it is". */
enum class EditAspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    Square("Square", 1f),
    Portrait("Portrait", 4f / 5f),
    Wide("Wide", 16f / 9f),
    Story("Story", 9f / 16f)
}

/**
 * The filters.
 *
 * Each is a `ColorMatrix` built below, chosen to be visibly different from one
 * another at a glance — five filters that all look like "slightly warmer" are
 * five ways to waste a tap.
 */
enum class EditFilter(val label: String) {
    None("Original"),
    Dream("Dream"),
    Warm("Warm"),
    Cool("Cool"),
    Fade("Fade"),
    Mono("Mono")
}

/**
 * One editing session's state.
 *
 * A data class rather than a set of mutable fields so the export and the preview
 * are two calls with the same argument, and so "what will be saved" is a value
 * that can be unit-tested without a device.
 */
data class EditState(
    /** 0–3 quarter turns clockwise. */
    val turns: Int = 0,
    val flipHorizontal: Boolean = false,
    val aspect: EditAspect = EditAspect.Original,
    val filter: EditFilter = EditFilter.None,
    /** 1.0 is untouched for all three of the adjustments below. */
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
) {
    val isPristine: Boolean
        get() = turns % 4 == 0 &&
            !flipHorizontal &&
            aspect == EditAspect.Original &&
            filter == EditFilter.None &&
            brightness == 1f &&
            contrast == 1f &&
            saturation == 1f
}

/**
 * The colour transform for a state.
 *
 * Built in the same order the eye reads it: the filter's own character first,
 * then the three sliders the user moved. Applying them the other way round makes
 * a slider feel like it is fighting the filter, which it would be.
 */
internal fun colorMatrixFor(state: EditState): ColorMatrix {
    val matrix = ColorMatrix()

    when (state.filter) {
        EditFilter.None -> Unit

        // Dream: lifted, slightly cyan. The "soft Instagram" look, and the one
        // filter that pairs with the app's own gradient backdrop.
        EditFilter.Dream -> {
            matrix.setSaturation(0.95f)
            matrix.postConcat(brightnessMatrix(1.06f))
            matrix.postConcat(tintMatrix(red = -12f, green = 4f, blue = 16f))
        }

        EditFilter.Warm -> {
            matrix.setSaturation(1.12f)
            matrix.postConcat(tintMatrix(red = 18f, green = 6f, blue = -14f))
        }

        EditFilter.Cool -> {
            matrix.setSaturation(1.05f)
            matrix.postConcat(tintMatrix(red = -14f, green = 2f, blue = 20f))
        }

        // Fade: less contrast, less colour, lifted blacks. The film look.
        EditFilter.Fade -> {
            matrix.setSaturation(0.78f)
            matrix.postConcat(contrastMatrix(0.88f))
            matrix.postConcat(brightnessMatrix(1.05f))
        }

        EditFilter.Mono -> matrix.setSaturation(0f)
    }

    if (state.saturation != 1f) matrix.postConcat(saturationMatrix(state.saturation))
    if (state.brightness != 1f) matrix.postConcat(brightnessMatrix(state.brightness))
    if (state.contrast != 1f) matrix.postConcat(contrastMatrix(state.contrast))

    return matrix
}

private fun brightnessMatrix(scale: Float) = ColorMatrix(
    floatArrayOf(
        scale, 0f, 0f, 0f, 0f,
        0f, scale, 0f, 0f, 0f,
        0f, 0f, scale, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

private fun saturationMatrix(scale: Float) = ColorMatrix().apply { setSaturation(scale) }

private fun contrastMatrix(scale: Float) = ColorMatrix(
    floatArrayOf(
        scale, 0f, 0f, 0f, 128f * (1f - scale),
        0f, scale, 0f, 0f, 128f * (1f - scale),
        0f, 0f, scale, 0f, 128f * (1f - scale),
        0f, 0f, 0f, 1f, 0f
    )
)

/** A per-channel offset. Values are 0–255 scale, so ±20 is a gentle shift. */
private fun tintMatrix(red: Float, green: Float, blue: Float) = ColorMatrix(
    floatArrayOf(
        1f, 0f, 0f, 0f, red,
        0f, 1f, 0f, 0f, green,
        0f, 0f, 1f, 0f, blue,
        0f, 0f, 0f, 1f, 0f
    )
)

/**
 * A picture, decoded once for the editor to work on.
 *
 * Bounded on the way in: a 12-megapixel photo decoded at full size is ~48MB of
 * heap, and the editor only ever shows or exports something a screen can hold.
 * [MAX_EDGE] is generous for a phone camera and keeps the whole session inside
 * a normal heap.
 */
class EditableBitmap private constructor(
    val bitmap: Bitmap,
    /** EXIF rotation already applied, so no caller has to think about it. */
    val width: Int,
    val height: Int
) {
    companion object {
        /** Longest edge kept after decoding. */
        const val MAX_EDGE = 2048

        /** High enough that a re-encode is invisible at a phone's own zoom. */
        private const val QUALITY = 92

        /** Decode a picked image, downscaled and upright. Null if unreadable. */
        fun decode(context: Context, uri: Uri): EditableBitmap? {
            return try {
                // Two passes: bounds first, so the scale factor is known before
                // a single pixel is allocated.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return null

                // EXIF: a photo taken in portrait is stored landscape with a
                // rotation tag, and ignoring it is why edited images end up
                // sideways in half the apps that edit them.
                val upright = applyExif(context, uri, decoded)
                EditableBitmap(upright, upright.width, upright.height)
            } catch (_: Exception) {
                // Out of memory, a revoked URI, an unsupported format — all of
                // them are "this file cannot be edited", which the composer
                // reports rather than crashing on.
                null
            }
        }

        private fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            var longest = max(width, height)
            while (longest / 2 >= MAX_EDGE) {
                longest /= 2
                sample *= 2
            }
            return sample
        }

        private fun applyExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.media.ExifInterface(stream).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                android.media.ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }

            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            return rotated
        }
    }

    /**
     * Render a state to a JPEG in the cache directory.
     *
     * Returns null when the picture cannot be written, which the composer shows
     * as an error rather than pretending the attachment exists.
     */
    fun render(context: Context, state: EditState, name: String = "goodpost-edit"): File? {
        return try {
            val cropped = cropNormally(state)
            val rotated = rotate(cropped, state)
            val filtered = applyColor(rotated, state)

            val file = File(context.cacheDir, "$name-${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                filtered.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }

            if (filtered != bitmap) filtered.recycle()
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The centre crop for a ratio, clamped to the picture.
     *
     * Centred rather than free-form: a drag-to-crop rectangle needs handles, a
     * gesture that fights the editor's own scroll, and a live preview of what is
     * being cut off — all for a crop that nine times out of ten means "centre it
     * on the subject".
     *
     * Measured against the source's own coordinates and applied BEFORE rotation,
     * so a ratio is never expressed in rotated axes — which is exactly where
     * landscape and portrait crops get swapped.
     */
    private fun cropNormally(state: EditState): Bitmap {
        val ratio = state.aspect.ratio ?: return bitmap
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        var cropWidth = width
        var cropHeight = width / ratio
        if (cropHeight > height) {
            cropHeight = height
            cropWidth = height * ratio
        }

        val left = ((width - cropWidth) / 2f).roundToInt().coerceAtLeast(0)
        val top = ((height - cropHeight) / 2f).roundToInt().coerceAtLeast(0)
        val w = cropWidth.roundToInt().coerceAtMost(bitmap.width - left)
        val h = cropHeight.roundToInt().coerceAtMost(bitmap.height - top)
        if (w <= 0 || h <= 0) return bitmap

        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun rotate(source: Bitmap, state: EditState): Bitmap {
        val degrees = (state.turns % 4) * 90f
        if (degrees == 0f && !state.flipHorizontal) return source

        val matrix = Matrix().apply {
            postRotate(degrees)
            if (state.flipHorizontal) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyColor(source: Bitmap, state: EditState): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrixFor(state))
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
    }
}

/** An upload request built from an edited file rather than a picked URI. */
internal fun uploadRequestForFile(file: File): MediaUploadRequest = MediaUploadRequest(
    contentType = "image/jpeg",
    byteSize = file.length(),
    open = { file.inputStream() }
)
