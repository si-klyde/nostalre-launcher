package io.github.siklyde.nostalre

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.siklyde.nostalre.Ui.fit
import io.github.siklyde.nostalre.Ui.pad
import kotlin.concurrent.thread

/**
 * The hub's settings, a screen like character creation's: the servers' rates and town helpers ([ServerConf]), and
 * GM commands ([Account]). GM commands save at once; rates and helpers when the screen closes, [onClose] told whether
 * they changed (the servers restart with them).
 *
 * Controller: the D-pad moves between choices, A sets the one it is on, B or Start goes back ([Hub]). Touch: tap.
 */
class SettingsScreen(
	private val context: Context,
	isGm: Boolean,
	private val onClose: ( changed: Boolean ) -> Unit,
) {
	private val ratesBefore: ServerConf.Rates = ServerConf.rates( context )
	private var rates: ServerConf.Rates = ratesBefore
	private val offBefore: Set<ServerConf.Helper> = ServerConf.helpersOff( context )
	private val off: MutableSet<ServerConf.Helper> = offBefore.toMutableSet()
	private var gm = isGm

	private val changed: Boolean get() = rates != ratesBefore || off != offBefore

	private val help = Ui.text( context, 13f ).fit( 10, 13 )
	private val helpersOn = Ui.text( context, 11f, Ui.DIM )
	private val back = Ui.hint( context, listOf( "B" ), "Back" ) { close() }.apply { isFocusable = true }
	private val backLabel = Ui.hintLabel( back )
	private val account = Ui.text( context, 12f, Ui.DIM ).apply { text = Account.name( context ).orEmpty() }

	fun view(): View {
		val left = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			addView( section( "SERVER RATES" ) )
			addView( rateRow( "Base EXP", "Base experience from monsters (MVPs too), times the official rate.",
				{ rates.baseExp } ) { rates = rates.copy( baseExp = it ) } )
			addView( rateRow( "Job EXP", "Job experience from monsters, times the official rate.",
				{ rates.jobExp } ) { rates = rates.copy( jobExp = it ) } )
			addView( rateRow( "Item drops", "How often monsters drop items (all but cards). Each drop tops out at 100%.",
				{ rates.drop } ) { rates = rates.copy( drop = it ) } )
			addView( rateRow( "Card drops", "How often monsters drop their cards.",
				{ rates.card } ) { rates = rates.copy( card = it ) } )
			addView( section( "ACCOUNT" ).apply { ( layoutParams as LinearLayout.LayoutParams ).topMargin = Ui.dp( context, 10f ) } )
			addView( toggleRow( "GM commands", "@ commands in chat, like @warp and @item. Saved now; from your next game.",
				{ gm } ) { setGm( it ) } )
		}
		val right = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			addView( section( "TOWN HELPERS", helpersOn ) )
			for( helper in ServerConf.Helper.entries )
				addView( toggleRow( helper.label, helperHelp( helper ), { helper !in off } ) { on ->
					if( on ) off -= helper else off += helper
				} )
			addView( Ui.text( context, 12f, Ui.DIM ).apply {
				text = "Rates and helpers take effect when you leave this screen: the server restarts with them."
				setPadding( 0, Ui.dp( context, 8f ), 0, 0 )
			} )
		}
		val body = ScrollView( context ).apply {
			isVerticalScrollBarEnabled = false
			addView( LinearLayout( context ).apply {
				addView( left, LinearLayout.LayoutParams( 0, -2, 1f ) )
				addView( right, LinearLayout.LayoutParams( 0, -2, 1f ).apply { marginStart = Ui.dp( context, 36f ) } )
			} )
		}
		refresh()
		help.text = DEFAULT_HELP
		return Ui.screen( context,
			Ui.breadcrumb( context, "CHARACTERS ›", "SETTINGS" ),
			account,
			body,
			help,
			listOf( Ui.hint( context, listOf( "✥" ), "choose" ), Ui.hint( context, listOf( "A" ), "set" ), back ),
		)
	}

	/** The first rate's ‹, so the controller starts on the screen (as character creation starts on the name). */
	fun focusFirst() {
		first?.requestFocus()
	}

	/** B, Back or Start: saves what changed and goes back. */
	fun close() {
		if( changed ) {
			ServerConf.saveRates( context, rates )
			ServerConf.saveHelpersOff( context, off )
		}
		onClose( changed )
	}

	private fun update() {
		helpersOn.text = "${ServerConf.Helper.entries.size - off.size} of ${ServerConf.Helper.entries.size} on"
		backLabel.text = if( changed ) "Save and restart server" else "Back"
	}

	/** GM commands go to the account at once; not saved, the choice shows what the account still has. */
	private fun setGm( on: Boolean ) {
		gm = on
		thread( name = "gm" ) {
			if( runCatching { Account.setGm( context, on ) }.isFailure )
				help.post {
					gm = !on
					refresh()
					help.text = "GM commands were not saved: the save database is busy. Try again."
				}
		}
	}

	private val refreshers = mutableListOf<() -> Unit>()
	private var first: View? = null

	private fun refresh() {
		refreshers.forEach { it() }
		update()
	}

	/** A rate: its name, then ‹ the multiple › (the steps of [ServerConf.Rates.STEPS]). */
	private fun rateRow( label: String, about: String, get: () -> Int, set: ( Int ) -> Unit ): View {
		val steps = ServerConf.Rates.STEPS
		val value = Ui.text( context, 17f, typeface = Ui.bold( context ) ).apply { gravity = Gravity.CENTER }
		fun step( by: Int ) {
			val at = ( steps.indexOf( get() ).coerceAtLeast( 0 ) + by ).coerceIn( steps.indices )
			set( steps[at] )
			refresh()
		}
		val down = arrow( "‹", about ) { step( -1 ) }
		if( first == null )
			first = down
		val up = arrow( "›", about ) { step( 1 ) }
		refreshers += {
			val v = get()
			value.text = "${v}x"
			value.setTextColor( if( v == 1 ) Ui.PARCHMENT else Ui.ACCENT )
			down.alpha = if( v == steps.first() ) 0.35f else 1f
			up.alpha = if( v == steps.last() ) 0.35f else 1f
		}
		return row( label ).apply {
			addView( down, square() )
			addView( value, LinearLayout.LayoutParams( Ui.dp( context, 64f ), -2 ) )
			addView( up, square() )
		}
	}

	/** An on/off choice: its name, then the two pills, the chosen one banded. */
	private fun toggleRow( label: String, about: String, get: () -> Boolean, set: ( Boolean ) -> Unit ): View {
		val pills = listOf( true to "On", false to "Off" ).map { ( value, text ) ->
			value to Ui.text( context, 14f ).apply {
				this.text = text
				gravity = Gravity.CENTER
				isFocusable = true
				isClickable = true
				pad( context, 16f, 0f )
				onFocus( about )
				setOnClickListener {
					if( get() != value ) {
						set( value )
						refresh()
					}
				}
			}
		}
		refreshers += { pills.forEach { ( value, pill ) -> pill.background = Ui.choice( context, value == get() ) } }
		return row( label ).apply {
			pills.forEach { ( _, pill ) -> addView( pill, LinearLayout.LayoutParams( Ui.dp( context, 64f ), Ui.dp( context, 34f ) ).apply { marginStart = Ui.dp( context, 4f ) } ) }
		}
	}

	private fun row( label: String ) = LinearLayout( context ).apply {
		gravity = Gravity.CENTER_VERTICAL
		minimumHeight = Ui.dp( context, 44f )
		addView( Ui.text( context, 15f ).apply { text = label }, LinearLayout.LayoutParams( 0, -2, 1f ) )
	}

	/** ‹ or ›: a round touch target the controller can rest on. */
	private fun arrow( label: String, about: String, onClick: () -> Unit ) = Ui.text( context, 20f, Ui.ACCENT ).apply {
		text = label
		gravity = Gravity.CENTER
		isFocusable = true
		isClickable = true
		background = Ui.focusable( context, 0x14FFFFFF, 18f )
		onFocus( about )
		setOnClickListener { onClick() }
	}

	private fun TextView.onFocus( about: String ) = setOnFocusChangeListener { _, focused -> if( focused ) help.text = about }

	private fun square() = LinearLayout.LayoutParams( Ui.dp( context, 36f ), Ui.dp( context, 36f ) )

	private fun section( label: String, extra: View? = null ) = Ui.section( context, label, extra ).apply {
		layoutParams = LinearLayout.LayoutParams( -1, -2 ).apply { bottomMargin = Ui.dp( context, 2f ) }
	}

	private fun helperHelp( helper: ServerConf.Helper ) = when( helper ) {
		ServerConf.Helper.JOB_MASTER -> "Job Master, in Prontera: changes your job once you reach the levels, no quest."
		ServerConf.Helper.WARPER -> "Warper, in every town: takes you to towns, fields and dungeons."
		ServerConf.Helper.HEALER -> "Healer, in every town: fills HP and SP for free."
		ServerConf.Helper.STYLIST -> "Stylist, in Prontera: hair style, hair colour and clothes colour."
		ServerConf.Helper.RESET -> "Reset Girl, in Prontera: gives back your stat or skill points, 5,000 zeny each."
	}

	private companion object {
		const val DEFAULT_HELP = "Pick how the game's server plays. Your characters keep everything when it restarts."
	}
}
