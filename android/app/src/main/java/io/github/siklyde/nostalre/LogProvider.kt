package io.github.siklyde.nostalre

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the exported log files ([LogExport]) to the app the player shares them with, read only. Only files directly
 * in [dir]; the receiving app gets access through the share intent's grant, nothing else can open them.
 */
class LogProvider : ContentProvider() {
	companion object {
		fun dir( context: Context ) = File( context.cacheDir, "logs" )

		fun uri( context: Context, file: File ): Uri =
			Uri.Builder().scheme( "content" ).authority( "${context.packageName}.logs" ).appendPath( file.name ).build()
	}

	override fun onCreate() = true

	private fun file( uri: Uri ): File {
		val dir = dir( context!! )
		val name = uri.pathSegments.singleOrNull()?.takeIf { '/' !in it && it != ".." && it != "." }
		val file = name?.let { File( dir, it ) }
		if( file == null || !file.isFile || file.canonicalFile.parentFile != dir.canonicalFile )
			throw FileNotFoundException( uri.toString() )
		return file
	}

	override fun openFile( uri: Uri, mode: String ): ParcelFileDescriptor {
		if( mode != "r" )
			throw SecurityException( "read only" )
		return ParcelFileDescriptor.open( file( uri ), ParcelFileDescriptor.MODE_READ_ONLY )
	}

	// the name and size, which chat and mail apps show for the attachment
	override fun query( uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String? ): Cursor {
		val file = file( uri )
		val columns = projection ?: arrayOf( OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE )
		return MatrixCursor( columns, 1 ).apply {
			addRow( columns.map {
				when( it ) {
					OpenableColumns.DISPLAY_NAME -> file.name
					OpenableColumns.SIZE -> file.length()
					else -> null
				}
			} )
		}
	}

	override fun getType( uri: Uri ) = "text/plain"

	override fun insert( uri: Uri, values: ContentValues? ): Uri = throw UnsupportedOperationException()

	override fun delete( uri: Uri, selection: String?, selectionArgs: Array<out String>? ): Int = throw UnsupportedOperationException()

	override fun update( uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>? ): Int =
		throw UnsupportedOperationException()
}
