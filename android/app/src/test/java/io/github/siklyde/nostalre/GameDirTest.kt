package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameDirTest {
	@get:Rule val tmp = TemporaryFolder()

	/** A file with a GRF header of [version]: 0x300 (kRO's 2025 client, "Event Horizon") or 0x200 (an older one). */
	private fun grf( dir: File, name: String, version: Int = 0x300 ) {
		val header = ByteArray( 46 )
		val magic = if( version == 0x300 ) "Event Horizon" else "Master of Magic"
		magic.toByteArray( Charsets.ISO_8859_1 ).copyInto( header )
		header[42] = ( version and 0xff ).toByte()
		header[43] = ( version shr 8 and 0xff ).toByte()
		File( dir, name ).writeBytes( header )
	}

	private fun names( dir: File ) = GameDir.archives( dir ).map { it.name }

	@Test fun warpgateFolderGetsOfficialDataAfterItsOwnList() {
		val dir = tmp.newFolder()
		File( dir, "DATA.INI" ).writeText( "[Data]\r\n1=data.grf" ) // as WARPGATE writes it: CRLF, no newline at the end
		grf( dir, "data.grf" )
		grf( dir, "official_data.grf", 0x200 )
		assertEquals( listOf( "data.grf", "official_data.grf" ), names( dir ) )
		assertNull( GameDir.problem( dir ) )
		assertNull( GameDir.warning( dir ) )
	}

	@Test fun withoutDataIniDataGrfThenOfficialData() {
		val dir = tmp.newFolder()
		grf( dir, "data.grf" )
		grf( dir, "official_data.grf", 0x200 )
		assertEquals( listOf( "data.grf", "official_data.grf" ), names( dir ) )
		assertNull( GameDir.problem( dir ) )
	}

	@Test fun aDataIniNamingOfficialDataInAnyCaseIsNotDoubled() {
		val dir = tmp.newFolder()
		File( dir, "DATA.INI" ).writeText( "[Data]\n1=data.grf\n2=Official_Data.grf\n" )
		grf( dir, "data.grf" )
		grf( dir, "Official_Data.grf", 0x200 )
		grf( dir, "official_data.grf", 0x200 )
		assertEquals( listOf( "data.grf", "Official_Data.grf" ), names( dir ) )
	}

	@Test fun dataIniOrderIsKeptAndOtherKeysIgnored() {
		val dir = tmp.newFolder()
		File( dir, "DATA.INI" ).writeText( "[Data]\n2=rdata.grf\nname=x\n1=data.grf\n" )
		assertEquals( listOf( "data.grf", "rdata.grf" ), names( dir ) )
	}

	@Test fun anEmptyDataIniReadsDataGrf() {
		val dir = tmp.newFolder()
		File( dir, "DATA.INI" ).writeText( "[Data]\n" )
		grf( dir, "data.grf" )
		assertEquals( listOf( "data.grf" ), names( dir ) )
		assertNull( GameDir.problem( dir ) )
	}

	@Test fun aFolderWithoutDataGrfSaysSo() {
		val dir = tmp.newFolder()
		assertTrue( GameDir.problem( dir )!!.startsWith( "data.grf is not in" ) )
		assertFalse( GameDir.valid( dir ) )
	}

	@Test fun anArchiveDataIniListsButTheFolderLacksIsNamed() {
		val dir = tmp.newFolder()
		File( dir, "DATA.INI" ).writeText( "[Data]\n1=data.grf\n2=rdata.grf\n" )
		grf( dir, "data.grf" )
		assertTrue( GameDir.problem( dir )!!.startsWith( "rdata.grf is not in" ) )
	}

	@Test fun anOlderClientsArchiveIsRefused() {
		val dir = tmp.newFolder()
		grf( dir, "data.grf", 0x200 )
		assertTrue( GameDir.problem( dir )!!.contains( "older client" ) )
	}

	@Test fun aFileThatIsNoGrfIsRefused() {
		val dir = tmp.newFolder()
		File( dir, "data.grf" ).writeText( "not an archive" )
		assertNotNull( GameDir.problem( dir ) )
	}

	@Test fun missingOfficialDataIsAWarningNotAProblem() {
		val dir = tmp.newFolder()
		grf( dir, "data.grf" )
		assertNull( GameDir.problem( dir ) )
		assertTrue( GameDir.warning( dir )!!.contains( GameDir.OFFICIAL_URL ) )
	}
}
