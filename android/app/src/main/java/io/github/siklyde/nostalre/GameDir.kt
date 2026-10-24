package io.github.siklyde.nostalre

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.RandomAccessFile

/**
 * The player's game folder (kRO's 2025 client as WARPGATE downloads it, plus official_data.grf), read in place by the
 * game and grf-server. The player's own DATA.INI is read, never written: their Windows client reads it too.
 */
object GameDir {
	private const val KEY = "game_dir"
	private const val MAIN = "data.grf"
	private const val OFFICIAL = "official_data.grf"
	const val OFFICIAL_URL = "https://llchrisll.github.io/ROTPDocs/misc/custom_officials/"

	fun get( context: Context ): File? =
		prefs( context ).getString( KEY, null )?.let { File( it ) }?.takeIf { valid( it ) }

	fun set( context: Context, dir: File ) {
		prefs( context ).edit().putString( KEY, dir.path ).apply()
	}

	/**
	 * The archives the game and grf-server read, the first holding a file wins: DATA.INI's in its order (data.grf
	 * alone without one, or with one that lists none), then official_data.grf when the folder has it and DATA.INI
	 * does not name it, as WARPGATE's own DATA.INI does not.
	 */
	fun archives( dir: File ): List<File> {
		val ini = File( dir, "DATA.INI" )
		val names = if( ini.isFile )
			ini.readLines()
				.mapNotNull { Regex( """^\s*(\d+)\s*=\s*(.+?)\s*$""" ).find( it )?.destructured }
				.sortedBy { ( order, _ ) -> order.toInt() }
				.map { ( _, name ) -> name }
				.ifEmpty { listOf( MAIN ) }
		else
			listOf( MAIN )
		val withOfficial = if( File( dir, OFFICIAL ).isFile && names.none { it.equals( OFFICIAL, ignoreCase = true ) } )
			names + OFFICIAL
		else
			names
		return withOfficial.map { File( dir, it ) }
	}

	/** Why [dir] cannot be the game folder, or null when it can. */
	fun problem( dir: File ): String? {
		val archives = archives( dir )
		archives.firstOrNull { !it.isFile }?.let {
			return "${it.name} is not in ${dir.path}. Choose the kRO client folder itself, the one holding data.grf."
		}
		if( archives.none { grfVersion( it ) == 0x300 } )
			return "This folder's data.grf is from an older client. nostalRE needs kRO's 2025-07-16 client " +
				"(WARP0716's WARPGATE downloads it: see the setup guide)."
		return null
	}

	fun valid( dir: File ): Boolean = problem( dir ) == null

	/** What the folder lacks but can be played without, or null. */
	fun warning( dir: File ): String? =
		if( archives( dir ).none { it.name.equals( OFFICIAL, ignoreCase = true ) } )
			"No official_data.grf in this folder: monsters, NPCs and items from other servers will be invisible. " +
				"Download it from $OFFICIAL_URL and put it next to data.grf."
		else
			null

	/** A GRF's format version from its header (0x300: GRF Editor's "Event Horizon", kRO's 2025 client), or null. */
	private fun grfVersion( file: File ): Int? = runCatching {
		RandomAccessFile( file, "r" ).use { grf ->
			val header = ByteArray( 46 ).also { grf.readFully( it ) }
			val magic = String( header, 0, 15, Charsets.ISO_8859_1 )
			if( !magic.startsWith( "Event Horizon" ) && !magic.startsWith( "Master of Magic" ) )
				return@use null
			( header[42].toInt() and 0xff ) or ( ( header[43].toInt() and 0xff ) shl 8 )
		}
	}.getOrNull()

	/** Folder picker result ("primary:nostalRE", "1234-ABCD:Games/nostalRE") as a file path. */
	fun fromTree( uri: Uri ): File? {
		val id = DocumentsContract.getTreeDocumentId( uri )
		val volume = id.substringBefore( ':' )
		val path = id.substringAfter( ':', "" )
		val root = if( volume == "primary" ) Environment.getExternalStorageDirectory() else File( "/storage", volume )
		return if( path.isEmpty() ) root else File( root, path )
	}

	private fun prefs( context: Context ) = context.getSharedPreferences( "game", Context.MODE_PRIVATE )
}
