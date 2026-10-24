package io.github.siklyde.nostalre

import android.content.Context
import java.io.File

/**
 * Settings of the app's own rAthena servers (files/server/conf) as they read them: "key: value" lines of a conf
 * file, then its import file over it. What character creation offers comes from here, so it is what the char server
 * accepts.
 */
object ServerConf {
	/** conf/battle/client.conf, then conf/import/battle_conf.txt (battle_athena.conf reads them in that order). */
	fun battle( context: Context ): Map<String, String> = read( context, "battle/client.conf", "import/battle_conf.txt" )

	/** conf/char_athena.conf, then conf/import/char_conf.txt. */
	fun char( context: Context ): Map<String, String> = read( context, "char_athena.conf", "import/char_conf.txt" )

	private fun read( context: Context, vararg files: String ): Map<String, String> {
		val conf = File( Paths( context ).server, "conf" )
		val values = mutableMapOf<String, String>()
		for( name in files ) {
			val file = File( conf, name ).takeIf { it.isFile } ?: continue
			file.forEachLine { line ->
				val text = line.substringBefore( "//" )
				val colon = text.indexOf( ':' )
				if( colon > 0 )
					values[text.substring( 0, colon ).trim()] = text.substring( colon + 1 ).trim()
			}
		}
		return values
	}

	/** The hair a new character may have, as rAthena caps it (pc_max_hair_style: per sex and race). */
	class Hair( val male: IntRange, val female: IntRange, val doram: IntRange, val colors: IntRange ) {
		fun styles( sex: Int, doram: Boolean ) = when {
			doram -> this.doram
			sex == CharacterSprite.MALE -> male
			else -> female
		}
	}

	fun hair( context: Context ): Hair {
		val conf = battle( context )
		fun int( key: String, default: Int ) = conf[key]?.toIntOrNull() ?: default
		// style 0 draws as style 1 ("whatever suits the job"): not offered twice
		val first = maxOf( 1, int( "min_hair_style", 0 ) )
		val max = int( "max_hair_style", 23 )
		return Hair(
			first..int( "max_hair_style_male", max ),
			first..max,
			first..int( "max_hair_style_doram", 10 ),
			int( "min_hair_color", 0 )..int( "max_hair_color", 8 ),
		)
	}

	/** The map a new Human (or [doram]) character starts on: the first of start_point (start_point_doram). */
	fun startMap( context: Context, doram: Boolean ): String? =
		char( context )[if( doram ) "start_point_doram" else "start_point"]?.substringBefore( ',' )?.trim()?.takeIf { it.isNotEmpty() }
}
