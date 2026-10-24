package io.github.siklyde.nostalre

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Gravity sprite (.spr): frames of palette indexes or RGBA pixels, and the palette. Same reading as roBrowser's Loaders/Sprite.js. */
class Spr( data: ByteArray ) {
	/** [indexes] (into a palette) or [pixels] (ARGB). */
	class Frame( val width: Int, val height: Int, val indexes: ByteArray?, val pixels: IntArray? )

	val frames: List<Frame>
	val palette: IntArray?

	/** The RGBA frames follow this many indexed ones. */
	val indexedCount: Int

	init {
		val fp = ByteBuffer.wrap( data ).order( ByteOrder.LITTLE_ENDIAN )
		require( fp.get().toInt().toChar() == 'S' && fp.get().toInt().toChar() == 'P' ) { "not a sprite" }
		val minor = fp.get().toInt()
		val version = fp.get() + minor / 10f
		val indexed = fp.short.toInt() and 0xffff
		indexedCount = indexed
		val rgba = if( version > 1.1f ) fp.short.toInt() and 0xffff else 0
		val list = ArrayList<Frame>( indexed + rgba )
		repeat( indexed ) {
			val width = fp.short.toInt() and 0xffff
			val height = fp.short.toInt() and 0xffff
			val indexes = ByteArray( width * height )
			if( version < 2.1f ) {
				fp.get( indexes )
			} else {
				// run length encoded: 0 then how many zeros (0: just two)
				val end = ( fp.short.toInt() and 0xffff ) + fp.position()
				var i = 0
				while( fp.position() < end && i < indexes.size ) {
					val c = fp.get()
					if( c.toInt() != 0 ) {
						indexes[i++] = c
					} else {
						val count = fp.get().toInt() and 0xff
						i += if( count == 0 ) 2 else count
					}
				}
				fp.position( end )
			}
			list += Frame( width, height, indexes, null )
		}
		repeat( rgba ) {
			val width = fp.short.toInt()
			val height = fp.short.toInt()
			val pixels = IntArray( width * height )
			// ABGR, bottom row first
			for( y in height - 1 downTo 0 )
				for( x in 0 until width ) {
					val a = fp.get().toInt() and 0xff
					val b = fp.get().toInt() and 0xff
					val g = fp.get().toInt() and 0xff
					val r = fp.get().toInt() and 0xff
					pixels[y * width + x] = ( a shl 24 ) or ( r shl 16 ) or ( g shl 8 ) or b
				}
			list += Frame( width, height, null, pixels )
		}
		frames = list
		palette = if( version > 1.0f ) palette( data, data.size - 1024 ) else null
	}

	companion object {
		/** 256 RGBX entries at [offset] as ARGB; index 0 is transparent. */
		fun palette( data: ByteArray, offset: Int = 0 ) = IntArray( 256 ) { i ->
			if( i == 0 ) 0 else {
				val p = offset + i * 4
				( 0xff shl 24 ) or ( ( data[p].toInt() and 0xff ) shl 16 ) or ( ( data[p + 1].toInt() and 0xff ) shl 8 ) or ( data[p + 2].toInt() and 0xff )
			}
		}
	}
}

/** Gravity action (.act): per action and direction, animation frames of sprite layers. Same reading as roBrowser's Loaders/Action.js. */
class Act( data: ByteArray ) {
	class Layer(
		val x: Int, val y: Int, val index: Int, val mirror: Boolean, val alpha: Float,
		val scaleX: Float, val scaleY: Float, val angle: Int, val rgba: Boolean,
	)

	/** [anchor]: where attached sprites (the head on the body) line up. */
	class Frame( val layers: List<Layer>, val anchor: Pair<Int, Int>? )

	/** actions[action * 8 + direction][frame] */
	val actions: List<List<Frame>>

	init {
		val fp = ByteBuffer.wrap( data ).order( ByteOrder.LITTLE_ENDIAN )
		require( fp.get().toInt().toChar() == 'A' && fp.get().toInt().toChar() == 'C' ) { "not an action" }
		val minor = fp.get().toInt()
		val version = fp.get() + minor / 10f
		val count = fp.short.toInt() and 0xffff
		fp.position( fp.position() + 10 )
		actions = List( count ) {
			List( fp.int ) {
				fp.position( fp.position() + 32 )
				val layers = List( fp.int ) {
					val x = fp.int
					val y = fp.int
					val index = fp.int
					val mirror = fp.int != 0
					var alpha = 1f
					var scaleX = 1f
					var scaleY = 1f
					var angle = 0
					var rgba = false
					if( version >= 2.0f ) {
						fp.position( fp.position() + 3 ) // rgb tint
						alpha = ( fp.get().toInt() and 0xff ) / 255f
						scaleX = fp.float
						scaleY = if( version <= 2.3f ) scaleX else fp.float
						angle = fp.int
						rgba = fp.int == 1
						if( version >= 2.5f )
							fp.position( fp.position() + 8 )
					}
					Layer( x, y, index, mirror, alpha, scaleX, scaleY, angle, rgba )
				}
				if( version >= 2.0f )
					fp.int // sound
				var anchor: Pair<Int, Int>? = null
				if( version >= 2.3f )
					repeat( fp.int ) {
						fp.int
						val point = fp.int to fp.int
						fp.int
						if( anchor == null )
							anchor = point
					}
				Frame( layers, anchor )
			}
		}
	}
}
