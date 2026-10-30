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

	/**
	 * The servers' EXP and drop rates, as multiples of the official ones (1x), picked in the hub's settings. [drop]
	 * covers every item but cards, which have their own [card].
	 */
	data class Rates( val baseExp: Int = 1, val jobExp: Int = 1, val drop: Int = 1, val card: Int = 1 ) {
		fun toMap(): Map<String, Int> = mapOf( "base_exp" to baseExp, "job_exp" to jobExp, "drop" to drop, "card" to card )

		companion object {
			val STEPS = listOf( 1, 2, 3, 5, 10, 25, 50, 100, 250, 500, 1000 )

			/** Read back from [toMap]'s keys (the saved picks); a missing or unknown one is 1x. */
			fun from( saved: Map<String, *> ): Rates {
				fun pick( key: String ) = ( saved[key] as? Int )?.takeIf { it in STEPS } ?: 1
				return Rates( pick( "base_exp" ), pick( "job_exp" ), pick( "drop" ), pick( "card" ) )
			}
		}
	}

	// conf/battle/drops.conf: every item rate but the cards', and the cards'
	private val DROP_KEYS = listOf(
		"item_rate_common", "item_rate_common_boss", "item_rate_common_mvp",
		"item_rate_heal", "item_rate_heal_boss", "item_rate_heal_mvp",
		"item_rate_use", "item_rate_use_boss", "item_rate_use_mvp",
		"item_rate_equip", "item_rate_equip_boss", "item_rate_equip_mvp",
		"item_rate_mvp", "item_rate_adddrop", "item_group_rate", "item_rate_treasure",
	)
	private val CARD_KEYS = listOf( "item_rate_card", "item_rate_card_boss", "item_rate_card_mvp" )

	/** conf/import/battle_conf.txt for [rates]: rAthena's rates are percents, 100 for 1x. */
	fun ratesFile( rates: Rates ): String = buildString {
		appendLine( "// Written by the nostalRE app at every server start: the rates picked in its settings" )
		fun line( key: String, times: Int ) = appendLine( "$key: ${times * 100}" )
		line( "base_exp_rate", rates.baseExp )
		line( "mvp_exp_rate", rates.baseExp )
		line( "job_exp_rate", rates.jobExp )
		DROP_KEYS.forEach { line( it, rates.drop ) }
		CARD_KEYS.forEach { line( it, rates.card ) }
	}

	private fun ratePrefs( context: Context ) = context.getSharedPreferences( "rates", Context.MODE_PRIVATE )

	fun rates( context: Context ): Rates = Rates.from( ratePrefs( context ).all )

	fun saveRates( context: Context, rates: Rates ) =
		ratePrefs( context ).edit().apply { rates.toMap().forEach { ( key, value ) -> putInt( key, value ) } }.apply()

	/** The saved rates into the servers' import file; the app's updates unpack the servers' files afresh, so before every start. */
	fun writeRates( context: Context ) {
		val file = File( Paths( context ).server, "conf/import/battle_conf.txt" )
		file.parentFile?.mkdirs()
		file.writeText( ratesFile( rates( context ) ) )
	}

	/** The map a new Human (or [doram]) character starts on: the first of start_point (start_point_doram). */
	fun startMap( context: Context, doram: Boolean ): String? =
		char( context )[if( doram ) "start_point_doram" else "start_point"]?.substringBefore( ',' )?.trim()?.takeIf { it.isNotEmpty() }
}
