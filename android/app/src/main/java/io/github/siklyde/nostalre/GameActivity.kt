package io.github.siklyde.nostalre

import android.app.NativeActivity

/** NativeActivity (libnostalre_client.so, see NativeGame) full screen like the rest of the app. */
class GameActivity : NativeActivity() {
	override fun onWindowFocusChanged( hasFocus: Boolean ) {
		super.onWindowFocusChanged( hasFocus )
		if( hasFocus )
			Ui.hideSystemBars( window )
	}
}
