package io.github.siklyde.nostalre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import io.github.siklyde.nostalre.GameFiles.cp949

/**
 * Draws characters from the game's sprites the way roBrowser does (Renderer/Entity/EntityRender.js): body, then the
 * head lined up on the body's anchor. Idle, in the frame of the game's snapshots (nostalRE writes
 * snapshots/<char_id>.png when the player leaves): [WIDTH] x [HEIGHT], ground point at ([GROUND_X], [GROUND_Y]).
 * No headgear or equipment: what a new character wears. Reads files over the network: call off the main thread.
 */
object CharacterSprite {
	const val WIDTH = 200
	const val HEIGHT = 220
	const val GROUND_X = 100
	const val GROUND_Y = 190

	private const val TAG = "CharacterSprite"

	const val FEMALE = 0
	const val MALE = 1

	/** Directions of an idle action in a sprite's act: 0 faces the camera (south), then clockwise. */
	const val DIRECTIONS = 8

	private val HUMAN = cp949( "c0ceb0a3c1b7" ) // 인간족
	private val DORAM = cp949( "b5b5b6f7c1b7" ) // 도람족
	private const val DORAM_BODY = "summoner" // the Summoner's body sprite (nostalre-client sprite_path.rs job_name_kr)
	private val BODY = cp949( "b8f6c5eb" ) // 몸통
	private val HEAD = cp949( "b8d3b8aec5eb" ) // 머리통
	private val HAIR = cp949( "b8d3b8ae" ) // 머리
	private val SEX = listOf( cp949( "bfa9" ), cp949( "b3b2" ) ) // 여, 남

	// client DB/Jobs/HairIndexTable.js: hair style -> head sprite number, per sex
	private val HAIR_INDEX = listOf(
		intArrayOf( 2, 2, 4, 7, 1, 5, 3, 6, 12, 10, 9, 11, 8 ),
		intArrayOf( 2, 2, 1, 7, 5, 4, 3, 6, 8, 9, 10, 12, 11 ),
	)

	// client DB/Jobs/JobNameTable.js: job -> body sprite name, CP949 in hex (unknown jobs: Novice)
	private val JOBS = mapOf(
		0 to "c3cabab8c0da",
		1 to "b0cbbbe7",
		2 to "b8b6b9fdbbe7",
		3 to "b1c3bcf6",
		4 to "bcbac1f7c0da",
		5 to "bbf3c0ce",
		6 to "b5b5b5cf",
		7 to "b1e2bbe7",
		8 to "c7c1b8aebdbac6ae",
		9 to "c0a7c0fab5e5",
		10 to "c1a6c3b6b0f8",
		11 to "c7e5c5cd",
		12 to "beeebcbcbdc5",
		13 to "c6e4c4dac6e4c4da5fb1e2bbe7",
		14 to "c5a9b7e7bcbcc0ccb4f5",
		15 to "b8f9c5a9",
		16 to "bcbcc0ccc1f6",
		17 to "b7ceb1d7",
		18 to "bfacb1ddbcfabbe7",
		19 to "b9d9b5e5",
		20 to "b9abc8f1",
		21 to "bdc5c6e4c4dac5a9b7e7bcbcc0ccb4f5",
		22 to "b0e1c8a5",
		23 to "bdb4c6dbb3ebbaf1bdba",
		24 to "b0c7b3ca",
		25 to "b4d1c0da",
		26 to "bbeac5b8",
		27 to "bfa9b8a7",
		4001 to "c3cabab8c0da",
		4002 to "b0cbbbe7",
		4003 to "b8b6b9fdbbe7",
		4004 to "b1c3bcf6",
		4005 to "bcbac1f7c0da",
		4006 to "bbf3c0ce",
		4007 to "b5b5b5cf",
		4008 to "b7ceb5e5b3aac0ccc6ae",
		4009 to "c7cfc0ccc7c1b8ae",
		4010 to "c7cfc0ccc0a7c0fab5e5",
		4011 to "c8adc0ccc6aebdbab9ccbdba",
		4012 to "bdbab3aac0ccc6db",
		4013 to "beeebdd8bdc5c5a9b7cebdba",
		4014 to "b7ceb5e5c6e4c4da",
		4015 to "c6c8b6f3b5f2",
		4016 to "c3a8c7c7bfc2",
		4017 to "c7c1b7cec6e4bcad",
		4018 to "bdbac5e4c4bf",
		4019 to "c5a9b8aebfa1c0ccc5cd",
		4020 to "c5acb6f3bfee",
		4021 to "c1fdbdc3",
		4022 to "c6e4c4dac6c8b6f3b5f2",
		4023 to "c3cabab8c0da",
		4024 to "b0cbbbe7",
		4025 to "b8b6b9fdbbe7",
		4026 to "b1c3bcf6",
		4027 to "bcbac1f7c0da",
		4028 to "bbf3c0ce",
		4029 to "b5b5b5cf",
		4030 to "b1e2bbe7",
		4031 to "c7c1b8aebdbac6ae",
		4032 to "c0a7c0fab5e5",
		4033 to "c1a6c3b6b0f8",
		4034 to "c7e5c5cd",
		4035 to "beeebcbcbdc5",
		4036 to "c6e4c4dac6e4c4da5fb1e2bbe7",
		4037 to "c5a9b7e7bcbcc0ccb4f5",
		4038 to "b8f9c5a9",
		4039 to "bcbcc0ccc1f6",
		4040 to "b7ceb1d7",
		4041 to "bfacb1ddbcfabbe7",
		4042 to "b9d9b5e5",
		4043 to "b9abc8f1",
		4044 to "bdc5c6e4c4dac5a9b7e7bcbcc0ccb4f5",
		4046 to "c5c2b1c7bcd2b3e2",
		4047 to "b1c7bcba",
		4048 to "b1c7bcbac0b6c7d5",
		4049 to "bcd2bfefb8b5c4bf"

	)

	// roBrowser draws sprites half a cell up from the ground point (SpriteRenderer.js RenderCanvas2D)
	private const val CELL_MIDDLE = 17.5f

	private class Part( val spr: Spr, val act: Act, val palette: IntArray? ) {
		/** Idle facing [direction]: the idle action's first frame (actions 0 to 7 are idle, one per direction). */
		fun idle( direction: Int ) = act.actions.getOrNull( direction.mod( DIRECTIONS ) )?.firstOrNull() ?: act.actions.firstOrNull()?.firstOrNull()
	}

	private val parts = object : LinkedHashMap<String, Part?>( 16, 0.75f, true ) {
		override fun removeEldestEntry( eldest: MutableMap.MutableEntry<String, Part?> ) = size > 48
	}

	private val pixels = Paint().apply { isFilterBitmap = false }

	// one at a time: the parts are shared, and grf-server is local anyway
	private val worker = Executors.newSingleThreadExecutor()
	private val main = Handler( Looper.getMainLooper() )

	/** Runs [work] (drawing, which reads files) off the main thread, then [done] with its result on it. */
	fun <T> async( work: () -> T, done: ( T? ) -> Unit ) {
		worker.execute {
			val result = runCatching { work() }.onFailure { Log.w( TAG, "drawing failed", it ) }.getOrNull()
			main.post { done( result ) }
		}
	}

	/** The character facing [direction], or null when its sprites are missing. Unknown jobs draw as a Novice. */
	fun draw( job: Int, sex: Int, hair: Int, hairColor: Int, direction: Int = 0 ): Bitmap? {
		val doram = job in JobNames.DORAM
		val bodyPath = if( doram )
			"data/sprite/$DORAM/$BODY/${SEX[sex]}/${DORAM_BODY}_${SEX[sex]}"
		else
			"data/sprite/$HUMAN/$BODY/${SEX[sex]}/${cp949( JOBS[job] ?: JOBS.getValue( 0 ) )}_${SEX[sex]}"
		val body = part( bodyPath, null ) ?: return null
		val head = head( sex, hair, hairColor, doram ) ?: return null
		val bodyFrame = body.idle( direction ) ?: return null
		val headFrame = head.idle( direction ) ?: return null

		val bitmap = Bitmap.createBitmap( WIDTH, HEIGHT, Bitmap.Config.ARGB_8888 )
		val canvas = Canvas( bitmap )
		draw( canvas, body, bodyFrame, 0, 0 )
		val anchor = bodyFrame.anchor
		val headAnchor = headFrame.anchor
		if( anchor != null && headAnchor != null )
			draw( canvas, head, headFrame, anchor.first - headAnchor.first, anchor.second - headAnchor.second )
		else
			draw( canvas, head, headFrame, 0, 0 )
		return bitmap
	}

	/** Just the hair style, cropped to it: hair style buttons. */
	fun hair( sex: Int, hair: Int, hairColor: Int, doram: Boolean ): Bitmap? {
		val head = head( sex, hair, hairColor, doram ) ?: return null
		val frame = head.idle( 0 ) ?: return null
		val bitmap = Bitmap.createBitmap( WIDTH, HEIGHT, Bitmap.Config.ARGB_8888 )
		draw( Canvas( bitmap ), head, frame, 0, 0 )
		return crop( bitmap )
	}

	/**
	 * What each of [colors] looks like on [hair]: the average of the hair's pixels. Hair pixels are the palette
	 * entries that change between colours (face and outline stay).
	 */
	fun hairColors( sex: Int, hair: Int, doram: Boolean, colors: IntRange ): List<Int> {
		val palettes = colors.map { color -> head( sex, hair, color, doram )?.palette }
		val head = head( sex, hair, colors.first, doram ) ?: return palettes.map { Ui.MUTED }
		val frame = head.idle( 0 )?.layers?.firstOrNull { it.index >= 0 }?.let { head.spr.frames.getOrNull( it.index ) }
		val counts = IntArray( 256 )
		frame?.indexes?.forEach { counts[it.toInt() and 0xff]++ }
		val hairIndexes = ( 1 until 256 ).filter { i -> counts[i] > 0 && palettes.mapNotNull { it?.get( i ) }.distinct().size > 1 }
		val total = hairIndexes.sumOf { counts[it] }
		return palettes.map { palette ->
			if( palette == null || total == 0 )
				return@map Ui.MUTED
			fun channel( shift: Int ) = hairIndexes.sumOf { ( ( palette[it] shr shift ) and 0xff ) * counts[it] } / total
			( 0xff shl 24 ) or ( channel( 16 ) shl 16 ) or ( channel( 8 ) shl 8 ) or channel( 0 )
		}
	}

	private fun head( sex: Int, hair: Int, hairColor: Int, doram: Boolean ): Part? {
		if( doram ) // a Doram's hair is its file number (nostalre-client sprite_path.rs head_name)
			return part( "data/sprite/$DORAM/$HEAD/${SEX[sex]}/${hair}_${SEX[sex]}", "data/palette/$DORAM/$HAIR/$HAIR${hair}_${SEX[sex]}_$hairColor.pal" )
		val number = HAIR_INDEX[sex].getOrNull( hair ) ?: hair
		return part( "data/sprite/$HUMAN/$HEAD/${SEX[sex]}/${number}_${SEX[sex]}", "data/palette/$HAIR/$HAIR${number}_${SEX[sex]}_$hairColor.pal" )
	}

	/** A sprite and its action, with [palettePath] in place of the sprite's own palette when it exists. */
	private fun part( path: String, palettePath: String? ): Part? {
		val key = "$path|$palettePath"
		synchronized( parts ) {
			if( key in parts )
				return parts[key]
		}
		// unreachable grf-server throws: not remembered as missing
		val spr = GameFiles.get( "$path.spr" )?.let { data -> runCatching { Spr( data ) }.onFailure { Log.w( TAG, "$path.spr", it ) }.getOrNull() }
		val act = GameFiles.get( "$path.act" )?.let { data -> runCatching { Act( data ) }.onFailure { Log.w( TAG, "$path.act", it ) }.getOrNull() }
		if( spr == null || act == null )
			Log.w( TAG, "missing sprite $path" )
		val palette = palettePath?.let { GameFiles.get( it ) }?.let { Spr.palette( it ) } ?: spr?.palette
		val part = if( spr != null && act != null ) Part( spr, act, palette ) else null
		synchronized( parts ) { parts[key] = part }
		return part
	}

	private fun draw( canvas: Canvas, part: Part, frame: Act.Frame, offsetX: Int, offsetY: Int ) {
		for( layer in frame.layers ) {
			if( layer.index < 0 )
				continue
			val sprite = part.spr.frames.getOrNull( if( layer.rgba ) part.spr.indexedCount + layer.index else layer.index ) ?: continue
			if( sprite.width <= 0 || sprite.height <= 0 )
				continue
			val colors = sprite.pixels ?: run {
				val palette = part.palette ?: return@run null
				IntArray( sprite.width * sprite.height ) { palette[sprite.indexes!![it].toInt() and 0xff] }
			} ?: continue
			val image = Bitmap.createBitmap( colors, sprite.width, sprite.height, Bitmap.Config.ARGB_8888 )
			val width = ( sprite.width * layer.scaleX ).toInt()
			val height = ( sprite.height * layer.scaleY ).toInt()
			canvas.save()
			canvas.translate( ( GROUND_X + layer.x + offsetX ).toFloat(), ( GROUND_Y + layer.y + offsetY - CELL_MIDDLE ).toInt().toFloat() )
			canvas.rotate( layer.angle.toFloat() )
			if( layer.mirror )
				canvas.scale( -1f, 1f )
			pixels.alpha = ( layer.alpha * 255 ).toInt()
			// centred like roBrowser's `-size >> 1`, which rounds odd sizes up and left
			val left = -( ( width + 1 ) shr 1 ).toFloat()
			val top = -( ( height + 1 ) shr 1 ).toFloat()
			canvas.drawBitmap( image, null, RectF( left, top, left + width, top + height ), pixels )
			canvas.restore()
		}
	}

	private fun crop( bitmap: Bitmap ): Bitmap? {
		var left = bitmap.width
		var top = bitmap.height
		var right = -1
		var bottom = -1
		for( y in 0 until bitmap.height ) for( x in 0 until bitmap.width )
			if( bitmap.getPixel( x, y ) ushr 24 != 0 ) {
				left = minOf( left, x ); right = maxOf( right, x )
				top = minOf( top, y ); bottom = maxOf( bottom, y )
			}
		return if( right < 0 ) null else Bitmap.createBitmap( bitmap, left, top, right - left + 1, bottom - top + 1 )
	}
}
