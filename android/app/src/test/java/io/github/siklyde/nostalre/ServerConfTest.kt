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

	@Test fun officialRatesWriteOneHundredEverywhere() {
		val conf = parse( ServerConf.ratesFile( ServerConf.Rates() ) )
		assertEquals( "100", conf["base_exp_rate"] )
		assertEquals( "100", conf["job_exp_rate"] )
		assertEquals( "100", conf["mvp_exp_rate"] )
		for( key in dropKeys + cardKeys )
			assertEquals( key, "100", conf[key] )
	}

	@Test fun eachRateGoesToItsOwnKeys() {
		val conf = parse( ServerConf.ratesFile( ServerConf.Rates( baseExp = 10, jobExp = 3, drop = 5, card = 1000 ) ) )
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
		val known = parse( File( battle, "exp.conf" ).readText() ).keys + parse( File( battle, "drops.conf" ).readText() ).keys
		val written = parse( ServerConf.ratesFile( ServerConf.Rates( 2, 2, 2, 2 ) ) ).keys
		assertEquals( 3 + dropKeys.size + cardKeys.size, written.size )
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
