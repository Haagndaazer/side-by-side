package com.example.sbsconverter.model

data class ProcessingConfig(
    val depthScale: Float = 2f,
    val depthBlurKernel: Int = 3,
    val depthGamma: Float = 1f,
    val convergencePoint: Float = 1f,
    val surfaceDetail: Float = 0.5f,
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
}

enum class Arrangement { PARALLEL, CROSS_EYED }
