package com.watermonitor.app.data.ml

import kotlin.math.abs

/**
 * Ridge-regularised ordinary least squares for small, fixed-width designs.
 *
 * Solves the normal equations `(XᵀX + λI)b = Xᵀy` by Gaussian elimination with partial
 * pivoting. Ridge is not decoration here: TDS and turbidity move together in practice
 * (dirty water is dirty on both axes), so `XᵀX` is close to singular and plain OLS divides
 * by roughly zero and returns NaN coefficients — which would then propagate silently into
 * every health percentage in the app.
 *
 * Pure Kotlin, no Android dependencies, so this is unit-testable in isolation.
 */
object RidgeRegression {

    private const val DEFAULT_LAMBDA = 1e-2
    private const val PIVOT_EPSILON = 1e-12

    /**
     * Fits `y ≈ Xb`.
     *
     * @param design rows of features. Each row must already include the leading 1.0 for
     *   the intercept term, and every row must be the same length.
     * @param targets one target per design row.
     * @return the fitted coefficients, or null if the fit is degenerate — too few rows, a
     *   singular matrix, or any non-finite result. Callers must keep their prior on null
     *   rather than substituting zeros.
     */
    fun fit(
        design: List<DoubleArray>,
        targets: List<Double>,
        lambda: Double = DEFAULT_LAMBDA
    ): DoubleArray? {
        if (design.isEmpty() || design.size != targets.size) return null

        val width = design[0].size
        if (width == 0) return null
        if (design.any { it.size != width }) return null
        // Ridge tolerates n < p, but a fit from fewer rows than features is noise.
        if (design.size < width) return null
        if (design.any { row -> row.any { !it.isFinite() } }) return null
        if (targets.any { !it.isFinite() }) return null

        // XtX + lambda*I, and Xty.
        val xtx = Array(width) { DoubleArray(width) }
        val xty = DoubleArray(width)
        for (r in design.indices) {
            val row = design[r]
            val target = targets[r]
            for (i in 0 until width) {
                xty[i] += row[i] * target
                for (j in 0 until width) {
                    xtx[i][j] += row[i] * row[j]
                }
            }
        }
        for (i in 0 until width) {
            xtx[i][i] += lambda
        }

        return solve(xtx, xty)
    }

    /**
     * Coefficient of determination for a fitted model. Returns 0.0 rather than NaN when the
     * targets have no variance, so it is always safe to display.
     */
    fun rSquared(
        design: List<DoubleArray>,
        targets: List<Double>,
        coefficients: DoubleArray
    ): Double {
        if (design.isEmpty() || design.size != targets.size) return 0.0

        val mean = targets.average()
        if (!mean.isFinite()) return 0.0

        var residualSS = 0.0
        var totalSS = 0.0
        for (r in design.indices) {
            val predicted = predict(design[r], coefficients)
            if (!predicted.isFinite()) return 0.0
            val residual = targets[r] - predicted
            residualSS += residual * residual
            val deviation = targets[r] - mean
            totalSS += deviation * deviation
        }

        if (totalSS < PIVOT_EPSILON) return 0.0
        val r2 = 1.0 - residualSS / totalSS
        return if (r2.isFinite()) r2.coerceIn(0.0, 1.0) else 0.0
    }

    /** Dot product of a feature row with fitted coefficients. */
    fun predict(features: DoubleArray, coefficients: DoubleArray): Double {
        if (features.size != coefficients.size) return Double.NaN
        var sum = 0.0
        for (i in features.indices) {
            sum += features[i] * coefficients[i]
        }
        return sum
    }

    /**
     * Gaussian elimination with partial pivoting. Returns null on a near-zero pivot
     * (singular system) or any non-finite result.
     */
    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        // Work on copies; callers may reuse the inputs.
        val a = Array(n) { matrix[it].copyOf() }
        val b = rhs.copyOf()

        for (col in 0 until n) {
            // Partial pivoting: swap in the row with the largest magnitude in this column.
            var pivotRow = col
            var pivotMagnitude = abs(a[col][col])
            for (r in col + 1 until n) {
                val magnitude = abs(a[r][col])
                if (magnitude > pivotMagnitude) {
                    pivotMagnitude = magnitude
                    pivotRow = r
                }
            }
            if (pivotMagnitude < PIVOT_EPSILON) return null

            if (pivotRow != col) {
                val swapRow = a[pivotRow]
                a[pivotRow] = a[col]
                a[col] = swapRow
                val swapValue = b[pivotRow]
                b[pivotRow] = b[col]
                b[col] = swapValue
            }

            val pivot = a[col][col]
            for (r in col + 1 until n) {
                val factor = a[r][col] / pivot
                if (factor == 0.0) continue
                for (c in col until n) {
                    a[r][c] -= factor * a[col][c]
                }
                b[r] -= factor * b[col]
            }
        }

        // Back substitution.
        val solution = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = b[row]
            for (c in row + 1 until n) {
                sum -= a[row][c] * solution[c]
            }
            val pivot = a[row][row]
            if (abs(pivot) < PIVOT_EPSILON) return null
            solution[row] = sum / pivot
        }

        if (solution.any { !it.isFinite() }) return null
        return solution
    }
}
