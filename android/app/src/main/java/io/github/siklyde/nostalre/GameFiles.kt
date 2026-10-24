package io.github.siklyde.nostalre

import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Game files from grf-server by their GRF path. GRF names are CP949 bytes: paths here spell them as Latin-1
 * characters, like roBrowser does ([cp949] builds them), and grf-server turns them back into the bytes.
 */
object GameFiles {
	private const val PORT = 8001
	private const val BASE = "http://127.0.0.1:$PORT/"
	private const val CACHED = 64
	private const val CONNECT_TIMEOUT_MS = 3_000
	private const val READ_TIMEOUT_MS = 10_000

	// recently used files, also the missing ones (null)
	private val cache = object : LinkedHashMap<String, ByteArray?>( CACHED, 0.75f, true ) {
		override fun removeEldestEntry( eldest: MutableMap.MutableEntry<String, ByteArray?> ) = size > CACHED
	}

	/** CP949 text from its bytes in hex, e.g. "c0ceb0a3c1b7" (인간족). */
	fun cp949( hex: String ): String = String( hex.chunked( 2 ).map { it.toInt( 16 ).toByte() }.toByteArray(), Charsets.ISO_8859_1 )

	/** The file, or null when grf-server does not have it; throws when grf-server cannot be reached or times out (waits for it to start first). */
	fun get( path: String ): ByteArray? {
		synchronized( cache ) {
			if( path in cache )
				return cache[path]
		}
		// grf-server may still be starting after an app start
		ServerService.waitPorts( PORT )
		val connection = ( URL( url( path ) ).openConnection() as HttpURLConnection ).apply {
			// the drawing thread waits on this: a stuck request must not hold it forever
			connectTimeout = CONNECT_TIMEOUT_MS
			readTimeout = READ_TIMEOUT_MS
		}
		val data = try {
			connection.inputStream.use { it.readBytes() }
		} catch( e: FileNotFoundException ) {
			null
		} finally {
			connection.disconnect()
		}
		synchronized( cache ) { cache[path] = data }
		return data
	}

	private fun url( path: String ) = BASE + buildString {
		for( byte in path.toByteArray( Charsets.UTF_8 ) ) {
			val c = byte.toInt() and 0xff
			if( c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~/" )
				append( c.toChar() )
			else
				append( '%' ).append( "%02X".format( c ) )
		}
	}
}
