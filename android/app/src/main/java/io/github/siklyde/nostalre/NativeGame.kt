package io.github.siklyde.nostalre

import android.app.Activity
import android.content.Intent
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * The game: nostalRE (client/, Rust/wgpu), built into the APK by scripts/build-apk.sh. It runs in a NativeActivity
 * whose working directory is filesDir:
 * config.json there tells it the game files, the local servers and the hub's account and character.
 */
object NativeGame {
	private const val LIB = "libnostalre_client.so"

	fun available( activity: Activity ) = File( activity.applicationInfo.nativeLibraryDir, LIB ).isFile

	fun start( activity: Activity ) = activity.startActivity( Intent( activity, GameActivity::class.java ) )

	/**
	 * config.json for [start]: keeps what the client saved (window layout, options), sets what the app decides.
	 * Off the main thread: it walks the game folder's loose files.
	 */
	fun prepare( activity: Activity, gameDir: File, charId: Int ) {
		val file = File( activity.filesDir, "config.json" )
		val config = runCatching { JSONObject( file.readText() ) }.getOrElse { JSONObject() }
		config.put( "grf_paths", JSONArray( GameDir.archives( gameDir ).map { it.path } ) )
		config.put( "bgm_path", File( gameDir, "BGM" ).path )
		// kRO's tables beside its archive (System/, SystemEN/: map titles, pop-ups, titles, damage skins), which the
		// client folder WARPGATE downloads has; scripts/run-nostalre.sh gives a desktop run the same
		config.put( "install_dir", gameDir.path )
		config.put( "data_dir", looseFiles( activity, gameDir )?.path ?: JSONObject.NULL )
		config.put( "login_servers", JSONArray().put( JSONObject().apply {
			put( "name", "nostalRE" )
			put( "host", "127.0.0.1" )
			put( "port", 6900 )
		} ) )
		config.put( "solo", JSONObject().apply {
			put( "username", Account.name( activity ) )
			put( "password", Account.PASSWORD )
			put( "char_id", charId )
		} )
		file.writeText( config.toString( 2 ) )
	}

	/**
	 * The game folder's loose data/ files (the English text) as the client wants them. Their names are CP949
	 * bytes spelled as Latin-1, as grf-server reads them; the client looks for the Korean itself, so it gets a
	 * mirror of symlinks under decoded names, rebuilt each launch so removed files go too.
	 */
	private fun looseFiles( activity: Activity, gameDir: File ): File? {
		val loose = File( gameDir, "data" ).takeIf { it.isDirectory } ?: return null
		val mirror = File( activity.filesDir, "nostalre-data" )
		mirror.deleteRecursively()
		val cp949 = Charset.forName( "MS949" )
		loose.walkTopDown().filter { it.isFile }.forEach { source ->
			val relative = source.relativeTo( loose ).path
			// only Latin-1 spellings decode; plain ASCII stays itself, real Unicode names are left alone
			val decoded = relative.takeIf { name -> name.all { it.code < 0x100 } }
				?.let { String( it.toByteArray( Charsets.ISO_8859_1 ), cp949 ) }
				?.takeIf { '�' !in it } ?: relative
			val link = File( mirror, decoded )
			link.parentFile?.mkdirs()
			Os.symlink( source.path, link.path )
		}
		return mirror
	}
}
