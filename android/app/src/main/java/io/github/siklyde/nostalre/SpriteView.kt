package io.github.siklyde.nostalre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A character picture in the snapshot frame ([CharacterSprite]), its ground point near the bottom of the view and
 * scaled to fill its height: by whole pixels when that is at least twice, so the pixel art stays crisp. [ground]
 * draws the patch of map cells it stands on and its shadow; [size] shrinks it (the line-up's neighbours).
 */
class SpriteView( context: Context, private val ground: Boolean, private val size: Float = 1f ) : View( context ) {
	var sprite: Bitmap? = null
		set( value ) {
			field = value
			invalidate()
		}

	/** Drawn when there is no sprite: the "+" of a free slot. */
	var placeholder: String? = null
		set( value ) {
			field = value
			invalidate()
		}

	private val paint = Paint( Paint.ANTI_ALIAS_FLAG )
	private val pixels = Paint().apply { isFilterBitmap = false }
	private val cell = Path()
	private val bounds = RectF()

	/** Slides in from the side of [direction] (-1 left, 1 right). */
	fun enter( direction: Int ) {
		animate().cancel()
		translationX = direction * width * 0.12f
		alpha = 0f
		animate().translationX( 0f ).alpha( 1f ).setDuration( 160 ).setInterpolator( DecelerateInterpolator() ).start()
	}

	override fun onDraw( canvas: Canvas ) {
		// how far the art reaches above the ground point: a standing character with room for a mount's head or wings,
		// not the empty top of the frame
		val reach = 120f
		val room = height - Ui.dp( context, 14f ).toFloat()
		val fit = room / reach * size
		val scale = if( fit >= 2f ) floor( fit ) else fit
		val cx = width / 2f
		val cy = room

		if( ground )
			drawGround( canvas, cx, cy, 32f * scale )

		val bitmap = sprite
		if( bitmap == null ) {
			val label = placeholder ?: return
			paint.style = Paint.Style.FILL
			paint.color = Ui.ACCENT
			paint.textAlign = Paint.Align.CENTER
			paint.textSize = room * 0.3f
			canvas.drawText( label, cx, room * 0.62f, paint )
			return
		}
		if( ground ) {
			paint.style = Paint.Style.FILL
			paint.color = 0x66000000
			canvas.drawOval( cx - 10f * scale, cy - 4f * scale, cx + 10f * scale, cy + 4f * scale, paint )
		}
		// a snapshot of another size (roBrowser's 120 x 160) keeps its ground point at the same share of the frame
		val groundX = bitmap.width / 2f
		val groundY = bitmap.height * CharacterSprite.GROUND_Y / CharacterSprite.HEIGHT.toFloat()
		bounds.set( cx - groundX * scale, cy - groundY * scale, cx + ( bitmap.width - groundX ) * scale, cy + ( bitmap.height - groundY ) * scale )
		canvas.drawBitmap( bitmap, null, bounds, pixels )
	}

	private fun drawGround( canvas: Canvas, cx: Float, cy: Float, cw: Float ) {
		val ch = cw / 2
		for( i in -2..2 ) for( j in -2..2 ) {
			val distance = max( abs( i ), abs( j ) )
			val x = cx + ( i - j ) * cw / 2
			val y = cy + ( i + j ) * ch / 2
			cell.reset()
			cell.moveTo( x, y - ch / 2 )
			cell.lineTo( x + cw / 2, y )
			cell.lineTo( x, y + ch / 2 )
			cell.lineTo( x - cw / 2, y )
			cell.close()
			paint.style = Paint.Style.FILL
			paint.color = if( distance == 0 ) Ui.SLATE_LIGHT else Ui.SLATE
			paint.alpha = ( 200 * ( 1f - distance / 3f ) ).toInt()
			canvas.drawPath( cell, paint )
			paint.style = Paint.Style.STROKE
			paint.strokeWidth = 2f
			paint.color = Ui.NIGHT
			canvas.drawPath( cell, paint )
		}
	}
}
