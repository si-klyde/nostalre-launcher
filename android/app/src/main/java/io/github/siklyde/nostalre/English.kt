package io.github.siklyde.nostalre

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** ROenglishRE, the English fan translation of kRO: fetched by the player's app at one pinned commit, never shipped */
object English {
	const val REPO = "llchrisll/ROenglishRE"
	const val SHA = "4a4abafd3fc6e360b57b41c297e9f9340fc66a26"

	class Response( val code: Int, val body: ByteArray )

	fun interface Http {
		/** Throws [IOException] when the request does not complete */
		fun get( url: String ): Response
	}

	/** Plain HTTPS GET */
	val web = Http { url ->
		val connection = URL( url ).openConnection() as HttpURLConnection
		connection.connectTimeout = 15_000
		connection.readTimeout = 30_000
		try {
			val code = connection.responseCode
			val body = if( code == 200 ) connection.inputStream.use { it.readBytes() } else ByteArray( 0 )
			Response( code, body )
		} finally {
			connection.disconnect()
		}
	}

	fun treeUrl( sha: String = SHA ) = "https://api.github.com/repos/$REPO/git/trees/$sha?recursive=1"

	fun rawUrl( path: String, sha: String = SHA ) = "https://raw.githubusercontent.com/$REPO/$sha/" + quote( path )

	/** Python's `urllib.parse.quote(path)`: UTF-8, letters, digits, `_.-~` and `/` kept */
	private fun quote( path: String ) = buildString {
		for( byte in path.toByteArray( Charsets.UTF_8 ) ) {
			val c = byte.toInt() and 0xff
			if( c < 0x80 && ( c.toChar().isLetterOrDigit() || c.toChar() in "_.-~/" ) ) append( c.toChar() )
			else append( "%%%02X".format( c ) )
		}
	}

	/** The id git gives a file's contents: SHA-1 of `blob <length>\0` and the bytes */
	fun blobSha( bytes: ByteArray ): String {
		val digest = MessageDigest.getInstance( "SHA-1" )
		digest.update( "blob ${bytes.size}\u0000".toByteArray() )
		return digest.digest( bytes ).joinToString( "" ) { "%02x".format( it ) }
	}
}

/**
 * Downloads the files the import reads (`nostalre-english needed`, run by [needed] on the saved tree listing) from the
 * pinned commit into a source folder, each checked against its git blob id. Files already there and correct are kept,
 * so a stopped download resumes.
 */
class EnglishDownload(
	private val http: English.Http,
	private val needed: ( File ) -> List<String>,
	private val sleep: ( Long ) -> Unit = Thread::sleep,
	private val freeBytes: ( File ) -> Long = { it.usableSpace },
	private val parallel: Int = 8,
) {
	data class Progress( val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long )

	class Failure( message: String ) : Exception( message )

	private class Blob( val sha: String, val size: Long )

	/** Fills [src] with every needed file; throws [Failure] with a message for the player */
	fun download( src: File, progress: ( Progress ) -> Unit = {} ) {
		val listing = fetch( English.treeUrl(), "the file list" ) { code ->
			if( code == 404 ) "ROenglishRE no longer has the translation this app was built for: update the app"
			else null
		}
		val blobs = try {
			blobs( JSONObject( String( listing ) ) )
		} catch( e: org.json.JSONException ) {
			throw Failure( "GitHub's file list could not be read: ${e.message}" )
		}

		src.mkdirs()
		val listingFile = File( src, ".tree.json" )
		listingFile.writeBytes( listing )
		val wanted = needed( listingFile ).map { path ->
			if( !safe( path ) ) throw Failure( "refusing a path outside the download: $path" )
			path to ( blobs[path] ?: throw Failure( "$path is not in ROenglishRE's file list" ) )
		}

		val totalBytes = wanted.sumOf { it.second.size }
		val reserve = 2 * totalBytes + 50L * MB
		if( freeBytes( src ) < reserve )
			throw Failure( "Not enough free space: English text needs ${( reserve + MB - 1 ) / MB} MB free" )

		var files = 0
		var bytes = 0L
		val done = { size: Long ->
			synchronized( this ) {
				files++
				bytes += size
				progress( Progress( files, wanted.size, bytes, totalBytes ) )
			}
		}
		val pool = Executors.newFixedThreadPool( parallel )
		try {
			val jobs = wanted.map { ( path, blob ) ->
				pool.submit {
					val file = File( src, path )
					if( !( file.isFile && English.blobSha( file.readBytes() ) == blob.sha ) ) get( path, blob, file )
					done( blob.size )
				}
			}
			for( job in jobs ) {
				try {
					job.get()
				} catch( e: ExecutionException ) {
					throw e.cause ?: e
				}
			}
		} finally {
			pool.shutdownNow()
		}
	}

	/** Path -> blob of every file in a GitHub tree listing */
	private fun blobs( tree: JSONObject ): Map<String, Blob> {
		if( tree.optBoolean( "truncated" ) ) throw Failure( "GitHub's file list came back truncated" )
		val blobs = HashMap<String, Blob>()
		val entries = tree.getJSONArray( "tree" )
		for( i in 0 until entries.length() ) {
			val entry = entries.getJSONObject( i )
			if( entry.getString( "type" ) == "blob" )
				blobs[entry.getString( "path" )] = Blob( entry.getString( "sha" ), entry.getLong( "size" ) )
		}
		return blobs
	}

	/** One file, retried after 1, 2 and 4 seconds; written as `.part` and renamed once its contents check out */
	private fun get( path: String, blob: Blob, file: File ) {
		var wait = 1000L
		while( true ) {
			val problem = try {
				val response = http.get( English.rawUrl( path ) )
				when {
					response.code != 200 -> "HTTP ${response.code}"
					English.blobSha( response.body ) != blob.sha -> "contents do not match"
					else -> {
						file.parentFile!!.mkdirs()
						val part = File( file.path + ".part" )
						part.writeBytes( response.body )
						if( !part.renameTo( file ) ) throw IOException( "cannot rename $part" )
						return
					}
				}
			} catch( e: IOException ) {
				e.message ?: e.toString()
			}
			if( wait > 4000L ) throw Failure( "Download of $path failed: $problem" )
			sleep( wait )
			wait *= 2
		}
	}

	/** The listing, retried like a file; [refuse] turns an HTTP code into a final message */
	private fun fetch( url: String, what: String, refuse: ( Int ) -> String? ): ByteArray {
		var wait = 1000L
		while( true ) {
			val problem = try {
				val response = http.get( url )
				if( response.code == 200 ) return response.body
				refuse( response.code )?.let { throw Failure( it ) }
				"HTTP ${response.code}"
			} catch( e: IOException ) {
				e.message ?: e.toString()
			}
			if( wait > 4000L ) throw Failure( "Download of $what failed: $problem" )
			sleep( wait )
			wait *= 2
		}
	}

	private fun safe( path: String ) =
		path.isNotEmpty() && !path.startsWith( "/" ) && '\\' !in path &&
			path.split( '/' ).none { it.isEmpty() || it == "." || it == ".." }

	private companion object {
		const val MB = 1024L * 1024
	}
}
