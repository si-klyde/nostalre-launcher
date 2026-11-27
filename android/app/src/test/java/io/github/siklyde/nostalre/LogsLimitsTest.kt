package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The edges of [Logs.tail] and [Logs.report]: the limits they promise and how the room is shared. */
class LogsLimitsTest {
	@get:Rule val tmp = TemporaryFolder()

	// the sections LogExport builds: client.log and 4 servers, each with the run before, and logcat
	private val titles = listOf( "client.log", "login-server.log", "char-server.log", "map-server.log", "grf-server.log" )
		.flatMap { listOf( "$it (last run)", "$it (run before)" ) } + "system log (logcat)"

	private fun lines( n: Int, tag: String ) = ( 1..n ).joinToString( "\n" ) { "$tag line $it" }

	@Test fun tailStaysWithinALimitShorterThanItsNote() {
		val tail = Logs.tail( "x".repeat( 100 ), 5 )
		assertTrue( "tail of length ${tail.length} over limit 5: \"$tail\"", tail.length <= 5 )
	}

	@Test fun reportStaysUnderTheLimitWhenEachShareIsSmall() {
		val sections = titles.map { it to lines( 200, it ) }
		// room for the header and titles plus about 10 characters a section
		val fixed = "header\n".length + titles.sumOf { "\n===== $it =====\n".length + 1 }
		val limit = fixed + 10 * titles.size
		val report = Logs.report( "header", sections, limit )
		assertTrue( "report ${report.length} over limit $limit", report.length <= limit )
	}

	@Test fun reportStaysUnderTheLimitWithEveryLogMissing() {
		val sections = titles.map { it to null }
		val fixed = "header\n".length + titles.sumOf { "\n===== $it =====\n".length + 1 }
		val limit = fixed + 3 * titles.size
		val report = Logs.report( "header", sections, limit )
		assertTrue( "report ${report.length} over limit $limit", report.length <= limit )
	}

	@Test fun whitespaceOnlySectionLeavesItsRoomToTheOthers() {
		// a log that is only blank lines shows as "(none)" and needs nothing, so the real log should get the room
		val log = lines( 300, "map-server" ) // ~6000 chars: over the limit, under the blank log's raw length
		val report = Logs.report( "h", listOf( "map-server.log" to log, "blank.log" to "\n".repeat( 20_000 ) ), 4000 )
		val mapBody = report.substringAfter( "===== map-server.log =====\n" ).substringBefore( "\n===== blank.log" )
		assertTrue( "map-server got ${mapBody.length} of a 4000 limit (report ${report.length})", mapBody.length > 3500 )
	}

	@Test fun tailDoesNotSplitASurrogatePair() {
		// a character outside the BMP (emoji in a chat line, a player name) is two chars; the cut must not halve one
		val tail = Logs.tail( "😀".repeat( 100 ), 51 )
		val body = tail.removePrefix( "[earlier lines cut]\n" )
		assertFalse( "tail starts with a lone low surrogate", body.first().isLowSurrogate() )
	}

	// guardrails

	@Test fun tailAtExactlyTheLimitKeepsTheTextWhole() {
		val text = "a".repeat( 40 )
		assertEquals( text, Logs.tail( text, 40 ) )
	}

	@Test fun tailCutAtALineBoundaryKeepsThatWholeLine() {
		// 30 chars of room after the note: exactly "line B\n" plus 23 more of the last line
		val text = "x".repeat( 100 ) + "\n" + "line B" + "\n" + "y".repeat( 23 )
		val tail = Logs.tail( text, 20 + 31 )
		assertEquals( "[earlier lines cut]\nline B\n" + "y".repeat( 23 ), tail )
	}

	@Test fun tailWhoseOnlyLineBreakIsLastKeepsTheEndWithinTheLimit() {
		val tail = Logs.tail( "x".repeat( 100 ) + "\n", 50 )
		assertTrue( tail.length <= 50 )
		assertTrue( tail.startsWith( "[earlier lines cut]\n" ) )
	}

	@Test fun reportUnderTheLimitKeepsEverySectionWhole() {
		val sections = titles.map { it to lines( 5, it ) }
		val report = Logs.report( "header", sections, Logs.COPY_LIMIT )
		assertFalse( "[earlier lines cut]" in report )
		for( ( title, text ) in sections )
			assertTrue( "===== $title =====\n$text\n" in report )
	}

	@Test fun reportOfRealisticSessionStaysUnderCopyLimit() {
		val sections = titles.map { it to lines( 20_000, it ) }
		val report = Logs.report( "nostalRE logs\nApp: 0.3.1 (9)", sections, Logs.COPY_LIMIT )
		assertTrue( report.length <= Logs.COPY_LIMIT )
		// every section keeps its last line
		for( title in titles )
			assertTrue( "$title line 20000" in report )
	}

	@Test fun reportSharesEvenlyBetweenEqualLongSections() {
		val report = Logs.report( "h", listOf( "a" to lines( 1000, "a" ), "b" to lines( 1000, "b" ) ), 2000 )
		val a = report.substringAfter( "===== a =====\n" ).substringBefore( "\n===== b" ).length
		val b = report.substringAfter( "===== b =====\n" ).length
		assertTrue( "a $a, b $b", kotlin.math.abs( a - b ) < 50 )
	}

	@Test fun reportCrLfLogKeepsItsLastLine() {
		val log = ( 1..500 ).joinToString( "\r\n" ) { "crlf line $it" } + "\r\n"
		val report = Logs.report( "h", listOf( "win.log" to log ), 500 )
		assertTrue( report.length <= 500 )
		assertTrue( report.trimEnd().endsWith( "crlf line 500" ) )
	}

	@Test fun previousOfANameWithoutLogSuffix() =
		assertEquals( "nohup.out.prev.log", Logs.previous( File( "/x/nohup.out" ) ).name )

	@Test fun keepPreviousLeavesADirectoryAlone() {
		val dir = tmp.newFolder()
		val log = File( dir, "map-server.log" ).apply { mkdir() }
		Logs.keepPrevious( log )
		assertTrue( log.isDirectory )
		assertFalse( Logs.previous( log ).exists() )
	}
}
