package com.hym.pointer

/** No default PIN, coordinates, or values in error messages. */
internal data class PinCoordinates(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val taps: List<Point>
) {
    data class Point(val x: Int, val y: Int)
    companion object {
        // Format: left,top,right,bottom;x,y;x,y;x,y;x,y;x,y;x,y
        fun parse(raw: String, width: Int, height: Int): PinCoordinates {
            val groups = raw.split(';').map { part -> part.split(',').map { it.toIntOrNull() } }
            require(groups.size == 7 && groups[0].size == 4 &&
                groups.drop(1).all { it.size == 2 } && groups.flatten().all { it != null }) {
                "Invalid PIN configuration format"
            }
            val bounds = groups[0].map { requireNotNull(it) }
            val taps = groups.drop(1).map { Point(requireNotNull(it[0]), requireNotNull(it[1])) }
            val (left, top, right, bottom) = bounds
            require(left >= 0 && top >= 0 && right <= width && bottom <= height &&
                right > left && bottom > top && taps.all {
                    it.x >= left && it.x < right && it.y >= top && it.y < bottom
                }) { "PIN coordinates must be inside the configured keyboard and display" }
            return PinCoordinates(left, top, right, bottom, taps)
        }
    }
}
