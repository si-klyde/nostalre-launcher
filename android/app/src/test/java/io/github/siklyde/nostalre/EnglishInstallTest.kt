package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishInstallTest {
	@get:Rule val tmp = TemporaryFolder()

	private fun file( dir: File, path: String, text: String ) =
		File( dir, path ).apply { parentFile!!.mkdirs(); writeText( text ) }

	// --- loose files: the game folder's own data/ over the English text

	@Test fun playerFilesWinOverEnglish() {
		val game = tmp.newFolder( "game", "data" )
		val english = tmp.newFolder( "english", "data" )
		file( game, "msgstringtable.txt", "player's own" )
		file( english, "msgstringtable.txt", "english" )
		file( english, "skillnametable.txt", "english only" )
		val layers = LooseFiles.layers( listOf( game, english ) )
		assertEquals( "player's own", layers.getValue( "msgstringtable.txt" ).readText() )
		assertEquals( "english only", layers.getValue( "skillnametable.txt" ).readText() )
	}

	@Test fun mirrorLayersEnglishUnderGameFolder() {
		val game = tmp.newFolder( "game", "data" )
		val english = tmp.newFolder( "english", "data" )
		// 유저인터페이스 as CP949 bytes spelled in Latin-1, as ROenglishRE and kRO folders name it
		val ui = "texture/À¯ÀúÀÎÅÍÆäÀÌ½º"
		file( english, "$ui/btn_ok.bmp", "english button" )
		file( english, "$ui/btn_skill.bmp", "english skill" )
		file( game, "$ui/btn_ok.bmp", "player's button" )
		val layers = LooseFiles.layers( listOf( game, english ) )
		assertEquals( "player's button", layers.getValue( "texture/유저인터페이스/btn_ok.bmp" ).readText() )
		assertEquals( "english skill", layers.getValue( "texture/유저인터페이스/btn_skill.bmp" ).readText() )
		assertEquals( 2, layers.size )
	}

	@Test fun missingLayersAreSkipped() {
		val game = tmp.newFolder( "game", "data" )
		file( game, "a.txt", "a" )
		assertEquals( setOf( "a.txt" ), LooseFiles.layers( listOf( game, File( tmp.root, "none" ) ) ).keys )
	}

	@Test fun grfServerArgsLayerEnglishAfterGameFolder() {
		val game = File( "/storage/emulated/0/kRO" )
		val archives = listOf( File( game, "data.grf" ), File( game, "official_data.grf" ) )
		assertEquals(
			listOf( game.path, "8001", "--loose", "/data/files/english", "${game.path}/data.grf", "${game.path}/official_data.grf" ),
			LooseFiles.grfServerArgs( game, File( "/data/files/english" ), archives ),
		)
		assertEquals(
			listOf( game.path, "8001", "${game.path}/data.grf", "${game.path}/official_data.grf" ),
			LooseFiles.grfServerArgs( game, null, archives ),
		)
	}

	// --- install: download, import, clean up

	private val dirs by lazy { EnglishText.Dirs( tmp.newFolder( "files" ) ) }
	private val db by lazy { tmp.newFolder( "db" ) }
	private val statuses = mutableListOf<EnglishText.Status>()
	private val imports = mutableListOf<List<File>>()

	/** Writes a source file like the downloader, reports progress */
	private val download: ( File, ( EnglishDownload.Progress ) -> Unit ) -> Unit = { src, progress ->
		file( src, "Translation/Renewal/data/msgstringtable.txt", "Do you agree?#\n" )
		progress( EnglishDownload.Progress( 1, 2, 10, 20 ) )
		progress( EnglishDownload.Progress( 2, 2, 20, 20 ) )
	}

	/** Writes an output like `nostalre-english import` */
	private val goodImport: ( File, File, File ) -> EnglishInstall.Result = { src, db, out ->
		imports += listOf( src, db, out )
		file( out, ".nostalre-english", "" )
		file( out, "msgstringtable.txt", "Do you agree?#\n" )
		EnglishInstall.Result( 0, "" )
	}

	private fun install( download: ( File, ( EnglishDownload.Progress ) -> Unit ) -> Unit = this.download, import: ( File, File, File ) -> EnglishInstall.Result = goodImport ) =
		EnglishInstall( dirs, db, download, import ).run { synchronized( statuses ) { statuses += it } }

	@Test fun installRunsDownloadImportCleanup() {
		install()
		assertEquals( listOf( listOf( dirs.src, db, dirs.data ) ), imports )
		assertFalse( "downloaded source removed", dirs.src.exists() )
		assertTrue( File( dirs.data, "msgstringtable.txt" ).isFile )
		assertEquals( EnglishText.Status.Installed( English.SHA ), EnglishText.status( dirs ) )
	}

	@Test fun failedImportKeepsOldEnglish() {
		file( dirs.data, ".nostalre-english", "" )
		file( dirs.data, "msgstringtable.txt", "old english" )
		dirs.version.writeText( "0123456789abcdef" )
		install( import = { _, _, _ -> EnglishInstall.Result( 1, "reading\nnostalre-english: missing itemInfo.lua\n\n" ) } )
		assertEquals( EnglishText.Status.Failed( "nostalre-english: missing itemInfo.lua" ), statuses.last() )
		assertEquals( "old english", File( dirs.data, "msgstringtable.txt" ).readText() )
		assertEquals( EnglishText.Status.Installed( "0123456789abcdef" ), EnglishText.status( dirs ) )
		assertTrue( "source kept for the next try", dirs.src.exists() )
	}

	@Test fun statusFollowsInstall() {
		assertEquals( EnglishText.Status.NotInstalled, EnglishText.status( dirs ) )
		install()
		assertEquals(
			listOf(
				EnglishText.Status.Downloading( EnglishDownload.Progress( 0, 0, 0, 0 ) ),
				EnglishText.Status.Downloading( EnglishDownload.Progress( 1, 2, 10, 20 ) ),
				EnglishText.Status.Downloading( EnglishDownload.Progress( 2, 2, 20, 20 ) ),
				EnglishText.Status.Converting,
				EnglishText.Status.Installed( English.SHA ),
			),
			statuses,
		)
	}

	@Test fun failedDownloadSaysWhy() {
		install( download = { _, _ -> throw EnglishDownload.Failure( "Download of x failed: HTTP 500" ) } )
		assertEquals( EnglishText.Status.Failed( "Download of x failed: HTTP 500" ), statuses.last() )
		assertEquals( EnglishText.Status.NotInstalled, EnglishText.status( dirs ) )
	}

	@Test fun removeDeletesEnglish() {
		install()
		file( dirs.src, "left.txt", "x" )
		EnglishText.remove( dirs )
		assertFalse( dirs.root.exists() )
		assertFalse( dirs.src.exists() )
		assertEquals( EnglishText.Status.NotInstalled, EnglishText.status( dirs ) )
	}

	@Test fun offersEnglishOnceAfterFolderPick() {
		var offered = false
		val offer = { installed: Boolean -> EnglishText.offerOnce( installed, { offered }, { offered = true } ) }
		assertFalse( "installed: nothing to offer", offer( true ) )
		assertFalse( offered )
		assertTrue( offer( false ) )
		assertFalse( "only once", offer( false ) )
	}
}
