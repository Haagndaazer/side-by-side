package com.example.sbsconverter.model

import android.graphics.Bitmap

data class SbsResult(
    val sbsBitmap: Bitmap,
    val meshVerts: FloatArray,
    val meshW: Int,
    val meshH: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SbsResult) return false
        return sbsBitmap == other.sbsBitmap &&
                meshVerts.contentEquals(other.meshVerts) &&
                meshW == other.meshW &&
                meshH == other.meshH
    }

    override fun hashCode(): Int {
        var result = sbsBitmap.hashCode()
        result = 31 * result + meshVerts.contentHashCode()
        result = 31 * result + meshW
        result = 31 * result + meshH
        return result
    }
}
