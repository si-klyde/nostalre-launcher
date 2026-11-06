package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class EnglishDownloadTest {
	@get:Rule val tmp = TemporaryFolder()

	/** A repository at the pinned commit: path -> contents, served by [FakeHttp] */
	private val files = linkedMapOf(
		"Translation/Renewal/data/msgstringtable.txt" to "Do you agree?#\n",
		"Translation/Renewal/data/book/prontera bible01.txt" to "In the beginning\n",
		"Translation/Renewal/data/texture/À¯ÀúÀÎÅÍÆäÀÌ½º/btn_skill_a.bmp" to "BM fake",
		"Translation/Compatibility/2021-10-28/Renewal/readme.md" to "marker",
		"README.md" to "not needed",
	)

	private fun treeJson( entries: Map<String, String> = files, truncated: Boolean = false ): String {
		val blobs = entries.entries.joinToString( "," ) { ( path, body ) ->
			val bytes = body.toByteArray()
			"""{"path":${quoteJson( path )},"mode":"100644","type":"blob","sha":"${English.blobSha( bytes )}","size":${bytes.size}}"""
		}
		return """{"sha":"${English.SHA}","truncated":$truncated,"tree":[{"path":"Translation","mode":"040000","type":"tree","sha":"x"},$blobs]}"""
	}

	private fun quoteJson( text: String ) = "\"" + text.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\""

	private open inner class FakeHttp( val tree: String = treeJson() ) : English.Http {
		val fetched = mutableListOf<String>()

		override fun get( url: String ): English.Response {
			synchronized( fetched ) { fetched += url }
			if( url == English.treeUrl() ) return English.Response( 200, tree.toByteArray() )
			val path = files.keys.firstOrNull { English.rawUrl( it ) == url } ?: return English.Response( 404, ByteArray( 0 ) )
			return English.Response( 200, body( path ) )
		}

		open fun body( path: String ): ByteArray = files.getValue( path ).toByteArray()
	}

	/** What `nostalre-english needed` prints for the fake tree: everything under Translation/ */
	private val needed: ( File ) -> List<String> = { tree ->
		assertTrue( "needed gets the saved listing", tree.readText().contains( English.SHA ) )
		files.keys.filter { it.startsWith( "Translation/" ) }
	}

	private val sleeps = mutableListOf<Long>()

	private fun download( http: English.Http, need: ( File ) -> List<String> = needed, free: Long = Long.MAX_VALUE ) =
		EnglishDownload( http, need, sleep = { synchronized( sleeps ) { sleeps += it } }, freeBytes = { free } )

	private fun failure( block: () -> Unit ): String {
		try {
			block()
		} catch( e: EnglishDownload.Failure ) {
			return e.message ?: ""
		}
		fail( "no failure" )
		return ""
	}

	@Test fun blobShaMatchesGit() {
		assertEquals( "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", English.blobSha( ByteArray( 0 ) ) )
		assertEquals( "ce013625030ba8dba906f756967f9e9ca394464a", English.blobSha( "hello\n".toByteArray() ) )
	}

	@Test fun rawUrlEncodesLikeQuote() {
		val base = "https://raw.githubusercontent.com/llchrisll/ROenglishRE/${English.SHA}/"
		// urllib.parse.quote(path) of each, from Python 3
		assertEquals(
			base + "Translation/Renewal/data/book/prontera%20bible01.txt",
			English.rawUrl( "Translation/Renewal/data/book/prontera bible01.txt" ),
		)
		assertEquals(
			base + "Translation/Renewal/data/texture/%C3%80%C2%AF%C3%80%C3%BA%C3%80%C3%8E%C3%85%C3%8D%C3%86%C3%A4%C3%80%C3%8C%C2%BD%C2%BA/btn_skill_a.bmp",
			English.rawUrl( "Translation/Renewal/data/texture/À¯ÀúÀÎÅÍÆäÀÌ½º/btn_skill_a.bmp" ),
		)
		assertEquals( base + "a-b_c.d~e/%23%25%2B%3F%26", English.rawUrl( "a-b_c.d~e/#%+?&" ) )
	}

	@Test fun fetchesOnlyNeeded() {
		val http = FakeHttp()
		val src = tmp.newFolder( "src" )
		download( http ).download( src )
		val wanted = files.keys.filter { it.startsWith( "Translation/" ) }
		assertEquals( listOf( English.treeUrl() ) + wanted.map { English.rawUrl( it ) }.sorted(), listOf( http.fetched[0] ) + http.fetched.drop( 1 ).sorted() )
		for( path in wanted ) assertEquals( files[path], File( src, path ).readText() )
		assertFalse( File( src, "README.md" ).exists() )
	}

	@Test fun neededPathMissingFromListingFails() {
		val message = failure {
			download( FakeHttp(), need = { listOf( "Translation/Renewal/data/gone.txt" ) } ).download( tmp.newFolder() )
		}
		assertTrue( message, message.contains( "Translation/Renewal/data/gone.txt" ) )
	}

	@Test fun missingCommitSaysUpdateApp() {
		val http = object : English.Http {
			override fun get( url: String ) = English.Response( 404, ByteArray( 0 ) )
		}
		val message = failure { download( http ).download( tmp.newFolder() ) }
		assertTrue( message, message.contains( "update the app", ignoreCase = true ) )
	}

	@Test fun truncatedListingFails() {
		val message = failure { download( FakeHttp( treeJson( truncated = true ) ) ).download( tmp.newFolder() ) }
		assertTrue( message, message.contains( "truncated" ) )
	}

	@Test fun resumesVerifiedFiles() {
		val src = tmp.newFolder()
		val done = "Translation/Renewal/data/msgstringtable.txt"
		File( src, done ).apply { parentFile!!.mkdirs(); writeText( files.getValue( done ) ) }
		val http = FakeHttp()
		download( http ).download( src )
		assertFalse( http.fetched.contains( English.rawUrl( done ) ) )
	}

	@Test fun refetchesCorruptFiles() {
		val src = tmp.newFolder()
		val bad = "Translation/Renewal/data/msgstringtable.txt"
		File( src, bad ).apply { parentFile!!.mkdirs(); writeText( "Do you agr" ) }
		val http = FakeHttp()
		download( http ).download( src )
		assertTrue( http.fetched.contains( English.rawUrl( bad ) ) )
		assertEquals( files[bad], File( src, bad ).readText() )
	}

	@Test fun partialFileNeverCountsAsDone() {
		val src = tmp.newFolder()
		val path = "Translation/Renewal/data/msgstringtable.txt"
		// a download stopped after writing every byte but before its rename
		File( src, "$path.part" ).apply { parentFile!!.mkdirs(); writeText( files.getValue( path ) ) }
		val http = FakeHttp()
		download( http ).download( src )
		assertTrue( http.fetched.contains( English.rawUrl( path ) ) )
		assertTrue( File( src, path ).exists() )
		assertFalse( File( src, "$path.part" ).exists() )
	}

	@Test fun retriesThenFailsNamingFile() {
		val broken = "Translation/Renewal/data/book/prontera bible01.txt"
		val http = object : FakeHttp() {
			override fun body( path: String ): ByteArray =
				if( path == broken ) "truncat".toByteArray() else super.body( path )
		}
		val src = tmp.newFolder()
		val message = failure { download( http ).download( src ) }
		assertTrue( message, message.contains( broken ) )
		assertEquals( listOf( 1000L, 2000L, 4000L ), sleeps )
		assertEquals( 4, http.fetched.count { it == English.rawUrl( broken ) } )
		assertFalse( File( src, broken ).exists() )
		// the good files stay for the next try
		assertTrue( File( src, "Translation/Renewal/data/msgstringtable.txt" ).exists() )
	}

	@Test fun recoversFromAnHttpErrorAndANetworkError() {
		val flaky = "Translation/Renewal/data/msgstringtable.txt"
		val calls = AtomicInteger()
		val http = object : FakeHttp() {
			override fun get( url: String ): English.Response {
				if( url == English.rawUrl( flaky ) )
					when( calls.incrementAndGet() ) {
						1 -> return English.Response( 503, ByteArray( 0 ) )
						2 -> throw IOException( "connection reset" )
					}
				return super.get( url )
			}
		}
		val src = tmp.newFolder()
		download( http ).download( src )
		assertEquals( listOf( 1000L, 2000L ), sleeps )
		assertEquals( files[flaky], File( src, flaky ).readText() )
	}

	@Test fun reportsProgressToTotals() {
		val reports = mutableListOf<EnglishDownload.Progress>()
		download( FakeHttp() ).download( tmp.newFolder() ) { synchronized( reports ) { reports += it } }
		val wanted = files.filterKeys { it.startsWith( "Translation/" ) }
		val total = wanted.values.sumOf { it.toByteArray().size.toLong() }
		assertEquals( EnglishDownload.Progress( wanted.size, wanted.size, total, total ), reports.last() )
		assertTrue( reports.zipWithNext().all { ( a, b ) -> b.files >= a.files && b.bytes >= a.bytes } )
	}

	@Test fun refusesWhenLowOnSpace() {
		val total = files.filterKeys { it.startsWith( "Translation/" ) }.values.sumOf { it.toByteArray().size.toLong() }
		val http = FakeHttp()
		val message = failure { download( http, free = 2 * total + 50L * 1024 * 1024 - 1 ).download( tmp.newFolder() ) }
		assertTrue( message, message.contains( "51 MB" ) )
		assertEquals( listOf( English.treeUrl() ), http.fetched )
		// exactly enough is fine
		download( FakeHttp(), free = 2 * total + 50L * 1024 * 1024 ).download( tmp.newFolder() )
	}

	@Test fun refusesEscapingPaths() {
		for( path in listOf( "Translation/../../evil.txt", "/etc/evil.txt", "Translation\\evil.txt" ) ) {
			files[path] = "evil"
			val root = tmp.newFolder()
			val src = File( root, "src" ).apply { mkdirs() }
			val message = failure { download( FakeHttp(), need = { listOf( path ) } ).download( src ) }
			assertTrue( message, message.contains( path ) )
			assertEquals( listOf( "src" ), root.list()!!.toList() )
			assertEquals( 0, src.walk().count { it.isFile && !it.name.endsWith( ".json" ) } )
			files.remove( path )
		}
	}

	@Test fun downloadsInParallel() {
		repeat( 20 ) { files["Translation/Renewal/data/book/$it.txt"] = "page $it" }
		val active = AtomicInteger()
		val most = AtomicInteger()
		val http = object : FakeHttp() {
			override fun body( path: String ): ByteArray {
				most.accumulateAndGet( active.incrementAndGet(), ::maxOf )
				Thread.sleep( 50 )
				active.decrementAndGet()
				return super.body( path )
			}
		}
		download( http ).download( tmp.newFolder() )
		assertEquals( 8, most.get() )
	}
}
