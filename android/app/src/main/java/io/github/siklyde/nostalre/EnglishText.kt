package io.github.siklyde.nostalre

import android.content.Context
import java.io.File
import java.nio.charset.Charset

/**
 * The English text installed on the device: ROenglishRE downloaded ([EnglishDownload]) and converted by
 * `libnostalreenglish.so import` into app storage, layered under the game folder's own `data/` ([LooseFiles]).
 */
object EnglishText {
	const val CREDIT = "Translation: ROenglishRE by llchrisll"
	const val LINK = "https://github.com/${English.REPO}"
	private const val LIB = "libnostalreenglish.so"

	/** Where it lives: [root] is the loose folder grf-server reads (`data/` under it), [src] the download */
	class Dirs( filesDir: File ) {
		val root = File( filesDir, "english" )
		val data = File( root, "data" )
		val version = File( root, "version" )
		val src = File( filesDir, "english-src/${English.SHA}" )

		constructor( context: Context ) : this( context.filesDir )
	}

	sealed class Status {
		object NotInstalled : Status()
		data class Downloading( val progress: EnglishDownload.Progress ) : Status()
		object Converting : Status()
		/** [sha]: the ROenglishRE commit it came from */
		data class Installed( val sha: String ) : Status()
		data class Failed( val message: String ) : Status()
	}

	/** Installed once an import finished: its output holds the importer's marker file */
	fun status( dirs: Dirs ): Status =
		if( File( dirs.data, ".nostalre-english" ).isFile )
			Status.Installed( dirs.version.takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { "unknown" } )
		else Status.NotInstalled

	fun installed( context: Context ) = status( Dirs( context ) ) is Status.Installed

	fun remove( dirs: Dirs ) {
		dirs.root.deleteRecursively()
		dirs.src.parentFile?.deleteRecursively()
	}

	/** True the first time it is asked while English is missing; [mark] remembers that it was */
	fun offerOnce( installed: Boolean, offered: () -> Boolean, mark: () -> Unit ): Boolean {
		if( installed || offered() ) return false
		mark()
		return true
	}

	fun offerOnce( context: Context ): Boolean {
		val prefs = context.getSharedPreferences( "english", Context.MODE_PRIVATE )
		return offerOnce( installed( context ), { prefs.getBoolean( "offered", false ) } ) {
			prefs.edit().putBoolean( "offered", true ).apply()
		}
	}

	/** The install the app runs: the real downloader and importer, the rAthena db the app unpacked */
	fun install( context: Context ): EnglishInstall {
		val paths = Paths( context )
		val bin = File( paths.bin, LIB ).path
		val download = EnglishDownload( English.web, needed = { tree ->
			val result = run( listOf( bin, "needed", tree.path ) )
			if( result.code != 0 ) throw EnglishDownload.Failure( "Could not list the files: ${result.lastLine()}" )
			result.output.lines().filter { it.isNotBlank() }
		} )
		return EnglishInstall( Dirs( context ), File( paths.server, "db/re" ), download::download ) { src, db, out ->
			run( listOf( bin, "import", src.path, db.path, out.path ) )
		}
	}

	private fun run( command: List<String> ): EnglishInstall.Result {
		val process = ProcessBuilder( command ).start()
		val errors = StringBuilder()
		val reader = Thread { errors.append( process.errorStream.bufferedReader().readText() ) }.apply { start() }
		val output = process.inputStream.bufferedReader().readText()
		reader.join()
		val code = process.waitFor()
		return EnglishInstall.Result( code, if( code == 0 ) output else errors.toString() )
	}
}

/**
 * Download, convert, clean up. [import] runs the importer on (source, rAthena db, output folder); the importer keeps an
 * earlier output whole when it fails.
 */
class EnglishInstall(
	private val dirs: EnglishText.Dirs,
	private val db: File,
	private val download: ( File, ( EnglishDownload.Progress ) -> Unit ) -> Unit,
	private val import: ( src: File, db: File, out: File ) -> Result,
) {
	/** A process's exit code, and its output: stdout on success, stderr on failure */
	class Result( val code: Int, val output: String ) {
		fun lastLine() = output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "exit $code"
	}

	/** Runs on the calling thread (not the main one); [onStatus] follows it, from this and the download's threads */
	fun run( onStatus: ( EnglishText.Status ) -> Unit ) {
		try {
			onStatus( EnglishText.Status.Downloading( EnglishDownload.Progress( 0, 0, 0, 0 ) ) )
			download( dirs.src ) { onStatus( EnglishText.Status.Downloading( it ) ) }
			onStatus( EnglishText.Status.Converting )
			dirs.root.mkdirs()
			val result = import( dirs.src, db, dirs.data )
			if( result.code != 0 ) {
				onStatus( EnglishText.Status.Failed( result.lastLine() ) )
				return
			}
			dirs.version.writeText( English.SHA )
			dirs.src.parentFile?.deleteRecursively()
			onStatus( EnglishText.Status.Installed( English.SHA ) )
		} catch( e: EnglishDownload.Failure ) {
			onStatus( EnglishText.Status.Failed( e.message ?: "Download failed" ) )
		} catch( e: java.io.IOException ) {
			onStatus( EnglishText.Status.Failed( e.message ?: e.toString() ) )
		}
	}
}

/**
 * Loose files over the GRFs, in layers: the game folder's own `data/` first (the player's files win), then the English
 * text. Names are CP949 bytes spelled as Latin-1, as grf-server reads them; the client looks for the Korean itself.
 */
object LooseFiles {
	private val cp949 = Charset.forName( "MS949" )

	/** Decoded relative path -> file, the first layer holding a path winning; a missing layer is skipped */
	fun layers( dataDirs: List<File> ): Map<String, File> {
		val files = LinkedHashMap<String, File>()
		for( dir in dataDirs.filter { it.isDirectory } )
			dir.walkTopDown().filter { it.isFile }.forEach { source ->
				files.putIfAbsent( decode( source.relativeTo( dir ).path ), source )
			}
		return files
	}

	/** Only Latin-1 spellings decode; plain ASCII stays itself, real Unicode names are left alone */
	private fun decode( relative: String ) =
		relative.takeIf { name -> name.all { it.code < 0x100 } }
			?.let { String( it.toByteArray( Charsets.ISO_8859_1 ), cp949 ) }
			?.takeIf { '�' !in it } ?: relative

	/** grf-server's arguments: the game folder, port, the English folder as a second loose layer, the archives */
	fun grfServerArgs( gameDir: File, english: File?, archives: List<File> ): List<String> =
		listOf( gameDir.path, "8001" ) + ( english?.let { listOf( "--loose", it.path ) } ?: emptyList() ) + archives.map { it.path }
}
