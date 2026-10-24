package io.github.siklyde.nostalre

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Talks to the login and char servers like the game client does, for what the hub changes through them: the servers
 * check it and clean up after it (a deleted character's items, skills, pets... in many tables).
 */
object CharServer {
	private const val LOGIN_PORT = 6900
	private const val CHAR_PORT = 6121
	private const val TIMEOUT_MS = 10_000

	// fixed size packets the char server may send before what is waited for (the rest carry their length)
	private val FIXED = mapOf( 0x09a0 to 6, 0x08b9 to 12, 0x0828 to 14, 0x082a to 10, 0x006c to 3, 0x0081 to 3, 0x006d to 157, 0x0b6f to 177, 0x006e to 3 )

	class Error( message: String ) : IOException( message )

	/** Deletes a character right away (char_del_delay is 0, the account has no birthdate to confirm). */
	fun delete( account: String, charId: Int ) {
		session( account ) { char ->
			char.send( 0x0827, 6 ) { putInt( charId ) }
			val reserved = char.receive( 0x0828 )
			// 1: queued, 0: already queued
			when( val result = reserved.getInt( 6 ) ) {
				0, 1 -> {}
				4 -> throw Error( "Leave the guild first." )
				5 -> throw Error( "Leave the party first." )
				else -> throw Error( "The server refused to delete this character (code $result)." )
			}
			char.send( 0x0829, 12 ) { putInt( charId ) } // empty birthdate
			val result = char.receive( 0x082a ).getInt( 6 )
			if( result != 1 )
				throw Error( "The server refused to delete this character (code $result)." )
		}
	}

	/** Makes a character in [slot] ([sex]: 0 female, 1 male; [job]: 0 a Novice, 4218 a Doram Summoner); its char_id. */
	fun create( account: String, name: String, slot: Int, hair: Int, hairColor: Int, sex: Int, job: Int ): Int {
		var charId = 0
		session( account ) { char ->
			char.send( 0x0a39, 36 ) {
				put( fixed( name, 24 ) )
				put( slot.toByte() )
				putShort( hairColor.toShort() )
				putShort( hair.toShort() )
				putInt( job )
				put( sex.toByte() )
			}
			// the new character: 0x0b6f from packet version 20211103 (Renewal; the native client's 20250716), else 0x006d
			val reply = char.receive( 0x006d, 0x0b6f, 0x006e )
			if( reply.getShort( 0 ).toInt() == 0x006e )
				throw Error( when( reply.get( 2 ).toInt() and 0xff ) {
					0x00 -> "That name is already taken."
					0x03 -> "There is no free character slot."
					else -> "That name can't be used. Try letters and digits only."
				} )
			charId = reply.getInt( 2 ) // the new character's info starts with its char_id
		}
		return charId
	}

	/** Logs in, enters the char server and runs [block] on it. */
	private fun session( account: String, block: ( Connection ) -> Unit ) {
		val login = Connection( LOGIN_PORT ).use { login ->
			login.send( 0x0064, 55 ) {
				putInt( 0 )
				put( fixed( account, 24 ) )
				put( fixed( Account.PASSWORD, 24 ) )
				put( 0 )
			}
			val packet = login.receive( 0x0ac4 )
			// login_id1, AID, login_id2 after type and length; sex after last_ip and the 26 byte date
			LoginTicket( packet.getInt( 4 ), packet.getInt( 8 ), packet.getInt( 12 ), packet.get( 46 ) )
		}
		Connection( CHAR_PORT ).use { char ->
			char.send( 0x0065, 17 ) {
				putInt( login.aid )
				putInt( login.id1 )
				putInt( login.id2 )
				putShort( 0 )
				put( login.sex )
			}
			char.raw( 4 ) // the account id, unframed
			// the character list: the server only acts on characters it listed to this session
			char.receive( 0x006b )
			block( char )
		}
	}

	private class LoginTicket( val id1: Int, val aid: Int, val id2: Int, val sex: Byte )

	/** [text] in the game's encoding, zero padded to [size] (and cut to leave the terminator). */
	private fun fixed( text: String, size: Int ) = ByteArray( size ).also { bytes ->
		val encoded = text.toByteArray( Charset.forName( "EUC-KR" ) )
		encoded.copyInto( bytes, endIndex = minOf( encoded.size, size - 1 ) )
	}

	private class Connection( port: Int ) : AutoCloseable {
		private val socket = Socket().apply {
			try {
				connect( InetSocketAddress( "127.0.0.1", port ), TIMEOUT_MS )
				soTimeout = TIMEOUT_MS
			} catch( e: IOException ) {
				close()
				throw e
			}
		}
		private val input = DataInputStream( socket.getInputStream().buffered() )

		fun send( type: Int, size: Int, body: ByteBuffer.() -> Unit ) {
			val buffer = ByteBuffer.allocate( size ).order( ByteOrder.LITTLE_ENDIAN )
			buffer.putShort( type.toShort() )
			buffer.body()
			socket.getOutputStream().write( buffer.array() )
		}

		fun raw( size: Int ): ByteArray = ByteArray( size ).also { input.readFully( it ) }

		/** Skips other packets until one of [types] arrives; whole packet, little endian. */
		fun receive( vararg types: Int ): ByteBuffer {
			while( true ) {
				val head = raw( 2 )
				val header = u16( head )
				when( header ) {
					0x006a, 0x083e -> throw Error( "The login server refused the account." )
					0x006c -> throw Error( "The char server refused the account." )
					0x0081 -> throw Error( "Disconnected by the server." )
				}
				val packet = FIXED[header]?.let { head + raw( it - 2 ) } ?: run {
					val length = raw( 2 )
					head + length + raw( u16( length ) - 4 )
				}
				if( header in types )
					return ByteBuffer.wrap( packet ).order( ByteOrder.LITTLE_ENDIAN )
			}
		}

		private fun u16( bytes: ByteArray ) = ( bytes[0].toInt() and 0xff ) or ( ( bytes[1].toInt() and 0xff ) shl 8 )

		override fun close() = socket.close()
	}
}
