package io.github.siklyde.nostalre

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.siklyde.nostalre.Ui.fit
import io.github.siklyde.nostalre.Ui.pad
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Home screen in place of the game's login and character select: the account's characters in a line-up, the chosen
 * one large in the middle with its neighbours beside it, its saved stats below; the last stop makes a new character
 * ([Creator]). [onPlay] enters the game with a character; [onChanged] rebuilds the hub selecting a character after
 * one was made or deleted.
 *
 * Controller: left/right or L1/R1 step through the line-up, A plays (or makes, on the last stop), X makes, Y deletes,
 * Start opens the settings. Touch: swipe or tap a neighbour to step, and the buttons along the bottom.
 */
class Hub(
	private val context: Context,
	private val selectId: Int,
	private val onPlay: ( charId: Int ) -> Unit,
	private val onChanged: ( selectId: Int ) -> Unit,
) {

	companion object {
		// rAthena MIN_CHARS, the slots every account has: MAX_CHARS (15 at this packet version), no VIP slots
		const val SLOTS = 15

		// the line-up: two neighbours each side of the chosen one, smaller and fainter the further out
		private val OFFSETS = -2..2
		private val WEIGHTS = mapOf( 2 to 0.55f, 1 to 0.8f, 0 to 1.4f )
		private val ALPHAS = mapOf( 2 to 0.3f, 1 to 0.6f, 0 to 1f )
		private val SIZES = mapOf( 2 to 0.45f, 1 to 0.6f, 0 to 1f )
	}

	private var characters = emptyList<Character>()
	private var selected = 0
	private var hairLimits: ServerConf.Hair? = null
	private var startPlaces = "" to ""
	private var creator: Creator? = null
	private val looks = mutableMapOf<Int, Bitmap?>()

	private val root = FrameLayout( context )
	private val slots = OFFSETS.map { Slot( it ) }
	private val dots = LinearLayout( context ).apply { gravity = Gravity.CENTER }
	private val name = Ui.text( context, 22f, typeface = Ui.bold( context ) ).fit( 14, 22 )
	private val job = Ui.text( context, 14f, Ui.ACCENT ).fit( 10, 14 )
	private val levels = Ui.text( context, 13f )
	private val hp = Bar( context, "HP", Ui.HP )
	private val sp = Bar( context, "SP", Ui.SP )
	private val ap = Bar( context, "AP", Ui.AP )
	private val stats = cells()
	private val traits = cells()
	private val traitsNote = Ui.text( context, 12f, Ui.DIM ).apply { text = "Traits come with the 4th job." }
	private val place = Ui.text( context, 14f ).fit( 10, 14 )
	private val zeny = Ui.text( context, 14f, Ui.GOLD, Ui.bold( context ) )
	private val played = Ui.text( context, 12f, Ui.DIM )
	private val statsColumn = LinearLayout( context ).apply { orientation = LinearLayout.VERTICAL }
	private val placeColumn = LinearLayout( context ).apply { orientation = LinearLayout.VERTICAL }
	private val help = Ui.text( context, 13f ).fit( 10, 13 )
	private val playHint = Ui.hint( context, listOf( "A" ), "Play", primary = true ) { primary() }
	private val deleteHint = Ui.hint( context, listOf( "Y" ), "Delete" ) { characters.getOrNull( selected )?.let { confirmDelete( it ) } }
	private val message = Ui.text( context, 18f, Ui.MUTED ).apply { gravity = Gravity.CENTER }
	private lateinit var select: View

	/** The hub, filled once the characters are read (in the background: the servers may be writing). */
	fun view(): View {
		val lineUp = SwipeRow( context ) { step( it ) }.apply {
			gravity = Gravity.BOTTOM
			addView( arrow( "‹", -1 ), LinearLayout.LayoutParams( -2, -1 ) )
			for( slot in slots )
				addView( slot, LinearLayout.LayoutParams( 0, -1, WEIGHTS.getValue( abs( slot.offset ) ) ) )
			addView( arrow( "›", 1 ), LinearLayout.LayoutParams( -2, -1 ) )
		}
		statsColumn.apply {
			addView( Ui.section( context, "STATS" ), gap( 2f ) )
			addView( stats, gap( 6f ) )
			addView( Ui.section( context, "TRAITS" ), gap( 2f ) )
			addView( traits )
			addView( traitsNote )
		}
		placeColumn.apply {
			addView( Ui.section( context, "LAST SEEN" ), gap( 4f ) )
			addView( place, gap( 6f ) )
			addView( zeny, gap( 4f ) )
			addView( played )
		}
		val details = LinearLayout( context ).apply {
			addView( LinearLayout( context ).apply {
				orientation = LinearLayout.VERTICAL
				addView( LinearLayout( context ).apply {
					gravity = Gravity.BOTTOM
					addView( name, LinearLayout.LayoutParams( 0, -2, 1f ) )
				} )
				addView( job )
				addView( levels, gap( 4f ) )
				addView( hp, gap( 2f ) )
				addView( sp, gap( 2f ) )
				addView( ap )
			}, LinearLayout.LayoutParams( 0, -2, 1.1f ) )
			addView( statsColumn, LinearLayout.LayoutParams( 0, -2, 1f ).apply { marginStart = Ui.dp( context, 28f ) } )
			addView( placeColumn, LinearLayout.LayoutParams( 0, -2, 0.8f ).apply { marginStart = Ui.dp( context, 28f ) } )
		}
		val body = FrameLayout( context ).apply {
			addView( LinearLayout( context ).apply {
				orientation = LinearLayout.VERTICAL
				addView( lineUp, LinearLayout.LayoutParams( -1, 0, 1f ) )
				addView( dots, LinearLayout.LayoutParams( -1, Ui.dp( context, 14f ) ).apply { bottomMargin = Ui.dp( context, 6f ) } )
				addView( details )
			} )
			addView( message, FrameLayout.LayoutParams( -1, -1 ) )
		}
		val corner = LinearLayout( context ).apply {
			gravity = Gravity.CENTER_VERTICAL
			addView( Ui.text( context, 12f, Ui.DIM ).apply { text = Account.name( context ) }, LinearLayout.LayoutParams( -2, -2 ).apply { marginEnd = Ui.dp( context, 12f ) } )
			addView( Ui.hint( context, listOf( "≡" ), "Settings" ) { settings() } )
		}
		select = Ui.screen( context, Ui.breadcrumb( context, null, "CHARACTERS" ), corner, body, help, listOf(
			Ui.hint( context, listOf( "L1", "R1" ), "character" ),
			playHint,
			Ui.hint( context, listOf( "X" ), "New" ) { startCreating() },
			deleteHint,
		) )
		root.addView( select )
		message.text = "Reading your characters…"
		load( selectId )
		return root
	}

	/** Reads the characters again (back from the game or another app), keeping the selection and character creation. */
	fun refresh() = load( characters.getOrNull( selected )?.id ?: selectId )

	private fun load( selectId: Int ) {
		thread( name = "hub" ) {
			val loaded = runCatching {
				val list = Account.id( context )?.let { Characters.list( context, it ) }.orEmpty()
				// reads the map name table, if cached, here rather than on the main thread
				list.firstOrNull()?.let { MapNames.get( context, it.map ) }
				val hair = ServerConf.hair( context )
				fun start( doram: Boolean ) = ServerConf.startMap( context, doram )?.let { map ->
					val shown = MapNames.get( context, map )
					if( shown == map ) map else "$shown ($map)"
				} ?: ""
				Triple( list, hair, start( false ) to start( true ) )
			}
			root.post {
				loaded.onSuccess { ( list, hair, places ) ->
					hairLimits = hair
					startPlaces = places
					show( list, selectId )
				}.onFailure {
					message.text = "Couldn't read your characters: ${it.message}"
					message.visibility = View.VISIBLE
				}
			}
		}
	}

	private fun show( list: List<Character>, selectId: Int ) {
		// a character back from the game has a new snapshot
		looks.clear()
		characters = list
		message.visibility = View.GONE
		MapNames.fetch( context ) { root.post { if( creator == null ) showSelected( 0 ) } }
		if( creator != null )
			return
		selected = characters.indexOfFirst { it.id == selectId }.coerceAtLeast( 0 )
		showSelected( 0 )
	}

	/** The stops of the line-up: the characters, then New character while a slot is free. */
	private val stops get() = characters.size + if( characters.size < SLOTS ) 1 else 0

	// X still opens character creation with every slot taken: the looks can be tried, Create says why it can't

	private fun step( step: Int ) {
		if( creator != null || stops < 2 )
			return
		selected = ( selected + step ).mod( stops )
		showSelected( step )
	}

	/** [direction]: the side the chosen one came in from (-1 left, 1 right, 0 no movement). */
	private fun showSelected( direction: Int ) {
		val wrap = stops >= OFFSETS.count()
		for( slot in slots ) {
			val index = ( selected + slot.offset ).let { if( wrap ) it.mod( stops ) else it }
			slot.show( index.takeIf { it in 0 until stops } )
		}
		if( direction != 0 )
			slots.first { it.offset == 0 }.sprite.enter( direction )

		dots.removeAllViews()
		repeat( stops ) { i ->
			dots.addView( View( context ).apply {
				background = Ui.rounded( context, if( i == selected ) Ui.ACCENT else 0x40D2EAFC, 4f )
			}, LinearLayout.LayoutParams( Ui.dp( context, if( i == selected ) 18f else 7f ), Ui.dp( context, 7f ) ).apply { marginEnd = Ui.dp( context, 5f ) } )
		}

		val character = characters.getOrNull( selected )
		deleteHint.visibility = if( character != null ) View.VISIBLE else View.GONE
		Ui.hintLabel( playHint ).text = if( character != null ) "Play" else "Create"
		if( character == null ) {
			showNew()
			return
		}
		name.text = character.name
		job.text = character.jobName
		levels.text = "Base ${character.baseLevel}    Job ${character.jobLevel}    ${if( character.isDoram ) "Doram" else "Human"}"
		hp.set( character.hp, character.maxHp )
		sp.set( character.sp, character.maxSp )
		ap.set( character.ap, character.maxAp )
		ap.visibility = if( character.maxAp > 0 ) View.VISIBLE else View.GONE
		statsColumn.visibility = View.VISIBLE
		fill( stats, character.stats )
		val hasTraits = character.maxAp > 0 || character.traits.any { it.second > 0 }
		fill( traits, character.traits )
		traits.visibility = if( hasTraits ) View.VISIBLE else View.GONE
		traitsNote.visibility = if( hasTraits ) View.GONE else View.VISIBLE
		placeColumn.visibility = View.VISIBLE
		val map = MapNames.get( context, character.map )
		place.text = map
		zeny.text = "${"%,d".format( character.zeny )} z"
		played.text = playedAgo( character.lastLogin )
		help.text = "Enter $map as ${character.name}."
	}

	private fun showNew() {
		name.text = "New character"
		job.text = "${SLOTS - characters.size} of $SLOTS slots free"
		levels.text = ""
		listOf( hp, sp, ap ).forEach { it.visibility = View.GONE }
		statsColumn.visibility = View.INVISIBLE
		placeColumn.visibility = View.INVISIBLE
		help.text = "Make a Human or a Doram in a free slot."
	}

	/** A or Enter: play the chosen one, or make a character on the last stop. */
	private fun primary() {
		val character = characters.getOrNull( selected )
		if( character != null ) onPlay( character.id ) else startCreating()
	}

	fun onKey( event: KeyEvent ): Boolean {
		creator?.let { creator ->
			if( event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B ) {
				if( event.action == KeyEvent.ACTION_UP )
					stopCreating()
				return true
			}
			return creator.onKey( event )
		}
		val act: () -> Unit = when( event.keyCode ) {
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> { { step( -1 ) } }
			KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> { { step( 1 ) } }
			KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { { primary() } }
			KeyEvent.KEYCODE_BUTTON_X -> { { startCreating() } }
			KeyEvent.KEYCODE_BUTTON_Y -> { { characters.getOrNull( selected )?.let { confirmDelete( it ) } } }
			KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { { settings() } }
			else -> return false
		}
		// held left/right keeps stepping; the rest act once
		val steps = event.keyCode in listOf( KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 )
		if( event.action == KeyEvent.ACTION_DOWN && ( event.repeatCount == 0 || steps ) )
			act()
		return true
	}

	private fun confirmDelete( character: Character ) {
		AlertDialog.Builder( context, android.R.style.Theme_DeviceDefault_Dialog_Alert )
			.setTitle( "Delete ${character.name}?" )
			.setMessage( "${character.name} and everything they carry will be gone for good." )
			.setNegativeButton( "Keep", null )
			.setPositiveButton( "Delete" ) { _, _ -> delete( character ) }
			.let { Ui.show( it ) }
			.getButton( AlertDialog.BUTTON_NEGATIVE ).requestFocus()
	}

	private fun delete( character: Character ) {
		val name = Account.name( context ) ?: return
		deleteHint.isEnabled = false
		thread( name = "delete" ) {
			val error = runCatching {
				// the servers may still be starting after an app start
				ServerService.waitPorts( 6900, 6121 )
				CharServer.delete( name, character.id )
			}.exceptionOrNull()
			root.post {
				deleteHint.isEnabled = true
				if( error == null ) {
					File( Paths( context ).snapshots, "${character.id}.png" ).delete()
					onChanged( characters.getOrNull( selected - 1 )?.id ?: 0 )
				} else {
					AlertDialog.Builder( context, android.R.style.Theme_DeviceDefault_Dialog_Alert )
						.setTitle( "${character.name} was not deleted" )
						.setMessage( error.message ?: error.toString() )
						.setPositiveButton( "OK", null )
						.let { Ui.show( it ) }
				}
			}
		}
	}

	private fun settings() {
		thread( name = "settings" ) {
			val gm = runCatching { Account.isGm( context ) }.getOrDefault( false )
			root.post { settings( gm ) }
		}
	}

	private fun settings( isGm: Boolean ) {
		val gm = Switch( context ).apply {
			text = "GM commands"
			textSize = 18f
			isChecked = isGm
			setOnCheckedChangeListener( object : CompoundButton.OnCheckedChangeListener {
				override fun onCheckedChanged( button: CompoundButton, checked: Boolean ) {
					val listener = this
					thread( name = "gm" ) {
						if( runCatching { Account.setGm( context, checked ) }.isFailure )
							button.post {
								// not saved: show what the account still has
								button.setOnCheckedChangeListener( null )
								button.isChecked = !checked
								button.setOnCheckedChangeListener( listener )
							}
					}
				}
			} )
		}
		val layout = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			pad( context, 24f, 16f )
			addView( gm )
			addView( Ui.text( context, 14f, Ui.MUTED ).apply {
				text = "Lets you use @ commands in chat, like @warp and @item. Takes effect the next time you play."
			} )
		}
		AlertDialog.Builder( context, android.R.style.Theme_DeviceDefault_Dialog_Alert )
			.setTitle( "Settings" )
			.setView( layout )
			.setPositiveButton( "Done", null )
			.let { Ui.show( it ) }
	}

	/** How [character] looks: the game's last snapshot (with headgear, equipment and mount), else drawn from its sprites. */
	private fun look( character: Character, done: ( Bitmap? ) -> Unit ) {
		if( character.id in looks )
			return done( looks[character.id] )
		val file = File( Paths( context ).snapshots, "${character.id}.png" )
		CharacterSprite.async( {
			BitmapFactory.decodeFile( file.path ) ?: CharacterSprite.draw( character.job, character.sex, character.hair, character.hairColor )
		} ) { bitmap ->
			looks[character.id] = bitmap
			done( bitmap )
		}
	}

	private fun startCreating() {
		val hair = hairLimits ?: return
		if( creator != null )
			return
		val used = characters.map { it.slot }.toSet()
		val creator = Creator( context, ( 0 until SLOTS ).firstOrNull { it !in used }, SLOTS, hair, startPlaces, { onChanged( it ) }, { stopCreating() } )
		this.creator = creator
		select.visibility = View.GONE
		root.addView( creator.view() )
	}

	private fun stopCreating() {
		creator = null
		root.removeAllViews()
		root.addView( select )
		select.visibility = View.VISIBLE
		showSelected( 0 )
	}

	private fun playedAgo( lastLogin: String? ): String {
		val time = lastLogin?.let { runCatching { SimpleDateFormat( "yyyy-MM-dd HH:mm:ss", Locale.ROOT ).parse( it ) }.getOrNull() } ?: return ""
		val minutes = ( System.currentTimeMillis() - time.time ) / 60_000
		return when {
			minutes < 0 || time.time <= 0 -> ""
			minutes < 2 -> "Played just now"
			minutes < 60 -> "Played $minutes min ago"
			minutes < 48 * 60 -> "Played ${minutes / 60} h ago"
			else -> "Played ${minutes / ( 24 * 60 )} days ago"
		}
	}

	/** Six label-over-value cells in a row (stats, traits). */
	private fun cells() = LinearLayout( context )

	private fun fill( row: LinearLayout, values: List<Pair<String, Int>> ) {
		row.removeAllViews()
		for( ( label, value ) in values )
			row.addView( LinearLayout( context ).apply {
				orientation = LinearLayout.VERTICAL
				gravity = Gravity.CENTER_HORIZONTAL
				addView( Ui.text( context, 10f, Ui.DIM ).apply { text = label } )
				addView( Ui.text( context, 17f, typeface = Ui.bold( context ) ).fit( 10, 17 ).apply { text = value.toString() } )
			}, LinearLayout.LayoutParams( 0, -2, 1f ) )
	}

	/** A step arrow beside the line-up, for touch (L1/R1 and the D-pad do the same). */
	private fun arrow( glyph: String, step: Int ) = Ui.text( context, 34f, Ui.ACCENT ).apply {
		text = glyph
		gravity = Gravity.CENTER
		isClickable = true
		background = Ui.focusable( context, 0, 24f )
		pad( context, 10f, 0f )
		contentDescription = if( step < 0 ) "Previous character" else "Next character"
		setOnClickListener { step( step ) }
	}

	private fun gap( bottom: Float ) = LinearLayout.LayoutParams( -1, -2 ).apply { bottomMargin = Ui.dp( context, bottom ) }

	/** One place in the line-up, [offset] stops from the chosen one: its picture over its name; a tap steps to it. */
	private inner class Slot( val offset: Int ) : LinearLayout( context ) {
		val sprite = SpriteView( context, ground = offset == 0, size = SIZES.getValue( abs( offset ) ) )
		private val label = Ui.text( context, if( offset == 0 ) 15f else 11f, if( offset == 0 ) Ui.PARCHMENT else Ui.DIM ).apply {
			gravity = Gravity.CENTER
			maxLines = 1
			if( offset == 0 ) typeface = Ui.bold( context )
		}
		private var index: Int? = null

		init {
			orientation = VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			alpha = ALPHAS.getValue( abs( offset ) )
			addView( sprite, LayoutParams( -1, 0, 1f ) )
			addView( label, LayoutParams( -1, -2 ) )
			if( offset != 0 ) {
				isClickable = true
				setOnClickListener { step( offset ) }
			}
		}

		fun show( index: Int? ) {
			this.index = index
			visibility = if( index == null ) INVISIBLE else VISIBLE
			val character = index?.let { characters.getOrNull( it ) }
			label.text = character?.name ?: if( index != null ) "New character" else ""
			sprite.placeholder = if( index != null && character == null ) "+" else null
			sprite.sprite = null
			if( character != null )
				look( character ) { bitmap -> if( this.index == index ) sprite.sprite = bitmap }
		}
	}

	/** A row that turns a sideways swipe into a step (the swipe's opposite way, like turning a page), taps going through. */
	private class SwipeRow( context: Context, private val onSwipe: ( Int ) -> Unit ) : LinearLayout( context ) {
		private val slop = ViewConfiguration.get( context ).scaledTouchSlop * 3
		private var startX = 0f
		private var swiping = false

		override fun onInterceptTouchEvent( event: MotionEvent ): Boolean {
			when( event.actionMasked ) {
				MotionEvent.ACTION_DOWN -> {
					startX = event.x
					swiping = false
				}
				MotionEvent.ACTION_MOVE -> if( abs( event.x - startX ) > slop ) swiping = true
			}
			return swiping
		}

		override fun onTouchEvent( event: MotionEvent ): Boolean {
			when( event.actionMasked ) {
				MotionEvent.ACTION_DOWN -> {
					startX = event.x
					return true
				}
				MotionEvent.ACTION_UP -> {
					val dx = event.x - startX
					if( abs( dx ) > slop )
						onSwipe( if( dx < 0 ) 1 else -1 )
					swiping = false
					performClick()
				}
				MotionEvent.ACTION_CANCEL -> swiping = false
			}
			return true
		}

		override fun performClick() = super.performClick()
	}

	/** A labelled HP/SP/AP bar like the game's own. */
	private class Bar( context: Context, label: String, private val color: Int ) : LinearLayout( context ) {
		private val fill = object : View( context ) {
			var fraction = 0f
			private val paint = Paint( Paint.ANTI_ALIAS_FLAG )
			private val radius = Ui.dp( context, 2f ).toFloat()

			override fun onDraw( canvas: Canvas ) {
				paint.color = 0xFF1D2438.toInt()
				canvas.drawRoundRect( 0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint )
				paint.color = color
				canvas.drawRoundRect( 0f, 0f, width * fraction, height.toFloat(), radius, radius, paint )
			}
		}
		private val value = Ui.text( context, 12f ).apply { gravity = Gravity.END }

		init {
			gravity = Gravity.CENTER_VERTICAL
			addView( Ui.text( context, 11f, Ui.DIM ).apply { text = label }, LayoutParams( Ui.dp( context, 26f ), -2 ) )
			addView( fill, LayoutParams( 0, Ui.dp( context, 8f ), 1f ) )
			addView( value, LayoutParams( Ui.dp( context, 100f ), -2 ) )
		}

		fun set( current: Int, max: Int ) {
			visibility = VISIBLE
			fill.fraction = if( max > 0 ) ( current.toFloat() / max ).coerceIn( 0f, 1f ) else 0f
			fill.invalidate()
			value.text = "${"%,d".format( current )} / ${"%,d".format( max )}"
		}
	}
}
