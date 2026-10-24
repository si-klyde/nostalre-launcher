package io.github.siklyde.nostalre

import android.content.Context
import java.io.File

/** App private folders. server/ and client/ are replaced on every app update, save/ never. */
class Paths( context: Context ) {
	val server = File( context.filesDir, "server" ) // conf, db, npc: working directory of the servers
	val save = File( context.filesDir, "save" )     // ragnarok.db
	val logs = File( context.filesDir, "logs" )
	val snapshots = File( context.filesDir, "snapshots" ) // <char_id>.png, drawn by the game when left
	val bin = File( context.applicationInfo.nativeLibraryDir )
}
