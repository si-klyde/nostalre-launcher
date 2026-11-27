package io.github.siklyde.nostalre

import java.io.File

/**
 * The logs a bug report needs, put together as one text: the game's (client.log) and each server's, each with the run
 * before it, which a relaunch after a crash would otherwise overwrite. [LogExport] adds the device and hands it out.
 */
object Logs {
	/** Copy's limit: a GitHub issue body holds 65,536 characters. */
	const val COPY_LIMIT = 60_000

	/** The shared file's limit for each log: the end of a long session. */
	const val FILE_LIMIT = 1_000_000

	/** Moves [log] to its [previous] name before a new run writes it, replacing the older one. */
	fun keepPrevious( log: File ) {
		if( log.isFile )
			log.renameTo( previous( log ) )
	}

	/** login-server.log -> login-server.prev.log */
	fun previous( log: File ) = File( log.parentFile, log.name.removeSuffix( ".log" ) + ".prev.log" )

	/** The end of [text] in at most [limit] characters (and a note of what was cut), starting at a line. */
	fun tail( text: String, limit: Int ): String {
		if( text.length <= limit )
			return text
		val note = "[earlier lines cut]\n"
		val from = text.length - ( limit - note.length ).coerceAtLeast( 0 )
		val line = text.indexOf( '\n', from - 1 )
		val start = if( line in from - 1 until text.length - 1 ) line + 1 else from
		return note + text.substring( start )
	}

	/**
	 * [header], then each section under its title: its text, or "(none)" when missing. Sections over their share of
	 * [limit] keep their end; a short one leaves what it doesn't use to the others.
	 */
	fun report( header: String, sections: List<Pair<String, String?>>, limit: Int ): String {
		val head = header.trimEnd() + "\n"
		val titles = sections.map { ( title, _ ) -> "\n===== $title =====\n" }
		// each body ends in a line break too
		var left = limit - head.length - titles.sumOf { it.length + 1 }
		val bodies = arrayOfNulls<String>( sections.size )
		// shortest first: each takes what it needs up to an even share of what is left
		val order = sections.indices.sortedBy { sections[it].second?.length ?: 0 }
		for( ( done, i ) in order.withIndex() ) {
			val text = sections[i].second?.trimEnd()?.ifEmpty { null } ?: "(none)"
			val share = ( left / ( order.size - done ) ).coerceAtLeast( 0 )
			bodies[i] = tail( text, share )
			left -= bodies[i]!!.length
		}
		return buildString {
			append( head )
			for( i in sections.indices )
				append( titles[i] ).append( bodies[i] ).append( '\n' )
		}
	}
}
