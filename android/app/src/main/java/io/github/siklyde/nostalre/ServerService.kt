package io.github.siklyde.nostalre

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the game backend (rAthena's servers, grf-server) as child processes while the app is in use. */
class ServerService : Service() {

	/** A backend process: executable from the native library dir, arguments, its port and whether the next one needs it. */
	private class Proc( val name: String, val lib: String, val args: List<String>, val port: Int, val needed: Boolean = false )

	companion object {
		const val ACTION_STOP = "io.github.siklyde.nostalre.STOP"
		const val EXTRA_GAME_DIR = "game_dir"
		private const val CHANNEL = "server"

		/** Every port the game and the hub need, in start order. */
		val PORTS = listOf( 6900, 6121, 5121, 8001 )

		fun portOpen( port: Int ): Boolean = try {
			Socket().use { it.connect( InetSocketAddress( "127.0.0.1", port ), 200 ) }
			true
		} catch( e: IOException ) {
			false
		}

		/** Blocks until [ports] accept connections, or throws after a while. */
		fun waitPorts( vararg ports: Int ) {
			val deadline = System.currentTimeMillis() + 120_000
			for( port in ports )
				while( !portOpen( port ) ) {
					if( System.currentTimeMillis() > deadline )
						throw IOException( "The game server did not start (port $port)." )
					Thread.sleep( 200 )
				}
		}

		fun start( context: Context, gameDir: File ) {
			context.startForegroundService( Intent( context, ServerService::class.java ).putExtra( EXTRA_GAME_DIR, gameDir.path ) )
		}
	}

	private val running = mutableListOf<Pair<String, Process>>()
	private var launcher: Thread? = null
	@Volatile private var stopping = false

	override fun onBind( intent: Intent? ): IBinder? = null

	override fun onStartCommand( intent: Intent?, flags: Int, startId: Int ): Int {
		if( intent?.action == ACTION_STOP ) {
			stopSelf()
			return START_NOT_STICKY
		}
		startForeground( 1, notification( "Running" ), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE )
		val gameDir = intent?.getStringExtra( EXTRA_GAME_DIR )
		if( launcher == null && gameDir != null )
			launcher = thread( name = "launcher" ) { launch( File( gameDir ) ) }
		return START_NOT_STICKY
	}

	// app swiped away from recents: shut the servers down so they save
	override fun onTaskRemoved( rootIntent: Intent? ) {
		stopSelf()
	}

	override fun onDestroy() {
		stopping = true
		thread( name = "shutdown" ) {
			launcher?.join() // may still be starting one
			val procs = synchronized( running ) { running.toList().asReversed() }
			procs.forEach { it.second.destroy() } // SIGTERM: rAthena saves and exits
			procs.forEach { ( _, p ) -> if( !p.waitFor( 10, TimeUnit.SECONDS ) ) p.destroyForcibly() }
		}
	}

	private fun launch( gameDir: File ) {
		val paths = Paths( this )
		ServerConf.writeRates( this )
		val procs = listOf(
			// char-server needs login-server, map-server needs char-server
			Proc( "login-server", "libloginserver.so", emptyList(), 6900, needed = true ),
			Proc( "char-server", "libcharserver.so", emptyList(), 6121, needed = true ),
			Proc( "map-server", "libmapserver.so", emptyList(), 5121 ),
			// the same archives as the game, in its order (GameDir.archives)
			Proc( "grf-server", "libgrfserver.so", listOf( gameDir.path, "8001" ) + GameDir.archives( gameDir ).map { it.path }, 8001 ),
		)
		for( proc in procs ) {
			if( stopping )
				return
			val process = ProcessBuilder( listOf( File( paths.bin, proc.lib ).path ) + proc.args )
				.directory( paths.server )
				.redirectErrorStream( true )
				.redirectOutput( File( paths.logs, "${proc.name}.log" ) )
				.apply {
					// keep the working directory: the binaries live apart from conf/ (rAthena chdirs to argv[0]'s dir)
					environment()["RA_KEEP_CWD"] = "1"
					environment()["RA_SQLITE_DIR"] = paths.save.path
				}
				.start()
			synchronized( running ) { running += proc.name to process }
			thread( name = "watch-${proc.name}" ) {
				val code = process.waitFor()
				if( !stopping )
					getSystemService( NotificationManager::class.java ).notify( 1, notification( "${proc.name} stopped (exit $code)" ) )
			}
			if( proc.needed )
				waitPort( proc.port, process )
		}
	}

	private fun waitPort( port: Int, process: Process ) {
		while( !stopping && process.isAlive && !portOpen( port ) )
			Thread.sleep( 100 )
	}

	private fun notification( text: String ): Notification {
		val manager = getSystemService( NotificationManager::class.java )
		manager.createNotificationChannel( NotificationChannel( CHANNEL, "Game server", NotificationManager.IMPORTANCE_LOW ) )
		val open = PendingIntent.getActivity( this, 0, Intent( this, MainActivity::class.java ), PendingIntent.FLAG_IMMUTABLE )
		val stop = PendingIntent.getService( this, 1, Intent( this, ServerService::class.java ).setAction( ACTION_STOP ), PendingIntent.FLAG_IMMUTABLE )
		val folder = PendingIntent.getActivity( this, 2,
			Intent( this, MainActivity::class.java ).setAction( MainActivity.ACTION_CHOOSE_DIR ), PendingIntent.FLAG_IMMUTABLE )
		return Notification.Builder( this, CHANNEL )
			.setSmallIcon( android.R.drawable.ic_media_play )
			.setContentTitle( getString( R.string.app_name ) )
			.setContentText( text )
			.setContentIntent( open )
			.setOngoing( true )
			.addAction( Notification.Action.Builder( null, "Stop", stop ).build() )
			.addAction( Notification.Action.Builder( null, "Game folder", folder ).build() )
			.build()
	}
}
