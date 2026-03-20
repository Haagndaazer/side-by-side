package com.example.sbsconverter.model

data class ProcessingConfig(
    val depthScale: Float = 5f,
    val depthBlurKernel: Int = 3,
    val depthGamma: Float = 1f,
    val convergencePoint: Float = 0.5f,
    val arrangement: Arrangement = Arrangement.CROSS_EYED
)

enum class Arrangement { PARALLEL, CROSS_EYED }
