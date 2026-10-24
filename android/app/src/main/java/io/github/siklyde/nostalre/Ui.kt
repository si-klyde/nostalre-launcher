package io.github.siklyde.nostalre

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Colours, type and drawables shared by the app's own screens. */
object Ui {
	const val NIGHT = 0xFF1C2335.toInt()     // background
	const val SLATE = 0xFF283149.toInt()     // panels, buttons
	const val SLATE_LIGHT = 0xFF35405C.toInt()
	const val PARCHMENT = 0xFFEFE7D2.toInt() // text, focus ring
	const val MUTED = 0xFF8F98AE.toInt()
	const val HP = 0xFF5DBB46.toInt()
	const val SP = 0xFF4C8EDB.toInt()
	const val GOLD = 0xFFE2B34A.toInt()      // zeny and Play only
	const val ERROR = 0xFFE58A7A.toInt()
	const val ACCENT = 0xFFD2EAFC.toInt()    // RO's pale blue: section labels, the cursor, focus
	const val DIM = 0xFFAAB4CC.toInt()       // labels beside values
	const val AP = 0xFFE8914A.toInt()
	const val PICK = 0xBF8294C8.toInt()      // the chosen row's band, fading out to the right

	private var regular: Typeface? = null
	private var bold: Typeface? = null

	/** Noto Sans, the font nostalRE draws its own text in (lib/renderer/src/fonts): the hub reads like the game. */
	fun regular( context: Context ): Typeface = regular ?: context.resources.getFont( R.font.noto_sans ).also { regular = it }

	/** Noto Sans Bold: names, numbers, the chosen option. */
	fun bold( context: Context ): Typeface = bold ?: context.resources.getFont( R.font.noto_sans_bold ).also { bold = it }

	fun dp( context: Context, value: Float ): Int =
		TypedValue.applyDimension( TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics ).toInt()

	fun text( context: Context, size: Float, color: Int = PARCHMENT, typeface: Typeface? = null ) = TextView( context ).apply {
		textSize = size
		setTextColor( color )
		this.typeface = typeface ?: regular( context )
		// Noto's ascent and descent padding: lines as tight as the game's, more room for the characters
		includeFontPadding = false
	}

	fun rounded( context: Context, color: Int, radius: Float = 8f, stroke: Int = 0 ) = GradientDrawable().apply {
		setColor( color )
		cornerRadius = dp( context, radius ).toFloat()
		if( stroke != 0 )
			setStroke( dp( context, 3f ), stroke )
	}

	/**
	 * Background for anything the gamepad can focus: [color] fill, a parchment ring when focused (or [selected]),
	 * a ripple on touch.
	 */
	fun focusable( context: Context, color: Int, radius: Float = 8f, selected: Boolean = false ): Drawable {
		val states = StateListDrawable().apply {
			addState( intArrayOf( android.R.attr.state_focused ), rounded( context, color, radius, PARCHMENT ) )
			addState( intArrayOf(), rounded( context, color, radius, if( selected ) MUTED else 0 ) )
		}
		// mask: the ripple shape, opaque also over a transparent fill
		return RippleDrawable( ColorStateList.valueOf( 0x33FFFFFF ), states, rounded( context, PARCHMENT, radius ) )
	}

	/** Full screen: status and navigation bars hidden, a swipe from the edge shows them for a moment. */
	fun hideSystemBars( window: Window ) {
		window.insetsController?.apply {
			hide( WindowInsets.Type.systemBars() )
			systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}

	/** Shows a dialog without bringing back the system bars the app hides. */
	fun show( builder: AlertDialog.Builder ): AlertDialog = builder.create().apply {
		// not focusable while shown: a focused dialog window makes the bars appear
		window?.setFlags( WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE )
		show()
		window?.let { hideSystemBars( it ) }
		window?.clearFlags( WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE )
	}

	/** A hairline under a section label, fading out to the right like the in-game menu's. */
	fun rule( context: Context ) = View( context ).apply {
		background = GradientDrawable( GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf( 0x73D2EAFC, 0x00D2EAFC ) )
		minimumHeight = 1
	}

	/** A band behind the chosen entry (pill, row), fading out to the right. */
	fun pick( context: Context, radius: Float = 17f ) = GradientDrawable( GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf( PICK, 0x408294C8 ) ).apply {
		cornerRadius = dp( context, radius ).toFloat()
	}

	/** A controller button's glyph (A, L1, ≡) in a small round badge: the hint beside an action. */
	fun glyph( context: Context, label: String, color: Int = PARCHMENT ) = text( context, 11f, color, bold( context ) ).apply {
		text = label
		gravity = android.view.Gravity.CENTER
		minWidth = dp( context, 22f )
		background = GradientDrawable().apply {
			cornerRadius = dp( context, 10f ).toFloat()
			setColor( 0x14FFFFFF )
			setStroke( dp( context, 1f ), if( color == PARCHMENT ) 0xFF9AA6C8.toInt() else color )
		}
		setPadding( dp( context, 6f ), dp( context, 1f ), dp( context, 6f ), dp( context, 1f ) )
	}

	/** The chosen option's band, or none; a ring when the controller is on it. */
	fun choice( context: Context, chosen: Boolean ): Drawable {
		val states = StateListDrawable().apply {
			addState( intArrayOf( android.R.attr.state_focused ), ( if( chosen ) pick( context ) else rounded( context, 0x14FFFFFF, 17f ) ).apply {
				setStroke( dp( context, 2f ), ACCENT )
			} )
			addState( intArrayOf(), if( chosen ) pick( context ) else rounded( context, 0, 17f ) )
		}
		return RippleDrawable( ColorStateList.valueOf( 0x33FFFFFF ), states, rounded( context, PARCHMENT, 17f ) )
	}

	/** A section label in the accent, letter spaced, with its rule running to the right (and [extra] after it). */
	fun section( context: Context, label: String, extra: View? = null ) = LinearLayout( context ).apply {
		gravity = android.view.Gravity.CENTER_VERTICAL
		addView( text( context, 11f, ACCENT ).apply {
			text = label
			letterSpacing = 0.25f
		} )
		addView( rule( context ), LinearLayout.LayoutParams( 0, dp( context, 1f ), 1f ).apply { marginStart = dp( context, 10f ) } )
		if( extra != null )
			addView( extra, LinearLayout.LayoutParams( -2, -2 ).apply { marginStart = dp( context, 10f ) } )
	}

	/** "CHARACTERS ›" dim, then where you are in the accent. */
	fun breadcrumb( context: Context, parent: String?, here: String ) = text( context, 12f, ACCENT ).apply {
		letterSpacing = 0.3f
		text = android.text.SpannableStringBuilder().apply {
			if( parent != null ) {
				append( "$parent ", android.text.style.ForegroundColorSpan( DIM ), 0 )
			}
			append( here )
		}
	}

	/**
	 * A controller button's action: its glyphs and what it does, frameless. With [onClick] it is a touch button too
	 * (at least 44 dp tall, a ripple where it is touched), [primary] in gold.
	 */
	fun hint( context: Context, glyphs: List<String>, label: String, primary: Boolean = false, onClick: ( () -> Unit )? = null ) =
		LinearLayout( context ).apply {
			gravity = android.view.Gravity.CENTER_VERTICAL
			val color = if( primary ) GOLD else if( onClick != null ) PARCHMENT else DIM
			for( button in glyphs )
				addView( glyph( context, button, if( primary ) GOLD else PARCHMENT ), LinearLayout.LayoutParams( -2, -2 ).apply { marginEnd = dp( context, 5f ) } )
			addView( text( context, if( onClick != null ) 15f else 11f, color, if( primary ) bold( context ) else null ).apply { text = label } )
			if( onClick != null ) {
				isClickable = true
				minimumHeight = dp( context, 44f )
				background = focusable( context, 0, 22f )
				setPadding( dp( context, 8f ), 0, dp( context, 8f ), 0 )
				setOnClickListener { onClick() }
			}
		}

	/** The label of a [hint], to change what it says. */
	fun hintLabel( hint: LinearLayout ) = hint.getChildAt( hint.childCount - 1 ) as TextView

	/**
	 * A full screen of the hub, frameless like the game's menu: [title] and [corner] on the top line over a rule,
	 * [body], then a rule over [help] and the [hints] of what the buttons do.
	 */
	fun screen( context: Context, title: View, corner: View?, body: View, help: View, hints: List<View> ) = LinearLayout( context ).apply {
		orientation = LinearLayout.VERTICAL
		background = GradientDrawable( GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf( 0xFF222B42.toInt(), NIGHT, 0xFF121726.toInt() ) )
		setPadding( dp( context, 28f ), dp( context, 12f ), dp( context, 28f ), dp( context, 8f ) )
		addView( LinearLayout( context ).apply {
			gravity = android.view.Gravity.CENTER_VERTICAL
			minimumHeight = dp( context, 32f )
			addView( title, LinearLayout.LayoutParams( 0, -2, 1f ) )
			if( corner != null ) addView( corner )
		} )
		addView( rule( context ), LinearLayout.LayoutParams( -1, dp( context, 1f ) ).apply { bottomMargin = dp( context, 10f ) } )
		addView( body, LinearLayout.LayoutParams( -1, 0, 1f ) )
		addView( rule( context ), LinearLayout.LayoutParams( -1, dp( context, 1f ) ).apply { topMargin = dp( context, 6f ) } )
		addView( LinearLayout( context ).apply {
			gravity = android.view.Gravity.CENTER_VERTICAL
			minimumHeight = dp( context, 48f )
			addView( help, LinearLayout.LayoutParams( 0, -2, 1f ) )
			for( hint in hints )
				addView( hint, LinearLayout.LayoutParams( -2, -2 ).apply { marginStart = dp( context, 10f ) } )
		} )
	}

	/** One line, shrinking from [size] down to [minSize] sp when the width runs out (small screens). */
	fun TextView.fit( minSize: Int, size: Int ): TextView = apply {
		maxLines = 1
		setAutoSizeTextTypeUniformWithConfiguration( minSize, size, 1, TypedValue.COMPLEX_UNIT_SP )
	}

	fun View.pad( context: Context, horizontal: Float, vertical: Float ) =
		setPadding( dp( context, horizontal ), dp( context, vertical ), dp( context, horizontal ), dp( context, vertical ) )
}
