package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerConfTest {
	/** "key: value" lines of a conf text, as rAthena reads them. */
	private fun parse( text: String ): Map<String, String> =
		text.lines().map { it.substringBefore( "//" ) }.filter { ':' in it }
			.associate { it.substringBefore( ':' ).trim() to it.substringAfter( ':' ).trim() }

	private val dropKeys = listOf(
		"item_rate_common", "item_rate_common_boss", "item_rate_common_mvp",
		"item_rate_heal", "item_rate_heal_boss", "item_rate_heal_mvp",
		"item_rate_use", "item_rate_use_boss", "item_rate_use_mvp",
		"item_rate_equip", "item_rate_equip_boss", "item_rate_equip_mvp",
		"item_rate_mvp", "item_rate_adddrop", "item_group_rate", "item_rate_treasure",
	)
	private val cardKeys = listOf( "item_rate_card", "item_rate_card_boss", "item_rate_card_mvp" )
	private val worldKeys = listOf(
		"population_engine_enable", "population_engine_max_count", "population_engine_density_pct",
		"mob_count_rate", "mob_spawn_delay",
	)

	private fun world( world: ServerConf.World ) = parse( ServerConf.battleFile( ServerConf.Rates(), world ) )

	@Test fun noSavedWorldMeansShellsOnAtTwoHundredAndOfficialMonsters() {
		val conf = world( ServerConf.World.from( emptyMap<String, Any>() ) )
		assertEquals( "1", conf["population_engine_enable"] )
		assertEquals( "200", conf["population_engine_max_count"] )
		assertEquals( "100", conf["population_engine_density_pct"] )
		assertEquals( "100", conf["mob_count_rate"] )
		assertEquals( "100", conf["mob_spawn_delay"] )
		assertEquals( "100", conf["base_exp_rate"] )
	}

	@Test fun eachWorldPickGoesToItsOwnKey() {
		val conf = world( ServerConf.World( ai = false, maxShells = 500, density = 50, monsters = 300, respawn = 10 ) )
		assertEquals( "0", conf["population_engine_enable"] )
		assertEquals( "500", conf["population_engine_max_count"] )
		assertEquals( "50", conf["population_engine_density_pct"] )
		assertEquals( "300", conf["mob_count_rate"] )
		assertEquals( "10", conf["mob_spawn_delay"] )
	}

	@Test fun respawnSpeedIsTheDelayDivided() {
		val delays = ServerConf.World.RESPAWN_STEPS.map { world( ServerConf.World( respawn = it ) )["mob_spawn_delay"] }
		assertEquals( listOf( "100", "50", "33", "20", "10" ), delays )
	}

	@Test fun worldStepsAsPlanned() {
		assertEquals( listOf( 50, 100, 200, 300, 500 ), ServerConf.World.MAX_STEPS )
		assertEquals( listOf( 50, 100, 150, 200, 300 ), ServerConf.World.DENSITY_STEPS )
		assertEquals( listOf( 50, 100, 150, 200, 300 ), ServerConf.World.MONSTER_STEPS )
		assertEquals( listOf( 1, 2, 3, 5, 10 ), ServerConf.World.RESPAWN_STEPS )
	}

	/** The ranges in the server's battle config table (src/custom/battle_config_init.inc): out of range is ignored. */
	@Test fun everyWorldStepIsInTheServersRange() {
		assertTrue( ServerConf.World.MAX_STEPS.all { it in 1..30000 } )
		assertTrue( ServerConf.World.DENSITY_STEPS.all { it in 10..300 } )
		assertTrue( ServerConf.World.MONSTER_STEPS.all { it >= 0 } )
		assertTrue( ServerConf.World.RESPAWN_STEPS.all { 100 / it >= 0 } )
	}

	@Test fun savedWorldReadsBack() {
		val world = ServerConf.World( ai = false, maxShells = 50, density = 300, monsters = 150, respawn = 5 )
		assertEquals( world, ServerConf.World.from( world.toMap() ) )
	}

	@Test fun aWorldPickOffTheStepsReadsAsDefault() {
		val saved = mapOf( "max_shells" to 7, "density" to 999, "monsters" to "x", "respawn" to 4 )
		assertEquals( ServerConf.World(), ServerConf.World.from( saved ) )
	}

	@Test fun officialRatesWriteOneHundredEverywhere() {
		val conf = parse( ServerConf.battleFile( ServerConf.Rates(), ServerConf.World() ) )
		assertEquals( "100", conf["base_exp_rate"] )
		assertEquals( "100", conf["job_exp_rate"] )
		assertEquals( "100", conf["mvp_exp_rate"] )
		for( key in dropKeys + cardKeys )
			assertEquals( key, "100", conf[key] )
	}

	@Test fun eachRateGoesToItsOwnKeys() {
		val conf = parse( ServerConf.battleFile( ServerConf.Rates( baseExp = 10, jobExp = 3, drop = 5, card = 1000 ), ServerConf.World() ) )
		assertEquals( "1000", conf["base_exp_rate"] )
		assertEquals( "1000", conf["mvp_exp_rate"] )
		assertEquals( "300", conf["job_exp_rate"] )
		for( key in dropKeys )
			assertEquals( key, "500", conf[key] )
		for( key in cardKeys )
			assertEquals( key, "100000", conf[key] )
	}

	/** Every key written is one the server's own battle confs set (a typo would be ignored by rAthena, silently). */
	@Test fun everyKeyIsARealSetting() {
		val battle = File( "../../server/conf/battle" )
		val known = listOf( "exp.conf", "drops.conf", "population_engine.conf", "monster.conf" )
			.flatMap { parse( File( battle, it ).readText() ).keys }.toSet()
		val written = parse( ServerConf.battleFile( ServerConf.Rates( 2, 2, 2, 2 ), ServerConf.World() ) ).keys
		assertEquals( 3 + dropKeys.size + cardKeys.size + worldKeys.size, written.size )
		for( key in written )
			assertTrue( key, key in known )
	}

	@Test fun noSavedPicksMeanOfficialRates() {
		assertEquals( ServerConf.Rates(), ServerConf.Rates.from( emptyMap<String, Any>() ) )
	}

	@Test fun savedPicksReadBack() {
		val rates = ServerConf.Rates( baseExp = 25, jobExp = 50, drop = 3, card = 1 )
		assertEquals( rates, ServerConf.Rates.from( rates.toMap() ) )
	}

	@Test fun aRateOffTheStepsReadsAsOfficial() {
		assertEquals( 1, ServerConf.Rates.from( mapOf( "base_exp" to 7 ) ).baseExp )
	}

	@Test fun stepsGoFromOneToAThousand() {
		assertEquals( listOf( 1, 2, 3, 5, 10, 25, 50, 100, 250, 500, 1000 ), ServerConf.Rates.STEPS )
	}

	@Test fun allHelpersOnWritesNoDelnpc() {
		assertTrue( ServerConf.helpersFile( emptySet() ).lines().none { it.startsWith( "delnpc:" ) } )
	}

	@Test fun eachHelperOffBecomesADelnpcLine() {
		val lines = ServerConf.helpersFile( setOf( ServerConf.Helper.JOB_MASTER, ServerConf.Helper.HEALER ) )
			.lines().filter { it.startsWith( "delnpc:" ) }
		assertEquals( listOf( "delnpc: npc/custom/jobmaster.txt", "delnpc: npc/custom/healer.txt" ), lines )
	}

	/** Each helper is one the server loads, and the off file comes after them (delnpc only drops a file already listed). */
	@Test fun theServerLoadsEveryHelperBeforeTheOffFile() {
		val conf = File( "../../server/npc/scripts_custom.conf" ).readLines().map { it.substringBefore( "//" ).trim() }
		val offAt = conf.indexOf( "import: npc/nostalre_off.conf" )
		assertTrue( "scripts_custom.conf imports npc/nostalre_off.conf", offAt >= 0 )
		for( helper in ServerConf.Helper.entries ) {
			val at = conf.indexOf( "npc: npc/custom/${helper.file}.txt" )
			assertTrue( helper.file, at in 0 until offAt )
		}
	}
}
