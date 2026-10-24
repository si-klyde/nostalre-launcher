package io.github.siklyde.nostalre

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** The servers' SQLite database (WAL, so readable while they run). */
object SaveDb {
	fun file( context: Context ) = File( Paths( context ).save, "ragnarok.db" )

	fun open( context: Context ): SQLiteDatabase =
		// WAL like the servers: otherwise Android switches the journal mode of their database
		SQLiteDatabase.openDatabase( file( context ).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING ).apply {
			// the servers may hold the write lock for a moment
			rawQuery( "PRAGMA busy_timeout = 5000", null ).close()
		}
}
