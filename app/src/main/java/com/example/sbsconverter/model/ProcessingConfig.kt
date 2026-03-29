package com.example.sbsconverter.model

data class ProcessingConfig(
    val depthScale: Float = 0.1f,
    val depthBlurKernel: Int = 3,
    val convergencePoint: Float = 1f,
    val arrangement: Arrangement = Arrangement.CROSS_EYED
) {
    val smoothingLabel: String
        get() = when {
            depthBlurKernel <= 1 -> "Off"
            depthBlurKernel <= 5 -> "Low"
            depthBlurKernel <= 11 -> "Medium"
            depthBlurKernel <= 21 -> "High"
            else -> "Maximum"
        }

    /** Bilateral filter spatial sigma mapped from depthBlurKernel (3-33) → (3-14). */
    val bilateralSpatialSigma: Float
        get() = 3f + (depthBlurKernel.coerceIn(3, 33) - 3) * (11f / 30f)

    companion object {
        /** Fixed range sigma — preserves edges with >~12% depth difference. */
        const val BILATERAL_RANGE_SIGMA = 0.06f
    }
}

enum class Arrangement { PARALLEL, CROSS_EYED }
