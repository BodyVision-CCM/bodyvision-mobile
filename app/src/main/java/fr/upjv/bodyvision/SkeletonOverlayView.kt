package fr.upjv.bodyvision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import kotlin.math.max

/**
 * Canvas custom dessinant le squelette (33 landmarks + connexions) par-dessus la PreviewView.
 * imageWidth/imageHeight doivent être les dimensions de l'image telle que vue par ML Kit
 * (donc déjà "debout", axes échangés si rotation 90/270), pour que le mapping vers la vue
 * corresponde au scale type FILL_CENTER de PreviewView.
 */
class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pose: Pose? = null
    private var imageWidth = 0
    private var imageHeight = 0

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    fun updatePose(newPose: Pose?, imgWidth: Int, imgHeight: Int) {
        pose = newPose
        imageWidth = imgWidth
        imageHeight = imgHeight
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = pose ?: return
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return

        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f

        fun tx(x: Float) = x * scale + dx
        fun ty(y: Float) = y * scale + dy

        for ((startType, endType) in POSE_CONNECTIONS) {
            val start = p.getPoseLandmark(startType) ?: continue
            val end = p.getPoseLandmark(endType) ?: continue
            canvas.drawLine(
                tx(start.position.x), ty(start.position.y),
                tx(end.position.x), ty(end.position.y),
                linePaint
            )
        }
        for (landmark in p.allPoseLandmarks) {
            canvas.drawCircle(tx(landmark.position.x), ty(landmark.position.y), 6f, pointPaint)
        }
    }
}
