package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import boofcv.abst.feature.detdesc.DetectDescribePoint
import boofcv.android.ConvertBitmap
import boofcv.factory.feature.detdesc.FactoryDetectDescribe
import boofcv.struct.feature.TupleDesc_F64
import boofcv.struct.image.GrayF32
import com.example.sbsconverter.model.AlignmentConfig
import georegression.struct.point.Point2D_F64
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class StereoAligner {

    data class AlignmentResult(
        val transformedLeft: Bitmap,
        val transformedRight: Bitmap,
        val autoVerticalShift: Float,
        val autoHorizontalShift: Float,
        val autoRotation: Float,
        val autoScale: Float,
        val matchCount: Int,
        val confidence: Float
    )

    companion object {
        private const val ALIGNMENT_SIZE = 768
        private const val MIN_MATCHES = 4
        private const val RANSAC_ITERATIONS = 500
    }

    fun align(
        leftBitmap: Bitmap,
        rightBitmap: Bitmap,
        config: AlignmentConfig
    ): AlignmentResult {
        // 1. Downscale for feature detection
        val leftScale = ALIGNMENT_SIZE.toFloat() / maxOf(leftBitmap.width, leftBitmap.height)
        val rightScale = ALIGNMENT_SIZE.toFloat() / maxOf(rightBitmap.width, rightBitmap.height)

        val leftSmall = Bitmap.createScaledBitmap(
            leftBitmap,
            (leftBitmap.width * leftScale).toInt().coerceAtLeast(1),
            (leftBitmap.height * leftScale).toInt().coerceAtLeast(1),
            true
        )
        val rightSmall = Bitmap.createScaledBitmap(
            rightBitmap,
            (rightBitmap.width * rightScale).toInt().coerceAtLeast(1),
            (rightBitmap.height * rightScale).toInt().coerceAtLeast(1),
            true
        )

        // 2. Convert to BoofCV grayscale
        val leftGray = GrayF32(leftSmall.width, leftSmall.height)
        val rightGray = GrayF32(rightSmall.width, rightSmall.height)
        ConvertBitmap.bitmapToGray(leftSmall, leftGray, null)
        ConvertBitmap.bitmapToGray(rightSmall, rightGray, null)

        // Capture height before recycling (needed for stereo constraint)
        val alignHeight = leftSmall.height

        if (leftSmall !== leftBitmap) leftSmall.recycle()
        if (rightSmall !== rightBitmap) rightSmall.recycle()

        // 3. Detect SURF features
        val detector: DetectDescribePoint<GrayF32, TupleDesc_F64> =
            FactoryDetectDescribe.surfStable(null, null, null, GrayF32::class.java)

        detector.detect(leftGray)
        val leftPoints = mutableListOf<Point2D_F64>()
        val leftDescs = mutableListOf<TupleDesc_F64>()
        for (i in 0 until detector.numberOfFeatures) {
            leftPoints.add(detector.getLocation(i).copy())
            leftDescs.add(detector.getDescription(i).copy())
        }

        detector.detect(rightGray)
        val rightPoints = mutableListOf<Point2D_F64>()
        val rightDescs = mutableListOf<TupleDesc_F64>()
        for (i in 0 until detector.numberOfFeatures) {
            rightPoints.add(detector.getLocation(i).copy())
            rightDescs.add(detector.getDescription(i).copy())
        }

        // 4a. Left-to-right matching with Lowe's ratio test
        data class MatchCandidate(val leftIdx: Int, val rightIdx: Int, val distance: Double)
        val lrCandidates = mutableListOf<MatchCandidate>()

        for (i in leftDescs.indices) {
            var bestDist = Double.MAX_VALUE
            var secondBestDist = Double.MAX_VALUE
            var bestIdx = -1

            for (j in rightDescs.indices) {
                val dist = descriptorDistance(leftDescs[i], rightDescs[j])
                if (dist < bestDist) {
                    secondBestDist = bestDist
                    bestDist = dist
                    bestIdx = j
                } else if (dist < secondBestDist) {
                    secondBestDist = dist
                }
            }

            if (bestIdx >= 0 && bestDist < 0.75 * secondBestDist) {
                lrCandidates.add(MatchCandidate(i, bestIdx, bestDist))
            }
        }

        // 4b. Right-to-left matching for cross-check
        val rlBestMatch = IntArray(rightDescs.size) { -1 }
        for (j in rightDescs.indices) {
            var bestDist = Double.MAX_VALUE
            var bestIdx = -1
            for (i in leftDescs.indices) {
                val dist = descriptorDistance(rightDescs[j], leftDescs[i])
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            rlBestMatch[j] = bestIdx
        }

        // 4c. Keep only mutual (cross-checked) matches
        val rawSrcPts = mutableListOf<Point2D_F64>()
        val rawDstPts = mutableListOf<Point2D_F64>()
        for (match in lrCandidates) {
            if (rlBestMatch[match.rightIdx] == match.leftIdx) {
                rawSrcPts.add(leftPoints[match.leftIdx])
                rawDstPts.add(rightPoints[match.rightIdx])
            }
        }

        // 5. Filter by stereo constraint: reject excessive vertical disparity
        val maxVerticalDisparity = alignHeight * 0.25
        val srcPts = mutableListOf<Point2D_F64>()
        val dstPts = mutableListOf<Point2D_F64>()
        for (i in rawSrcPts.indices) {
            if (abs(rawSrcPts[i].y - rawDstPts[i].y) <= maxVerticalDisparity) {
                srcPts.add(rawSrcPts[i])
                dstPts.add(rawDstPts[i])
            }
        }

        if (srcPts.size < MIN_MATCHES) {
            throw IllegalStateException(
                "Not enough matching features (found ${srcPts.size}, need $MIN_MATCHES). Images may not overlap."
            )
        }

        // 6. RANSAC similarity transform with least-squares refit
        val result = ransacSimilarity(srcPts, dstPts, inlierThreshold = 3.0, iterations = RANSAC_ITERATIONS)
            ?: throw IllegalStateException("Could not find reliable alignment. Images may be too different.")

        // 7. Scale transform from alignment resolution to full resolution
        // Split the correction symmetrically: half to each image
        val scaleRatio = 1.0 / leftScale
        val halfRotation = result.rotation / 2.0
        val halfScale = sqrt(result.scale) // geometric mean split
        val halfTx = (result.tx * scaleRatio / 2.0).toFloat()
        val halfTy = (result.ty * scaleRatio / 2.0).toFloat()

        // Output dimensions: use the smaller of the two to ensure both fit
        val outW = minOf(leftBitmap.width, rightBitmap.width)
        val outH = minOf(leftBitmap.height, rightBitmap.height)

        // 8a. Apply half transform to LEFT image (opposite direction)
        val leftMatrix = Matrix()
        val lcx = leftBitmap.width / 2f
        val lcy = leftBitmap.height / 2f
        leftMatrix.postTranslate(-lcx, -lcy)
        leftMatrix.postRotate(Math.toDegrees(halfRotation).toFloat())
        leftMatrix.postScale(1f / halfScale.toFloat(), 1f / halfScale.toFloat())
        leftMatrix.postTranslate(outW / 2f, outH / 2f)
        leftMatrix.postTranslate(halfTx, halfTy)

        val transformedLeft = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(transformedLeft).drawBitmap(leftBitmap, leftMatrix, null)

        // 8b. Apply half transform to RIGHT image (correction direction)
        val rightMatrix = Matrix()
        val rcx = rightBitmap.width / 2f
        val rcy = rightBitmap.height / 2f
        rightMatrix.postTranslate(-rcx, -rcy)
        rightMatrix.postRotate(Math.toDegrees(-halfRotation).toFloat())
        rightMatrix.postScale(halfScale.toFloat(), halfScale.toFloat())
        rightMatrix.postTranslate(outW / 2f, outH / 2f)
        rightMatrix.postTranslate(-halfTx, -halfTy)

        val transformedRight = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(transformedRight).drawBitmap(rightBitmap, rightMatrix, null)

        val inlierRatio = result.inlierCount.toFloat() / srcPts.size

        return AlignmentResult(
            transformedLeft = transformedLeft,
            transformedRight = transformedRight,
            autoVerticalShift = halfTy * 2,
            autoHorizontalShift = halfTx * 2,
            autoRotation = Math.toDegrees(result.rotation).toFloat(),
            autoScale = result.scale.toFloat(),
            matchCount = result.inlierCount,
            confidence = inlierRatio
        )
    }

    private data class SimilarityModel(
        val rotation: Double,
        val scale: Double,
        val tx: Double,
        val ty: Double,
        val inlierCount: Int
    )

    private fun ransacSimilarity(
        srcPts: List<Point2D_F64>,
        dstPts: List<Point2D_F64>,
        inlierThreshold: Double,
        iterations: Int
    ): SimilarityModel? {
        val n = srcPts.size
        if (n < 2) return null

        var bestModel: SimilarityModel? = null
        var bestInliers = 0
        val random = java.util.Random(42)
        val thresholdSq = inlierThreshold * inlierThreshold

        repeat(iterations) {
            val i1 = random.nextInt(n)
            var i2 = random.nextInt(n - 1)
            if (i2 >= i1) i2++

            val model = fitSimilarity(srcPts[i1], dstPts[i1], srcPts[i2], dstPts[i2]) ?: return@repeat

            var inliers = 0
            for (i in 0 until n) {
                val px = model.scale * (cos(model.rotation) * srcPts[i].x - sin(model.rotation) * srcPts[i].y) + model.tx
                val py = model.scale * (sin(model.rotation) * srcPts[i].x + cos(model.rotation) * srcPts[i].y) + model.ty
                val dx = px - dstPts[i].x
                val dy = py - dstPts[i].y
                if (dx * dx + dy * dy < thresholdSq) inliers++
            }

            if (inliers > bestInliers) {
                bestInliers = inliers
                bestModel = model.copy(inlierCount = inliers)
            }
        }

        if (bestModel == null || bestInliers < MIN_MATCHES) return null

        // Collect all inliers from best model
        val bm = bestModel!!
        val inlierSrc = mutableListOf<Point2D_F64>()
        val inlierDst = mutableListOf<Point2D_F64>()
        for (i in 0 until n) {
            val px = bm.scale * (cos(bm.rotation) * srcPts[i].x - sin(bm.rotation) * srcPts[i].y) + bm.tx
            val py = bm.scale * (sin(bm.rotation) * srcPts[i].x + cos(bm.rotation) * srcPts[i].y) + bm.ty
            val dx = px - dstPts[i].x
            val dy = py - dstPts[i].y
            if (dx * dx + dy * dy < thresholdSq) {
                inlierSrc.add(srcPts[i])
                inlierDst.add(dstPts[i])
            }
        }

        // Refit using ALL inliers via least-squares
        return fitSimilarityLeastSquares(inlierSrc, inlierDst) ?: bestModel
    }

    /**
     * Least-squares fit of similarity transform using all point pairs.
     * Solves (M^T M) * [a, b, tx, ty] = M^T * rhs via Gaussian elimination.
     * Transform: (x,y) → (ax - by + tx, bx + ay + ty)
     * where a = scale*cos(θ), b = scale*sin(θ)
     */
    private fun fitSimilarityLeastSquares(
        srcPts: List<Point2D_F64>,
        dstPts: List<Point2D_F64>
    ): SimilarityModel? {
        val n = srcPts.size
        if (n < 2) return null

        var sxx = 0.0 // sum(x² + y²)
        var sx = 0.0   // sum(x)
        var sy = 0.0   // sum(y)
        var r0 = 0.0   // sum(x*u + y*v)
        var r1 = 0.0   // sum(-y*u + x*v)
        var r2 = 0.0   // sum(u)
        var r3 = 0.0   // sum(v)

        for (i in 0 until n) {
            val x = srcPts[i].x
            val y = srcPts[i].y
            val u = dstPts[i].x
            val v = dstPts[i].y
            sxx += x * x + y * y
            sx += x
            sy += y
            r0 += x * u + y * v
            r1 += -y * u + x * v
            r2 += u
            r3 += v
        }

        val nd = n.toDouble()

        // Augmented matrix [A | b] for 4x4 system
        val aug = arrayOf(
            doubleArrayOf(sxx, 0.0, sx, -sy, r0),
            doubleArrayOf(0.0, sxx, sy, sx, r1),
            doubleArrayOf(sx, sy, nd, 0.0, r2),
            doubleArrayOf(-sy, sx, 0.0, nd, r3)
        )

        // Gaussian elimination with partial pivoting
        for (col in 0 until 4) {
            var maxVal = abs(aug[col][col])
            var maxRow = col
            for (row in col + 1 until 4) {
                val v = abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-12) return null

            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            val pivot = aug[col][col]
            for (row in col + 1 until 4) {
                val factor = aug[row][col] / pivot
                for (c in col until 5) {
                    aug[row][c] -= factor * aug[col][c]
                }
            }
        }

        // Back-substitution
        val params = DoubleArray(4)
        for (row in 3 downTo 0) {
            var sum = aug[row][4]
            for (col in row + 1 until 4) {
                sum -= aug[row][col] * params[col]
            }
            params[row] = sum / aug[row][row]
        }

        val a = params[0]
        val b = params[1]
        val tx = params[2]
        val ty = params[3]

        val scale = sqrt(a * a + b * b)
        val rotation = atan2(b, a)

        if (scale < 0.5 || scale > 2.0) return null

        return SimilarityModel(rotation, scale, tx, ty, n)
    }

    /** 2-point algebraic fit for RANSAC sampling */
    private fun fitSimilarity(
        p1: Point2D_F64, q1: Point2D_F64,
        p2: Point2D_F64, q2: Point2D_F64
    ): SimilarityModel? {
        val sx = p2.x - p1.x
        val sy = p2.y - p1.y
        val dx = q2.x - q1.x
        val dy = q2.y - q1.y

        val srcDist = sqrt(sx * sx + sy * sy)
        val dstDist = sqrt(dx * dx + dy * dy)
        if (srcDist < 1e-6 || dstDist < 1e-6) return null

        val scale = dstDist / srcDist
        val rotation = atan2(dy, dx) - atan2(sy, sx)

        val tx = q1.x - scale * (cos(rotation) * p1.x - sin(rotation) * p1.y)
        val ty = q1.y - scale * (sin(rotation) * p1.x + cos(rotation) * p1.y)

        return SimilarityModel(rotation, scale, tx, ty, 0)
    }

    private fun descriptorDistance(a: TupleDesc_F64, b: TupleDesc_F64): Double {
        var sum = 0.0
        for (i in 0 until a.size()) {
            val d = a.data[i] - b.data[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}
