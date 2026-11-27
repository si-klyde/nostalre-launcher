package io.github.siklyde.nostalre

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The hub's Logs button: the game's and the servers' logs ([Logs]), the device, and the app's own system log, for a
 * bug report on GitHub or Discord. Copy puts a report that fits a GitHub issue on the clipboard; Share sends the
 * whole thing as a text file.
 */
object LogExport {
	/** The system log lines kept: the app's own processes only (Android shows an app nothing else). */
	private const val LOGCAT_LINES = 3000

	private val SERVERS = listOf( "login-server", "char-server", "map-server", "grf-server" )

	fun ask( context: Context ) {
		AlertDialog.Builder( context, android.R.style.Theme_DeviceDefault_Dialog_Alert )
			.setTitle( "Export logs" )
			.setMessage( "The game's and the servers' logs, with your device model and Android version, to attach to a " +
				"bug report on GitHub or Discord.\n\nThey show your account name and game folder, nothing else private.\n\n" +
				"Copy fits a GitHub issue. Share sends the full logs as a file." )
			.setNegativeButton( "Cancel", null )
			.setNeutralButton( "Copy" ) { _, _ -> build( context, Logs.COPY_LIMIT ) { copy( context, it ) } }
			.setPositiveButton( "Share" ) { _, _ -> build( context, Logs.FILE_LIMIT * sectionCount() ) { share( context, it ) } }
			.let { Ui.show( it ) }
			.getButton( AlertDialog.BUTTON_POSITIVE ).requestFocus()
	}

	/** Builds the report off the main thread (logcat takes a moment), then [done] with it on the main thread. */
	private fun build( context: Context, limit: Int, done: ( String ) -> Unit ) {
		val app = context.applicationContext
		thread( name = "logs" ) {
			val report = Logs.report( header( app ), sections( app ), limit )
			Handler( Looper.getMainLooper() ).post { done( report ) }
		}
	}

	private fun sectionCount() = ( 1 + SERVERS.size ) * 2 + 1

	private fun sections( context: Context ): List<Pair<String, String?>> {
		val logs = Paths( context ).logs
		val files = listOf( File( context.filesDir, "client.log" ) ) + SERVERS.map { File( logs, "$it.log" ) }
		return files.flatMap { file ->
			listOf( "${file.name} (last run)" to read( file ), "${file.name} (run before)" to read( Logs.previous( file ) ) )
		} + ( "system log (logcat)" to logcat() )
	}

	private fun read( file: File ) = runCatching { file.readText() }.getOrNull()

	private fun header( context: Context ): String {
		val now = SimpleDateFormat( "yyyy-MM-dd HH:mm:ss Z", Locale.ROOT ).format( Date() )
		val version = runCatching {
			val info = context.packageManager.getPackageInfo( context.packageName, 0 )
			"${info.versionName} (${info.longVersionCode})"
		}.getOrDefault( "?" )
		val free = context.filesDir.usableSpace / ( 1024 * 1024 )
		return """
			nostalRE logs, $now
			App: $version
			Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), ${Build.SUPPORTED_ABIS.joinToString()}
			Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}
			Free storage: $free MB
		""".trimIndent()
	}

	private fun logcat(): String? = runCatching {
		val process = ProcessBuilder( "logcat", "-d", "-t", LOGCAT_LINES.toString(), "-v", "threadtime" )
			.redirectErrorStream( true ).start()
		val text = process.inputStream.bufferedReader().use { it.readText() }
		if( !process.waitFor( 5, TimeUnit.SECONDS ) )
			process.destroyForcibly()
		text
	}.getOrNull()

	private fun copy( context: Context, report: String ) {
		val clipboard = context.getSystemService( ClipboardManager::class.java )
		clipboard.setPrimaryClip( ClipData.newPlainText( "nostalRE logs", report ) )
		// Android 13 shows its own confirmation
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU )
			Toast.makeText( context, "Logs copied", Toast.LENGTH_SHORT ).show()
	}

	private fun share( context: Context, report: String ) {
		val file = runCatching {
			val dir = LogProvider.dir( context )
			// one export at a time: the last one is all a player needs
			dir.deleteRecursively()
			dir.mkdirs()
			val stamp = SimpleDateFormat( "yyyyMMdd-HHmmss", Locale.ROOT ).format( Date() )
			File( dir, "nostalre-logs-$stamp.txt" ).apply { writeText( report ) }
		}.getOrElse {
			Toast.makeText( context, "Couldn't write the logs: ${it.message}", Toast.LENGTH_LONG ).show()
			return
		}
		val uri = LogProvider.uri( context, file )
		val send = Intent( Intent.ACTION_SEND )
			.setType( "text/plain" )
			.putExtra( Intent.EXTRA_STREAM, uri )
			.putExtra( Intent.EXTRA_SUBJECT, "nostalRE logs" )
			.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION )
		// the grant reaches the chooser's preview and the picked app through the clip
		send.clipData = ClipData.newRawUri( file.name, uri )
		context.startActivity( Intent.createChooser( send, "Share logs" ) )
	}
}
