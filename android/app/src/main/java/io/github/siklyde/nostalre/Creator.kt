package io.github.siklyde.nostalre

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.siklyde.nostalre.Ui.pad
import kotlin.concurrent.thread

/**
 * The hub's character creation, in place of the game's: race, body, hair style and colour, name, made through the
 * char server. What it offers is what the servers accept ([ServerConf]). [onCreated] gets the new char_id.
 *
 * Controller: the D-pad moves between choices, A picks, L1/R1 turn the hair style page, L2/R2 turn the preview,
 * Start creates, B goes back ([Hub]). Touch: tap a choice, drag the preview to turn it.
 */
class Creator(
	private val context: Context,
	private val freeSlot: Int?,
	private val slots: Int,
	private val hairLimits: ServerConf.Hair,
	private val startPlaces: Pair<String, String>,
	private val onCreated: ( charId: Int ) -> Unit,
	private val onCancel: () -> Unit,
) {
	private companion object {
		const val PER_ROW = 10
		const val PER_PAGE = 20
		const val NAME_LENGTH = 23
		const val SUMMONER = 4218 // rAthena JOB_SUMMONER: the job a Doram starts as
	}

	private var doram = false
	private var sex = CharacterSprite.MALE
	private var hair = 1
	private var hairColor = hairLimits.colors.first
	private var direction = 0
	private var page = 0

	private val styles get() = hairLimits.styles( sex, doram )
	private val pages get() = ( styles.count() + PER_PAGE - 1 ) / PER_PAGE

	private val preview = SpriteView( context, ground = true )
	private val raceChoices = listOf( false to "Human", true to "Doram" ).associate { ( value, label ) ->
		value to choice( label ) { setRace( value ) }
	}
	private val sexChoices = listOf( CharacterSprite.MALE to "Male", CharacterSprite.FEMALE to "Female" ).associate { ( value, label ) ->
		value to choice( label ) { setSex( value ) }
	}
	private val raceNote = Ui.text( context, 12f, Ui.DIM )
	private val pageLabel = Ui.text( context, 11f, Ui.DIM )
	private val styleGrid = LinearLayout( context ).apply { orientation = LinearLayout.VERTICAL }
	private val styleTiles = List( PER_PAGE ) { i ->
		ImageView( context ).apply {
			isFocusable = true
			isClickable = true
			scaleType = ImageView.ScaleType.FIT_CENTER
			pad( context, 3f, 3f )
			setOnClickListener { styleAt( i )?.let { setHair( it ) } }
		}
	}
	private val colorRow = LinearLayout( context )
	private val swatches = hairLimits.colors.associateWith { color ->
		View( context ).apply {
			isFocusable = true
			isClickable = true
			contentDescription = "Hair colour $color"
			setOnClickListener { setHairColor( color ) }
		}
	}
	private val name = EditText( context ).apply {
		hint = "Name"
		textSize = 18f
		typeface = Ui.regular( context )
		setTextColor( Ui.PARCHMENT )
		setHintTextColor( Ui.MUTED )
		backgroundTintList = android.content.res.ColorStateList.valueOf( Ui.ACCENT )
		isSingleLine = true
		inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
		// inline, not the full screen landscape editor
		imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
		filters = arrayOf( InputFilter.LengthFilter( NAME_LENGTH ) )
	}
	private val nameCount = Ui.text( context, 11f, Ui.DIM ).apply { text = "0 / $NAME_LENGTH" }
	private val startJob = Ui.text( context, 17f, typeface = Ui.bold( context ) )
	private val startPlace = Ui.text( context, 12f, Ui.DIM )
	private val path = Ui.text( context, 12f, Ui.DIM )
	private val error = Ui.text( context, 13f, Ui.ERROR ).apply { visibility = View.GONE }
	private val create = Ui.hint( context, listOf( "≡" ), "Create", primary = true ) { submit() }.apply { isFocusable = true }
	private val createLabel = Ui.hintLabel( create )

	fun view(): View {
		val choices = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			addView( section( "RACE" ) )
			addView( LinearLayout( context ).apply {
				gravity = Gravity.CENTER_VERTICAL
				raceChoices.values.forEach { addView( it, pill() ) }
				addView( raceNote, LinearLayout.LayoutParams( -2, -2 ).apply { marginStart = Ui.dp( context, 8f ) } )
			}, gap( 6f ) )
			addView( section( "BODY" ) )
			addView( LinearLayout( context ).apply { sexChoices.values.forEach { addView( it, pill() ) } }, gap( 6f ) )
			addView( section( "HAIR STYLE", pageLabel ) )
			addView( styleGrid, gap( 6f ) )
			addView( section( "HAIR COLOUR" ) )
			addView( colorRow, gap( 6f ) )
			addView( section( "NAME" ) )
			addView( LinearLayout( context ).apply {
				gravity = Gravity.CENTER_VERTICAL
				addView( name, LinearLayout.LayoutParams( 0, -2, 1f ) )
				addView( nameCount, LinearLayout.LayoutParams( -2, -2 ).apply { marginStart = Ui.dp( context, 8f ) } )
			} )
		}
		// a row shares the column's width: fixed tiles ran past a narrow column, the last ones out of sight and reach
		styleTiles.chunked( PER_ROW ).forEach { row ->
			styleGrid.addView( LinearLayout( context ).apply {
				row.forEach { addView( it, LinearLayout.LayoutParams( 0, Ui.dp( context, 36f ), 1f ).apply { marginEnd = Ui.dp( context, 4f ) } ) }
			}, LinearLayout.LayoutParams( -1, -2 ).apply { bottomMargin = Ui.dp( context, 4f ) } )
		}
		swatches.values.forEach { swatch ->
			colorRow.addView( swatch, LinearLayout.LayoutParams( Ui.dp( context, 28f ), Ui.dp( context, 28f ) ).apply { marginEnd = Ui.dp( context, 8f ) } )
		}
		name.addTextChangedListener( object : TextWatcher {
			override fun beforeTextChanged( s: CharSequence?, start: Int, count: Int, after: Int ) = Unit
			override fun onTextChanged( s: CharSequence?, start: Int, before: Int, count: Int ) = Unit
			override fun afterTextChanged( s: Editable? ) {
				nameCount.text = "${s?.length ?: 0} / $NAME_LENGTH"
			}
		} )
		// Done hides the keyboard and moves on to Create
		name.setOnEditorActionListener { _, action, _ ->
			if( action == EditorInfo.IME_ACTION_DONE )
				create.requestFocus()
			false
		}

		// drag sideways to turn: one direction every 40 dp
		var dragX = 0f
		preview.setOnTouchListener { view, event ->
			when( event.actionMasked ) {
				MotionEvent.ACTION_DOWN -> dragX = event.x
				MotionEvent.ACTION_MOVE -> {
					val step = Ui.dp( context, 40f )
					while( event.x - dragX > step ) { turn( -1 ); dragX += step }
					while( dragX - event.x > step ) { turn( 1 ); dragX -= step }
				}
				MotionEvent.ACTION_UP -> view.performClick()
			}
			true
		}
		val stage = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			addView( preview, LinearLayout.LayoutParams( -1, 0, 1f ) )
			addView( LinearLayout( context ).apply {
				gravity = Gravity.CENTER
				addView( action( "L2", "‹" ) { turn( 1 ) } )
				addView( Ui.text( context, 11f, Ui.DIM ).apply { text = "turn"; pad( context, 8f, 0f ) } )
				addView( action( "R2", "›" ) { turn( -1 ) } )
			} )
		}
		val summary = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			addView( section( "STARTS AS" ) )
			addView( startJob )
			addView( startPlace, gap( 12f ) )
			addView( section( "PATH" ) )
			addView( path )
			addView( View( context ), LinearLayout.LayoutParams( -1, 0, 1f ) )
			addView( error, gap( 8f ) )
			addView( LinearLayout( context ).apply {
				addView( create )
				addView( Ui.hint( context, listOf( "B" ), "Cancel" ) { onCancel() }.apply { isFocusable = true },
					LinearLayout.LayoutParams( -2, -2 ).apply { marginStart = Ui.dp( context, 8f ) } )
			} )
		}
		val body = LinearLayout( context ).apply {
			addView( ScrollView( context ).apply {
				isVerticalScrollBarEnabled = false
				addView( choices )
			}, LinearLayout.LayoutParams( 0, -1, 1.35f ) )
			addView( stage, LinearLayout.LayoutParams( 0, -1, 0.8f ).apply { marginStart = Ui.dp( context, 16f ) } )
			addView( summary, LinearLayout.LayoutParams( 0, -1, 0.85f ).apply { marginStart = Ui.dp( context, 16f ) } )
		}

		name.isFocusedByDefault = true
		if( freeSlot == null ) {
			createLabel.text = "No free slot"
			createLabel.setTextColor( Ui.MUTED )
		}
		update()
		return Ui.screen( context,
			Ui.breadcrumb( context, "CHARACTERS ›", "NEW CHARACTER" ),
			Ui.text( context, 12f, Ui.DIM ).apply { text = if( freeSlot != null ) "Slot ${freeSlot + 1} of $slots" else "No free slot" },
			body,
			Ui.text( context, 13f ).apply { text = "Pick a look; the preview shows all but the name." },
			listOf( Ui.hint( context, listOf( "✥" ), "choose" ), Ui.hint( context, listOf( "L1", "R1" ), "style page" ) ),
		)
	}

	/** L1/R1 turn the hair style page, L2/R2 the preview, Start creates. */
	fun onKey( event: KeyEvent ): Boolean {
		val act: () -> Unit = when( event.keyCode ) {
			KeyEvent.KEYCODE_BUTTON_L1 -> { { setPage( page - 1 ) } }
			KeyEvent.KEYCODE_BUTTON_R1 -> { { setPage( page + 1 ) } }
			KeyEvent.KEYCODE_BUTTON_L2 -> { { turn( 1 ) } }
			KeyEvent.KEYCODE_BUTTON_R2 -> { { turn( -1 ) } }
			KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { { submit() } }
			else -> return false
		}
		if( event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 )
			act()
		return true
	}

	private fun setRace( value: Boolean ) {
		doram = value
		hair = styles.first
		page = 0
		update()
	}

	private fun setSex( value: Int ) {
		sex = value
		hair = hair.coerceIn( styles )
		page = ( hair - styles.first ) / PER_PAGE
		update()
	}

	private fun setHair( value: Int ) {
		hair = value
		drawStyleMarks()
		drawColors()
		drawPreview()
	}

	private fun setHairColor( value: Int ) {
		hairColor = value
		drawStyles()
		drawColors()
		drawPreview()
	}

	private fun setPage( value: Int ) {
		page = ( value + pages ) % pages
		drawStyles()
	}

	private fun turn( step: Int ) {
		direction = ( direction + step ).mod( CharacterSprite.DIRECTIONS )
		drawPreview()
	}

	private fun update() {
		raceChoices.forEach { ( value, view ) -> view.background = Ui.choice( context, value == doram ) }
		sexChoices.forEach { ( value, view ) -> view.background = Ui.choice( context, value == sex ) }
		raceNote.text = if( doram ) "The Summoner line" else "Every class but the Summoner's"
		startJob.text = Characters.jobName( if( doram ) SUMMONER else 0 )
		startPlace.text = if( doram ) startPlaces.second else startPlaces.first
		path.text = if( doram ) "Summoner, then Spirit Handler" else "Novice, then a first job and on to the fourth, or an expanded class"
		drawStyles()
		drawColors()
		drawPreview()
	}

	private fun styleAt( index: Int ): Int? = ( styles.first + page * PER_PAGE + index ).takeIf { it in styles }

	private fun drawStyles() {
		pageLabel.text = if( pages > 1 ) "page ${page + 1} / $pages" else "${styles.count()} styles"
		val look = Triple( sex, hairColor, doram )
		styleTiles.forEachIndexed { i, tile ->
			val style = styleAt( i )
			tile.visibility = if( style != null ) View.VISIBLE else View.INVISIBLE
			tile.setImageDrawable( null )
			tile.contentDescription = "Hair style $style"
			if( style == null )
				return@forEachIndexed
			CharacterSprite.async( { CharacterSprite.hair( look.first, style, look.second, look.third ) } ) { bitmap ->
				if( look == Triple( sex, hairColor, doram ) && styleAt( i ) == style && bitmap != null ) {
					tile.setImageBitmap( bitmap )
					tile.drawable.isFilterBitmap = false // pixel art
				}
			}
		}
		drawStyleMarks()
	}

	private fun drawStyleMarks() = styleTiles.forEachIndexed { i, tile ->
		tile.background = Ui.focusable( context, 0x0DD2EAFC, 4f, selected = styleAt( i ) == hair )
	}

	private fun drawColors() {
		val look = Triple( sex, hair, doram )
		CharacterSprite.async( { CharacterSprite.hairColors( look.first, look.second, look.third, hairLimits.colors ) } ) { colors ->
			if( look != Triple( sex, hair, doram ) || colors == null )
				return@async
			swatches.forEach { ( color, swatch ) ->
				swatch.background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor( colors[color - hairLimits.colors.first] )
					setStroke( Ui.dp( context, 2f ), if( color == hairColor ) Ui.ACCENT else 0 )
				}
				swatch.foreground = Ui.focusable( context, 0, 14f )
			}
		}
	}

	private fun drawPreview() {
		val look = listOf( sex, hair, hairColor, direction, if( doram ) 1 else 0 )
		CharacterSprite.async( { CharacterSprite.draw( if( doram ) SUMMONER else 0, sex, hair, hairColor, direction ) } ) { bitmap ->
			if( look == listOf( sex, hair, hairColor, direction, if( doram ) 1 else 0 ) )
				preview.sprite = bitmap
		}
	}

	private fun submit() {
		val text = name.text.toString().trim()
		val account = Account.name( context ) ?: return
		when {
			text.isEmpty() -> return showError( "Give your character a name." )
			freeSlot == null -> return showError( "There is no free character slot. Delete a character first." )
		}
		if( !create.isEnabled )
			return
		error.visibility = View.GONE
		create.isEnabled = false
		createLabel.text = "Creating…"
		val look = listOf( hair, hairColor, sex, if( doram ) SUMMONER else 0 )
		thread( name = "create" ) {
			val result = runCatching {
				// the servers may still be starting after an app start
				ServerService.waitPorts( 6900, 6121 )
				CharServer.create( account, text, freeSlot!!, look[0], look[1], look[2], look[3] )
			}
			create.post {
				result.onSuccess { onCreated( it ) }.onFailure {
					create.isEnabled = true
					createLabel.text = "Create"
					showError( it.message ?: it.toString() )
				}
			}
		}
	}

	private fun showError( message: String ) {
		error.text = message
		error.visibility = View.VISIBLE
	}

	private fun section( label: String, extra: TextView? = null ) = Ui.section( context, label, extra ).apply {
		layoutParams = gap( 4f )
	}

	private fun choice( label: String, action: () -> Unit ) = Ui.text( context, 14f ).apply {
		text = label
		gravity = Gravity.CENTER
		isFocusable = true
		isClickable = true
		pad( context, 16f, 6f )
		setOnClickListener { action() }
	}

	private fun pill() = LinearLayout.LayoutParams( -2, Ui.dp( context, 34f ) ).apply { marginEnd = Ui.dp( context, 4f ) }

	/** A touch button that does what a controller button does, its glyph beside it. */
	private fun action( glyph: String, label: String, onClick: () -> Unit ) = FrameLayout( context ).apply {
		isClickable = true
		background = Ui.focusable( context, 0, 16f )
		pad( context, 6f, 4f )
		addView( LinearLayout( context ).apply {
			gravity = Gravity.CENTER_VERTICAL
			addView( Ui.glyph( context, glyph ) )
			addView( Ui.text( context, 18f, Ui.ACCENT ).apply { text = label; pad( context, 4f, 0f ) } )
		} )
		setOnClickListener { onClick() }
	}

	private fun gap( bottom: Float ) = LinearLayout.LayoutParams( -1, -2 ).apply { bottomMargin = Ui.dp( context, bottom ) }
}
