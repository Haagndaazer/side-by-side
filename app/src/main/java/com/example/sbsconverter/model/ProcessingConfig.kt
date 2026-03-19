package com.example.sbsconverter.model

data class ProcessingConfig(
    val depthScale: Float = 2f,
    val depthBlurKernel: Int = 1,
    val arrangement: Arrangement = Arrangement.PARALLEL
)

enum class Arrangement { PARALLEL, CROSS_EYED }
