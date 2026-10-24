package io.github.siklyde.nostalre

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** The one player account: named by the user, same password everywhere (offline game, nothing to protect). */
object Account {
	const val PASSWORD = "ragnarokoffline"
	private const val GM_GROUP = 99

	/** rAthena's userid limit, kept to characters safe in SQL and packets. */
	val NAME = Regex( "[A-Za-z0-9_]{4,23}" )

	private const val KEY_NAME = "username"

	fun name( context: Context ): String? = prefs( context ).getString( KEY_NAME, null )

	/**
	 * Saves [name] and makes the database match: the player account is renamed (its characters stay with it), or one
	 * is created. The servers' own account (sex 'S') is left alone.
	 */
	fun setName( context: Context, name: String ) {
		SaveDb.open( context ).use { db ->
			val values = ContentValues().apply {
				put( "userid", name )
				put( "user_pass", PASSWORD )
			}
			val id = playerId( db )
			if( id != null ) {
				db.update( "login", values, "account_id = ?", arrayOf( id.toString() ) )
			} else {
				values.put( "sex", "M" )
				values.put( "email", "a@a.com" )
				db.insertOrThrow( "login", null, values )
			}
		}
		prefs( context ).edit().putString( KEY_NAME, name ).apply()
	}

	fun id( context: Context ): Int? = SaveDb.open( context ).use { playerId( it ) }

	fun isGm( context: Context ): Boolean = SaveDb.open( context ).use { db ->
		val id = playerId( db ) ?: return false
		db.rawQuery( "SELECT group_id FROM login WHERE account_id = ?", arrayOf( id.toString() ) )
			.use { it.moveToFirst() && it.getInt( 0 ) >= GM_GROUP }
	}

	/** Read by the login-server at the next login. */
	fun setGm( context: Context, gm: Boolean ) {
		SaveDb.open( context ).use { db ->
			val id = playerId( db ) ?: return
			db.update( "login", ContentValues().apply { put( "group_id", if( gm ) GM_GROUP else 0 ) }, "account_id = ?", arrayOf( id.toString() ) )
		}
	}

	/** The player account: the first one that is not the servers' (a database from elsewhere may have more). */
	private fun playerId( db: SQLiteDatabase ): Int? =
		db.rawQuery( "SELECT MIN(account_id) FROM login WHERE sex != 'S'", null ).use { if( it.moveToFirst() && !it.isNull( 0 ) ) it.getInt( 0 ) else null }

	private fun prefs( context: Context ) = context.getSharedPreferences( "account", Context.MODE_PRIVATE )
}
