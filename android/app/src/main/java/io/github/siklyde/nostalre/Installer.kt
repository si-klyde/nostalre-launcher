package io.github.siklyde.nostalre

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks the bundled server data after each install or update. */
object Installer {
	private const val KEY = "installed_update_time"

	fun needed( context: Context ): Boolean =
		prefs( context ).getLong( KEY, 0 ) != updateTime( context )

	fun run( context: Context ) {
		val paths = Paths( context )
		unzip( context, "server.zip", paths.server )
		paths.save.mkdirs()
		paths.logs.mkdirs()
		val db = File( paths.save, "ragnarok.db" )
		if( !db.exists() )
			context.assets.open( "ragnarok.db" ).use { input -> db.outputStream().use { input.copyTo( it ) } }
		prefs( context ).edit().putLong( KEY, updateTime( context ) ).apply()
	}

	private fun unzip( context: Context, asset: String, dest: File ) {
		dest.deleteRecursively()
		ZipInputStream( context.assets.open( asset ).buffered() ).use { zip ->
			while( true ) {
				val entry = zip.nextEntry ?: break
				val file = File( dest, entry.name )
				if( entry.isDirectory ) {
					file.mkdirs()
				} else {
					file.parentFile?.mkdirs()
					file.outputStream().use { zip.copyTo( it ) }
				}
			}
		}
	}

	private fun updateTime( context: Context ): Long =
		context.packageManager.getPackageInfo( context.packageName, 0 ).lastUpdateTime

	private fun prefs( context: Context ) = context.getSharedPreferences( "install", Context.MODE_PRIVATE )
}
