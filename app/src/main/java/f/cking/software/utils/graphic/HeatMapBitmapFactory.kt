package f.cking.software.utils.graphic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.Location
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object HeatMapBitmapFactory {

    data class Position(val location: Location, val radiusMeters: Float)
    data class Tile(val topLeft: Location, val bottomRight: Location)

    /**
     * Generates a heatmap-like bitmap for the given tile.
     *
     * Colors:
     *  - Red at center
     *  - Green at radius edge
     *  - Transparent outside
     *
     * @param positions points with radius in meters
     * @param tile geographic bounds (topLeft has bigger lat, smaller lng)
     * @param widthPx desired bitmap width in pixels
     * @param additiveBlend if true, overlaps brighten via ADD; else normal SRC_OVER alpha
     */
    suspend fun generateTileGradientBitmap(
        positions: List<Position>,
        tile: Tile,
        widthPx: Int,
        additiveBlend: Boolean = false
    ): Bitmap {
        require(widthPx > 0)

        // --- Simple equirectangular meters-per-degree approximation ---
        fun metersPerDegLat(): Double = 111_320.0
        fun metersPerDegLng(atLatDeg: Double): Double =
            111_320.0 * cos(Math.toRadians(atLatDeg))

        val latTop = tile.topLeft.latitude
        val latBottom = tile.bottomRight.latitude
        val lngLeft = tile.topLeft.longitude
        val lngRight = tile.bottomRight.longitude

        val tileCenterLat = (latTop + latBottom) / 2.0
        val mPerDegLat = metersPerDegLat()
        val mPerDegLng = metersPerDegLng(tileCenterLat)

        val tileWidthMeters = abs(lngRight - lngLeft) * mPerDegLng
        val tileHeightMeters = abs(latTop - latBottom) * mPerDegLat

        if (tileWidthMeters <= 0.0 || tileHeightMeters <= 0.0) {
            return createBitmap(widthPx, widthPx)
        }

        val heightPx = max(1, round(widthPx * (tileHeightMeters / tileWidthMeters)).toInt())

        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val pxPerMeterX = widthPx / tileWidthMeters
        val pxPerMeterY = heightPx / tileHeightMeters
        val pxPerMeter = min(pxPerMeterX, pxPerMeterY) // keep circles circular

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            isDither = true
        }
        if (additiveBlend) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        fun latLngToPixel(lat: Double, lng: Double): PointF {
            val xNorm = (lng - lngLeft) / (lngRight - lngLeft)
            val yNorm = (latTop - lat) / (latTop - latBottom) // top->0, bottom->1
            return PointF(
                (xNorm * widthPx).toFloat(),
                (yNorm * heightPx).toFloat()
            )
        }

        val red = Color.argb(255, 255, 0, 0)
        val green = Color.argb(255, 0, 255, 0)
        val transparent = Color.TRANSPARENT

        for (pos in positions) {
            val loc = pos.location
            val (px, py) = latLngToPixel(loc.latitude, loc.longitude)
            val radiusPx = max(1f, pos.radiusMeters * pxPerMeter.toFloat())

            // Cheap cull if fully outside
            if (px + radiusPx < 0 || px - radiusPx > widthPx ||
                py + radiusPx < 0 || py - radiusPx > heightPx
            ) continue

            paint.shader = RadialGradient(
                px, py, radiusPx,
                intArrayOf(red, green, transparent),
                floatArrayOf(0f, 0.98f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, radiusPx, paint)
        }

        paint.shader = null
        paint.xfermode = null

        return bitmap
    }

    /**
     * Computes a minimal Tile that contains all locations plus a padding margin in meters.
     *
     * Uses local meters-per-degree approximations. Suitable for typical map region sizes.
     */
    fun computeBoundingTile(
        points: List<Location>,
        paddingMeters: Double
    ): Tile {
        require(points.isNotEmpty())

        // Extract numeric bounds
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY

        for (p in points) {
            val lat = p.latitude
            val lng = p.longitude
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lng < minLng) minLng = lng
            if (lng > maxLng) maxLng = lng
        }

        // Approximate meters per degree using center latitude
        val centerLat = (minLat + maxLat) / 2.0
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(centerLat))

        // Convert meter padding → degree padding
        val padLat = paddingMeters / mPerDegLat
        val padLng = paddingMeters / mPerDegLng

        val paddedMinLat = minLat - padLat
        val paddedMaxLat = maxLat + padLat
        val paddedMinLng = minLng - padLng
        val paddedMaxLng = maxLng + padLng

        fun loc(lat: Double, lng: Double): Location =
            Location("bounding").apply {
                latitude = lat
                longitude = lng
            }

        val topLeft = loc(paddedMaxLat, paddedMinLng)
        val bottomRight = loc(paddedMinLat, paddedMaxLng)

        return Tile(topLeft, bottomRight)
    }
}