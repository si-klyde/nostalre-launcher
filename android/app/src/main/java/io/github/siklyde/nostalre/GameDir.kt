package io.github.siklyde.nostalre

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** The user's RO client folder (GRFs, DATA.INI, BGM, data), read in place by grf-server. */
object GameDir {
	private const val KEY = "game_dir"

	fun get( context: Context ): File? =
		prefs( context ).getString( KEY, null )?.let { File( it ) }?.takeIf { valid( it ) }

	fun set( context: Context, dir: File ) {
		prefs( context ).edit().putString( KEY, dir.path ).apply()
	}

	fun valid( dir: File ): Boolean = File( dir, "DATA.INI" ).isFile

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
