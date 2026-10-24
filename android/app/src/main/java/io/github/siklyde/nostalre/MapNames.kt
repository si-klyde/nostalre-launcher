package io.github.siklyde.nostalre

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import kotlin.concurrent.thread

/** Map display names from the game files (data/mapnametable.txt: "pay_fild02.rsw#Payon Forest#"), cached once read. */
object MapNames {
	private const val TABLE = "data/mapnametable.txt"

	@Volatile private var names: Map<String, String>? = null

	private fun cache( context: Context ) = File( context.filesDir, "mapnametable.txt" )

	/** Display name of a map code, or the code until the table is cached. */
	fun get( context: Context, map: String ): String {
		val table = names ?: cache( context ).takeIf { it.exists() }?.let { parse( it.readBytes() ) }?.also { names = it }
		return table?.get( map.lowercase() ) ?: map
	}

	/** Caches the table from grf-server (which may still be starting), then calls [done] on a background thread. */
	fun fetch( context: Context, done: () -> Unit ) {
		if( names != null || cache( context ).exists() )
			return
		thread( name = "map-names" ) {
			val bytes = runCatching { GameFiles.get( TABLE ) }.getOrNull() ?: return@thread
			names = parse( bytes )
			// without the cache the names are fetched again next time
			runCatching { cache( context ).writeBytes( bytes ) }
			done()
		}
	}

	private fun parse( bytes: ByteArray ): Map<String, String> =
		String( bytes, Charset.forName( "EUC-KR" ) ).lineSequence()
			.mapNotNull { line ->
				val parts = line.split( '#' )
				if( parts.size >= 2 && parts[0].endsWith( ".rsw" ) ) parts[0].removeSuffix( ".rsw" ).lowercase() to parts[1].trim() else null
			}
			.toMap()
}
