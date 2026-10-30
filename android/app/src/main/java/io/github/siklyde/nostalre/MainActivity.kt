package io.github.siklyde.nostalre

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * Setup (storage permission, game folder, unpacking, username), then the hub; Play starts nostalRE (NativeGame), logged
 * in and entering the hub's character, until the game is left.
 */
class MainActivity : Activity() {

	companion object {
		const val ACTION_CHOOSE_DIR = "io.github.siklyde.nostalre.CHOOSE_DIR"
		private const val REQUEST_DIR = 1
		private const val START_TIMEOUT_MS = 180_000L
	}

	private var hub: Hub? = null
	private var busy = false
	private var chooseDir = false
	private var dirError: String? = null
	/** What the chosen folder lacks but can be played without: shown once, before the game starts */
	private var dirWarning: String? = null

	override fun onCreate( savedInstanceState: Bundle? ) {
		super.onCreate( savedInstanceState )
		window.addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON )
		window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
		if( intent?.action == ACTION_CHOOSE_DIR )
			closeGame() // the servers may still run from before this activity
	}

	override fun onNewIntent( intent: Intent ) {
		super.onNewIntent( intent )
		if( intent.action == ACTION_CHOOSE_DIR && !busy )
			closeGame()
	}

	override fun onResume() {
		super.onResume()
		val hub = hub
		if( hub != null )
			hub.refresh() // back from another app: stats may have changed, creation in progress stays
		else if( !busy )
			proceed()
	}

	override fun onWindowFocusChanged( hasFocus: Boolean ) {
		super.onWindowFocusChanged( hasFocus )
		if( hasFocus )
			Ui.hideSystemBars( window )
	}

	/** Next missing setup step, or start the servers and the game. */
	private fun proceed() {
		if( !Environment.isExternalStorageManager() ) {
			screen( "The game files are read from your storage. Allow access to all files for ${getString( R.string.app_name )}.",
				"Allow access" ) {
				startActivity( Intent( Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse( "package:$packageName" ) ) )
			}
			return
		}
		val gameDir = GameDir.get( this )
		if( gameDir == null || chooseDir ) {
			val message = dirError ?: "Choose your game folder: the kRO client folder, the one holding data.grf."
			screen( message, "Choose folder" ) {
				startActivityForResult( Intent( Intent.ACTION_OPEN_DOCUMENT_TREE ), REQUEST_DIR )
			}
			return
		}
		dirWarning?.let { warning ->
			screen( warning, "Continue" ) {
				dirWarning = null
				proceed()
			}
			return
		}
		if( checkSelfPermission( Manifest.permission.POST_NOTIFICATIONS ) != PackageManager.PERMISSION_GRANTED )
			requestPermissions( arrayOf( Manifest.permission.POST_NOTIFICATIONS ), 0 )

		busy = true
		screen( "Preparing…" )
		thread( name = "start" ) {
			if( Installer.needed( this ) ) {
				runOnUiThread { screen( "Unpacking game server…" ) }
				Installer.run( this )
			}
			ServerService.start( this, gameDir )
			runOnUiThread {
				busy = false
				if( Account.name( this ) == null ) askName() else showHub()
			}
		}
	}

	private fun askName( error: String? = null ) {
		val input = EditText( this ).apply {
			hint = "Username"
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
			isSingleLine = true
			// inline, not the full screen landscape editor
			imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
			textSize = 24f
			typeface = Ui.regular( this@MainActivity )
			gravity = Gravity.CENTER
			setTextColor( Ui.PARCHMENT )
			setHintTextColor( Ui.MUTED )
			backgroundTintList = android.content.res.ColorStateList.valueOf( Ui.PARCHMENT )
		}
		val layout = screen( error ?: "Choose a username: 4 to 23 letters, digits or _.", "Continue" ) {
			val name = input.text.toString().trim()
			if( !Account.NAME.matches( name ) )
				return@screen askName( "\"$name\" is not a valid username. Use 4 to 23 letters, digits or _." )
			busy = true
			thread( name = "account" ) {
				val error = runCatching { Account.setName( this, name ) }.exceptionOrNull()
				runOnUiThread {
					busy = false
					if( error == null )
						showHub()
					else
						screen( "Couldn't save the username: ${error.message}", "Retry" ) { askName() }
				}
			}
		}
		layout.addView( input, 1, LinearLayout.LayoutParams( Ui.dp( this, 420f ), -2 ).apply { bottomMargin = Ui.dp( this@MainActivity, 24f ) } )
		input.requestFocus()
	}

	private fun showHub( selectId: Int = 0 ) {
		val hub = Hub( this, selectId, { play( it ) }, { showHub( it ) }, { restartServers( it ) } )
		this.hub = hub
		setContentView( hub.view() )
	}

	/** Waits for the servers (still starting after an app start), then enters the game with [charId]. */
	private fun play( charId: Int ) {
		busy = true
		thread( name = "play" ) {
			val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
			val ports = ServerService.PORTS
			for( ( i, port ) in ports.withIndex() ) {
				if( ServerService.portOpen( port ) )
					continue
				runOnUiThread { screen( "Starting servers (${i + 1}/${ports.size})…" ) }
				while( !ServerService.portOpen( port ) ) {
					if( System.currentTimeMillis() > deadline ) {
						runOnUiThread {
							busy = false
							screen( "Port $port did not open. Logs: ${Paths( this ).logs}", "Retry" ) { play( charId ) }
						}
						return@thread
					}
					Thread.sleep( 200 )
				}
			}
			// the game's config written here, off the main thread
			val prepared = when {
				!NativeGame.available( this ) -> Result.failure( IllegalStateException( "this APK has no game client" ) )
				else -> GameDir.get( this )?.let { dir -> runCatching { NativeGame.prepare( this, dir, charId ) } }
					?: Result.failure( IllegalStateException( "no game folder" ) )
			}
			runOnUiThread {
				busy = false
				if( prepared.isSuccess )
					NativeGame.start( this )
				else
					screen( "Couldn't prepare the game: ${prepared.exceptionOrNull()?.message}", "Back" ) { showHub( charId ) }
			}
		}
	}

	@Deprecated( "framework Activity API" )
	override fun onActivityResult( requestCode: Int, resultCode: Int, data: Intent? ) {
		super.onActivityResult( requestCode, resultCode, data )
		val uri = data?.data
		if( requestCode != REQUEST_DIR || resultCode != RESULT_OK || uri == null )
			return
		val dir = GameDir.fromTree( uri )
		// onResume follows and shows the next step
		val problem = if( dir == null ) "That folder cannot be read. Choose one on the device's storage." else GameDir.problem( dir )
		if( dir != null && problem == null ) {
			GameDir.set( this, dir )
			chooseDir = false
			dirError = null
			dirWarning = GameDir.warning( dir )
		} else {
			dirError = problem
		}
	}

	/** Starts the servers again (with new rates): stopped first, until every port is closed, then the hub. */
	private fun restartServers( selectId: Int ) {
		val gameDir = GameDir.get( this ) ?: return
		busy = true
		screen( "Restarting server…" )
		stopService( Intent( this, ServerService::class.java ) )
		thread( name = "restart" ) {
			while( ServerService.PORTS.any { ServerService.portOpen( it ) } )
				Thread.sleep( 200 )
			ServerService.start( this, gameDir )
			runOnUiThread {
				busy = false
				showHub( selectId )
			}
		}
	}

	/** Stops the servers (grf-server must reopen with the new folder) and shows setup again. */
	private fun closeGame() {
		chooseDir = true
		busy = true
		screen( "Stopping servers…" )
		stopService( Intent( this, ServerService::class.java ) )
		thread( name = "stop" ) {
			while( ServerService.PORTS.any { ServerService.portOpen( it ) } )
				Thread.sleep( 200 )
			runOnUiThread {
				busy = false
				proceed()
			}
		}
	}

	// the hub's keys: a controller, Back
	override fun dispatchKeyEvent( event: KeyEvent ): Boolean {
		if( hub?.onKey( event ) == true )
			return true
		return super.dispatchKeyEvent( event )
	}

	private fun screen( message: String, button: String? = null, action: ( () -> Unit )? = null ): LinearLayout {
		hub = null
		val layout = LinearLayout( this ).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER
			setBackgroundColor( Ui.NIGHT )
			setPadding( 48, 48, 48, 48 )
		}
		layout.addView( Ui.text( this, 20f ).apply {
			text = message
			gravity = Gravity.CENTER
		}, LinearLayout.LayoutParams( Ui.dp( this, 560f ), -2 ).apply { bottomMargin = Ui.dp( this@MainActivity, 24f ) } )
		if( button != null && action != null )
			layout.addView( Ui.text( this, 20f, Ui.NIGHT ).apply {
				text = button
				gravity = Gravity.CENTER
				isFocusable = true
				isClickable = true
				background = Ui.focusable( this@MainActivity, Ui.GOLD )
				setPadding( Ui.dp( this@MainActivity, 32f ), Ui.dp( this@MainActivity, 14f ), Ui.dp( this@MainActivity, 32f ), Ui.dp( this@MainActivity, 14f ) )
				setOnClickListener { action() }
				requestFocus()
			} )
		setContentView( layout )
		return layout
	}
}
