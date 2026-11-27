package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogsTest {
	@get:Rule val tmp = TemporaryFolder()

	@Test fun keepPreviousMovesTheLogAndReplacesTheOlderOne() {
		val dir = tmp.newFolder()
		val log = File( dir, "map-server.log" ).apply { writeText( "crash run" ) }
		File( dir, "map-server.prev.log" ).writeText( "older run" )
		Logs.keepPrevious( log )
		assertFalse( log.exists() )
		assertEquals( "crash run", File( dir, "map-server.prev.log" ).readText() )
	}

	@Test fun keepPreviousWithoutALogKeepsTheOlderOne() {
		val dir = tmp.newFolder()
		File( dir, "client.prev.log" ).writeText( "older run" )
		Logs.keepPrevious( File( dir, "client.log" ) )
		assertEquals( "older run", File( dir, "client.prev.log" ).readText() )
	}

	@Test fun tailKeepsShortTextWhole() = assertEquals( "a\nb\n", Logs.tail( "a\nb\n", 10 ) )

	@Test fun tailKeepsTheEndFromALineStart() {
		val text = ( 1..100 ).joinToString( "\n" ) { "line $it" }
		val tail = Logs.tail( text, 60 )
		assertTrue( tail.length <= 60 )
		assertTrue( tail.startsWith( "[earlier lines cut]\nline " ) )
		assertTrue( tail.endsWith( "line 100" ) )
	}

	@Test fun tailOfOneLongLineKeepsItsEnd() {
		val tail = Logs.tail( "x".repeat( 500 ) + "END", 50 )
		assertEquals( 50, tail.length )
		assertTrue( tail.endsWith( "END" ) )
	}

	@Test fun reportStaysUnderTheLimitAndGivesShortSectionsWhole() {
		val long = ( 1..5000 ).joinToString( "\n" ) { "map-server line $it" }
		val report = Logs.report( "header", listOf( "client.log" to "short\n", "map-server.log" to long, "missing" to null ), 2000 )
		assertTrue( report.length <= 2000 )
		assertTrue( "===== client.log =====\nshort\n" in report )
		assertTrue( "===== missing =====\n(none)\n" in report )
		assertTrue( "map-server line 5000" in report )
		// the long one gets what the others left: most of the limit
		assertTrue( report.substringAfter( "===== map-server.log =====" ).length > 1500 )
	}

	@Test fun reportKeepsSectionOrder() {
		val report = Logs.report( "h", listOf( "b" to "x".repeat( 100 ), "a" to "y" ), 1000 )
		assertTrue( report.indexOf( "===== b" ) < report.indexOf( "===== a" ) )
	}
}
