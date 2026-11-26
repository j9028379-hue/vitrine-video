package com.vitrinevideo.engine.video

/**
 * Pure CPU-side geometry for single-source multi-cell layouts.
 *
 * Each grid cell receives a normalized `0..1` UV inside that cell. To keep the
 * source aspect ratio without distortion, the sampled source span must match
 * the cell aspect ratio:
 *
 * - `rows > columns` (stacked cells, e.g. T4 `double` 2x1): sample a full-width,
 *   half-height region -> span `(1, columns / rows)`.
 * - `columns > rows` (side-by-side cells): span `(rows / columns, 1)`.
 * - `rows == columns` (e.g. T3 2x2 grid): span `(1, 1)`, unchanged.
 *
 * Keeping this on the CPU makes the uniform value directly unit-testable and
 * prevents GLSL integer/inversion mistakes from silently changing the crop.
 */
object GridCellGeometry {
    fun samplingSpan(rows: Int, columns: Int): Pair<Float, Float> {
        require(rows > 0) { "rows must be positive" }
        require(columns > 0) { "columns must be positive" }
        return if (rows > columns) {
            1.0f to columns.toFloat() / rows.toFloat()
        } else {
            rows.toFloat() / columns.toFloat() to 1.0f
        }
    }
}
