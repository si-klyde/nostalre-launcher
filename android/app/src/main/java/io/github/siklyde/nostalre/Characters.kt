package io.github.siklyde.nostalre

import android.content.Context

/** A character as last saved by the servers. */
class Character(
	val id: Int, val slot: Int, val name: String, val job: Int, val baseLevel: Int, val jobLevel: Int, val zeny: Int,
	val hp: Int, val maxHp: Int, val sp: Int, val maxSp: Int, val ap: Int, val maxAp: Int,
	val stats: List<Pair<String, Int>>, val traits: List<Pair<String, Int>>, val map: String,
	val sex: Int, val hair: Int, val hairColor: Int, val lastLogin: String?,
) {
	val jobName get() = Characters.jobName( job )
	val isDoram get() = job in JobNames.DORAM
}

object Characters {
	private val STATS = listOf( "str", "agi", "vit", "int", "dex", "luk" )
	private val TRAITS = listOf( "pow", "sta", "wis", "spl", "con", "crt" )

	fun jobName( job: Int ): String = JobNames.NAMES[job] ?: "Job $job"

	fun list( context: Context, accountId: Int ): List<Character> = SaveDb.open( context ).use { db ->
		val columns = listOf( "char_id", "char_num", "name", "class", "base_level", "job_level", "zeny", "hp", "max_hp", "sp", "max_sp",
			"ap", "max_ap", "last_map", "sex", "hair", "hair_color", "last_login" ) + STATS + TRAITS
		db.rawQuery(
			"SELECT ${columns.joinToString { "`$it`" }} FROM `char` WHERE account_id = ? AND delete_date = 0 ORDER BY char_num",
			arrayOf( accountId.toString() )
		).use { c ->
			fun int( column: String ) = c.getInt( columns.indexOf( column ) )
			fun string( column: String ): String? = columns.indexOf( column ).let { if( c.isNull( it ) ) null else c.getString( it ) }
			generateSequence { if( c.moveToNext() ) c else null }.map {
				Character( int( "char_id" ), int( "char_num" ), string( "name" ).orEmpty(), int( "class" ), int( "base_level" ),
					int( "job_level" ), int( "zeny" ), int( "hp" ), int( "max_hp" ), int( "sp" ), int( "max_sp" ), int( "ap" ), int( "max_ap" ),
					STATS.map { it.uppercase() to int( it ) }, TRAITS.map { it.uppercase() to int( it ) }, string( "last_map" ).orEmpty(),
					if( string( "sex" ) == "F" ) CharacterSprite.FEMALE else CharacterSprite.MALE, int( "hair" ), int( "hair_color" ),
					string( "last_login" ) )
			}.toList()
		}
	}
}
