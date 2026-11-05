package io.github.siklyde.nostalre

import android.app.NativeActivity
import android.os.Bundle
import android.view.ViewGroup

/** NativeActivity (libnostalre_client.so, see NativeGame) full screen like the rest of the app. */
class GameActivity : NativeActivity() {
	override fun onCreate( savedInstanceState: Bundle? ) {
		super.onCreate( savedInstanceState )
		// the client's show of the on-screen keyboard names NativeActivity's content view, which cannot take focus by
		// itself: the window's focus stays on the DecorView and Android refuses the show ("is not served")
		findViewById<ViewGroup>( android.R.id.content ).getChildAt( 0 )?.apply {
			isFocusable = true
			isFocusableInTouchMode = true
			requestFocus()
		}
	}

	override fun onWindowFocusChanged( hasFocus: Boolean ) {
		super.onWindowFocusChanged( hasFocus )
		if( hasFocus )
			Ui.hideSystemBars( window )
	}
}
