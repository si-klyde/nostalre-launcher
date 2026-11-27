package io.github.siklyde.nostalre

import android.app.NativeActivity
import android.os.Bundle
import android.view.ViewGroup

/** NativeActivity (libnostalre_client.so, see NativeGame) full screen like the rest of the app. */
class GameActivity : NativeActivity() {
	override fun onCreate( savedInstanceState: Bundle? ) {
		super.onCreate( savedInstanceState )
		// NativeActivity's content view is not focusable, so the window's focus stays on the DecorView and the input
		// method ignores the client's showSoftInput (made on the content view) as "not served"
		val content = findViewById<ViewGroup>( android.R.id.content ).getChildAt( 0 )
		content.isFocusable = true
		content.isFocusableInTouchMode = true
		content.requestFocus()
	}

	override fun onWindowFocusChanged( hasFocus: Boolean ) {
		super.onWindowFocusChanged( hasFocus )
		if( hasFocus )
			Ui.hideSystemBars( window )
	}
}
