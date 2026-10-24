// Serves the game files over HTTP to the Android hub (character sprites, map names), on roBrowser's "remote client"
// protocol:
//   GET  /<path>             file from the game data folder (BGM/*.mp3, translated data/*.txt, ...),
//                            else from the GRFs: loose files override GRF contents like the RO client
//   POST / filter=<regex>    names of the GRF files matching the regex, one per line
// GRFs are read in DATA.INI order, the first archive holding a file wins. Only plain zlib
// entries are supported; DES encrypted entries answer 404.
// Listens on 127.0.0.1 and only answers pages served from this device.
//
// Without a DATA.INI it only serves the folder's files.
//
// Usage: grf-server <game data dir> [port]   (default 8001)

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <regex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

constexpr size_t MAX_REQUEST = 1 << 20;
constexpr uint8_t GRF_FLAG_FILE = 0x01;
constexpr uint8_t GRF_FLAG_ENCRYPTED = 0x06;

struct GrfEntry {
	int fd;
	uint32_t compressed_len;
	uint32_t compressed_aligned;
	uint32_t size;
	uint8_t flags;
	uint64_t offset;
};

std::string data_dir;
std::unordered_map<std::string, GrfEntry> entries; // key: lower case path with '/' separators
std::vector<std::string> names;                    // original GRF names, for searches
std::unordered_map<std::string, std::string> loose; // same keys as entries -> file in the data folder

std::string normalize( std::string path ){
	for( char& c : path ){
		c = c == '\\' ? '/' : (char)tolower( (unsigned char)c );
	}
	return path;
}

uint32_t le32( const unsigned char* p ){
	return p[0] | ( p[1] << 8 ) | ( p[2] << 16 ) | ( (uint32_t)p[3] << 24 );
}

uint64_t le64( const unsigned char* p ){
	return le32( p ) | ( (uint64_t)le32( p + 4 ) << 32 );
}

bool read_at( int fd, void* buf, size_t len, off_t offset ){
	char* p = (char*)buf;

	while( len > 0 ){
		ssize_t n = pread( fd, p, len, offset );
		if( n <= 0 )
			return false;
		p += n;
		len -= (size_t)n;
		offset += n;
	}
	return true;
}

bool inflate_to( const std::string& in, std::string& out, size_t size ){
	uLongf len = (uLongf)size;

	out.resize( size );
	return uncompress( (Bytef*)out.data(), &len, (const Bytef*)in.data(), (uLong)in.size() ) == Z_OK && len == size;
}

/// Adds the entries of a 0x200 GRF, or a 0x300 one (GRF Editor's "Event Horizon", 64-bit offsets: kRO's 2025
/// data); files already known from an earlier GRF keep priority.
bool load_grf( const std::string& path ){
	int fd = open( path.c_str(), O_RDONLY );
	unsigned char header[46];

	bool read = fd >= 0 && read_at( fd, header, sizeof( header ), 0 );
	uint32_t version = read ? le32( header + 42 ) : 0;
	bool v3 = version == 0x300 && memcmp( header, "Event Horizon", 14 ) == 0;
	if( !read || ( !v3 && memcmp( header, "Master of Magic", 15 ) != 0 ) ){
		fprintf( stderr, "[grf-server] %s: not a GRF\n", path.c_str() );
		return false;
	}
	if( !v3 && version != 0x200 ){
		fprintf( stderr, "[grf-server] %s: unsupported version 0x%x\n", path.c_str(), version );
		return false;
	}

	// 0x300: the 64-bit table offset takes the reserved-files slot, and a zero word precedes the table's sizes
	off_t table_pos = 46 + ( v3 ? (off_t)le64( header + 30 ) + 4 : (off_t)le32( header + 30 ) );
	unsigned char sizes[8];
	if( !read_at( fd, sizes, sizeof( sizes ), table_pos ) )
		return false;

	std::string packed( le32( sizes ), '\0' ), table;
	if( !read_at( fd, packed.data(), packed.size(), table_pos + 8 ) || !inflate_to( packed, table, le32( sizes + 4 ) ) ){
		fprintf( stderr, "[grf-server] %s: broken file table\n", path.c_str() );
		return false;
	}

	size_t entry_len = v3 ? 21 : 17, added = 0, pos = 0;
	while( pos < table.size() ){
		size_t end = table.find( '\0', pos );
		if( end == std::string::npos || end + 1 + entry_len > table.size() )
			break;

		std::string name = table.substr( pos, end - pos );
		const unsigned char* p = (const unsigned char*)table.data() + end + 1;
		GrfEntry e{ fd, le32( p ), le32( p + 4 ), le32( p + 8 ), p[12], v3 ? le64( p + 13 ) : le32( p + 13 ) };
		pos = end + 1 + entry_len;

		if( ( e.flags & GRF_FLAG_FILE ) && entries.emplace( normalize( name ), e ).second ){
			names.push_back( name );
			++added;
		}
	}
	fprintf( stderr, "[grf-server] %s: %zu files\n", path.c_str(), added );
	return true;
}

/// GRF names listed in DATA.INI ("1=rdata.grf"), in order.
std::vector<std::string> data_ini( const std::string& dir ){
	std::ifstream ini( dir + "/DATA.INI" );
	std::vector<std::pair<int, std::string>> list;
	std::string line;

	while( std::getline( ini, line ) ){
		size_t eq = line.find( '=' );
		if( eq != std::string::npos && eq > 0 && isdigit( (unsigned char)line[0] ) ){
			std::string name = line.substr( eq + 1 );
			name.erase( name.find_last_not_of( " \t\r" ) + 1 );
			list.emplace_back( atoi( line.c_str() ), name );
		}
	}
	std::sort( list.begin(), list.end() );

	std::vector<std::string> out;
	for( auto& item : list )
		out.push_back( item.second );
	return out;
}

int hex( char c ){
	return isdigit( (unsigned char)c ) ? c - '0' : tolower( (unsigned char)c ) - 'a' + 10;
}

/// GRF names are CP949 bytes; spelled as Latin-1 characters in UTF-8 (roBrowser's URLs, loose file
/// names on Android's shared storage, which only takes UTF-8 names) they turn back into those bytes.
std::string latin1_bytes( const std::string& utf8 ){
	std::string bytes;
	for( size_t i = 0; i < utf8.size(); ++i ){
		unsigned char c = (unsigned char)utf8[i];
		if( ( c & 0xE0 ) == 0xC0 && i + 1 < utf8.size() && ( c & 0x1F ) <= 0x03 ){
			bytes += (char)( ( ( c & 0x1F ) << 6 ) | ( utf8[i + 1] & 0x3F ) ); // code point <= 0xFF
			++i;
		}else{
			bytes += (char)c;
		}
	}
	return bytes;
}

/// Undoes roBrowser's encoding: encodeURIComponent of the name held as Latin-1 characters.
std::string decode_path( const std::string& url ){
	std::string utf8;

	for( size_t i = 0; i < url.size(); ++i ){
		if( url[i] == '%' && i + 2 < url.size() && isxdigit( (unsigned char)url[i + 1] ) && isxdigit( (unsigned char)url[i + 2] ) ){
			utf8 += (char)( hex( url[i + 1] ) * 16 + hex( url[i + 2] ) );
			i += 2;
		}else{
			utf8 += url[i];
		}
	}
	return latin1_bytes( utf8 );
}

bool read_entry( const GrfEntry& e, std::string& out ){
	if( e.flags & GRF_FLAG_ENCRYPTED )
		return false;

	std::string packed( e.compressed_aligned, '\0' );
	if( !read_at( e.fd, packed.data(), packed.size(), 46 + (off_t)e.offset ) )
		return false;
	packed.resize( e.compressed_len );
	return inflate_to( packed, out, e.size );
}

/// Indexes every file of the data folder by its lower case relative path, like the GRF entries.
void index_loose(){
	std::error_code ec;

	for( auto it = std::filesystem::recursive_directory_iterator( data_dir, ec ); !ec && it != std::filesystem::recursive_directory_iterator(); it.increment( ec ) ){
		if( it->is_regular_file( ec ) && it->path().extension() != ".grf" )
			loose[normalize( latin1_bytes( std::filesystem::relative( it->path(), data_dir, ec ).string() ) )] = it->path().string();
	}
	fprintf( stderr, "[grf-server] %s: %zu loose files\n", data_dir.c_str(), loose.size() );
}

/// Files of the data folder (BGM, translations, ...); only indexed files can be read.
bool read_loose( const std::string& path, std::string& out ){
	auto it = loose.find( normalize( path ) );
	if( it == loose.end() )
		return false;

	std::ifstream file( it->second, std::ios::binary );
	if( !file )
		return false;
	std::ostringstream buf;
	buf << file.rdbuf();
	out = buf.str();
	return true;
}

std::string header_value( const std::string& request, const char* name ){
	std::string lower = request, key = std::string( "\r\n" ) + name + ":";

	for( char& c : lower )
		c = (char)tolower( (unsigned char)c );
	for( char& c : key )
		c = (char)tolower( (unsigned char)c );

	size_t pos = lower.find( key );
	if( pos == std::string::npos )
		return "";
	pos += key.size();
	std::string value = request.substr( pos, request.find( "\r\n", pos ) - pos );
	value.erase( 0, value.find_first_not_of( " \t" ) );
	value.erase( value.find_last_not_of( " \t" ) + 1 );
	return value;
}

/// Only pages served from this device may read the files.
bool origin_allowed( const std::string& origin ){
	if( origin.empty() )
		return true;

	size_t host = origin.find( "://" );
	if( host == std::string::npos )
		return false;
	host += 3;
	size_t end = origin.find_first_of( ":/", host );
	std::string name = origin.substr( host, end == std::string::npos ? std::string::npos : end - host );
	return name == "localhost" || name == "127.0.0.1";
}

bool send_all( int fd, const std::string& data ){
	size_t sent = 0;

	while( sent < data.size() ){
		ssize_t n = send( fd, data.data() + sent, data.size() - sent, MSG_NOSIGNAL );
		if( n <= 0 )
			return false;
		sent += (size_t)n;
	}
	return true;
}

bool respond( int fd, int status, const std::string& origin, const std::string& type, const std::string& body ){
	const char* reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Bad Request";
	std::string head = "HTTP/1.1 " + std::to_string( status ) + " " + reason + "\r\n"
		"Content-Type: " + type + "\r\n"
		"Content-Length: " + std::to_string( body.size() ) + "\r\n";

	if( !origin.empty() )
		head += "Access-Control-Allow-Origin: " + origin + "\r\nVary: Origin\r\n";
	return send_all( fd, head + "\r\n" + body );
}

std::string content_type( const std::string& path ){
	static const std::pair<const char*, const char*> types[] = {
		{ ".mp3", "audio/mpeg" }, { ".wav", "audio/wav" },
		// the client page: browsers only run scripts served with a script type
		{ ".html", "text/html; charset=utf-8" }, { ".js", "text/javascript; charset=utf-8" },
		{ ".mjs", "text/javascript; charset=utf-8" }, { ".css", "text/css" }, { ".json", "application/json" },
		{ ".webmanifest", "application/manifest+json" }, { ".wasm", "application/wasm" },
		{ ".png", "image/png" }, { ".jpg", "image/jpeg" }, { ".gif", "image/gif" }, { ".svg", "image/svg+xml" },
	};
	size_t dot = path.rfind( '.' );
	std::string ext = dot == std::string::npos ? "" : path.substr( dot );

	for( auto& type : types ){
		if( strcasecmp( ext.c_str(), type.first ) == 0 )
			return type.second;
	}
	return "application/octet-stream";
}

std::string search( const std::string& body ){
	std::string pattern = decode_path( body.substr( body.find( '=' ) + 1 ) );
	for( char& c : pattern )
		c = c == '+' ? ' ' : c;

	std::string out;
	try{
		std::regex re( pattern, std::regex::icase );
		for( const std::string& name : names ){
			std::string path = name;
			for( char& c : path )
				c = c == '\\' ? '/' : c;
			if( std::regex_search( path, re ) )
				out += ( out.empty() ? "" : "\n" ) + path;
		}
	}catch( const std::regex_error& ){
	}
	return out;
}

/// One request per iteration; keeps the connection open (HTTP/1.1 keep-alive).
void session( int fd ){
	std::string buf;
	char chunk[65536];

	for( ;; ){
		size_t head_end;
		while( ( head_end = buf.find( "\r\n\r\n" ) ) == std::string::npos ){
			ssize_t n = recv( fd, chunk, sizeof( chunk ), 0 );
			if( n <= 0 || buf.size() > MAX_REQUEST ){
				close( fd );
				return;
			}
			buf.append( chunk, (size_t)n );
		}

		std::string request = buf.substr( 0, head_end + 2 );
		size_t body_len = (size_t)atol( header_value( request, "Content-Length" ).c_str() );
		while( buf.size() < head_end + 4 + body_len ){
			ssize_t n = recv( fd, chunk, sizeof( chunk ), 0 );
			if( n <= 0 || buf.size() > MAX_REQUEST ){
				close( fd );
				return;
			}
			buf.append( chunk, (size_t)n );
		}
		std::string body = buf.substr( head_end + 4, body_len );
		buf.erase( 0, head_end + 4 + body_len );

		std::string method = request.substr( 0, request.find( ' ' ) );
		size_t path_start = request.find( ' ' ) + 1;
		std::string target = request.substr( path_start, request.find( ' ', path_start ) - path_start );
		target = target.substr( 0, target.find( '?' ) );
		std::string origin = header_value( request, "Origin" );

		bool ok;
		if( !origin_allowed( origin ) ){
			ok = respond( fd, 403, "", "text/plain", "" );
		}else if( method == "OPTIONS" ){
			ok = respond( fd, 200, origin, "text/plain", "" );
		}else if( method == "POST" && target == "/" ){
			ok = respond( fd, 200, origin, "text/plain; charset=ISO-8859-1", search( body ) );
		}else if( method == "GET" && target.size() >= 1 ){
			std::string path = target == "/" ? "index.html" : decode_path( target.substr( 1 ) ), data;
			auto it = entries.find( normalize( path ) );
			bool found = read_loose( path, data ) || ( it != entries.end() && read_entry( it->second, data ) );

			if( !found && it != entries.end() && ( it->second.flags & GRF_FLAG_ENCRYPTED ) )
				fprintf( stderr, "[grf-server] encrypted entry not supported: %s\n", target.c_str() );
			ok = found ? respond( fd, 200, origin, content_type( path ), data ) : respond( fd, 404, origin, "text/plain", "" );
		}else{
			ok = respond( fd, 400, origin, "text/plain", "" );
		}

		if( !ok || strcasecmp( header_value( request, "Connection" ).c_str(), "close" ) == 0 )
			break;
	}
	close( fd );
}

}

int main( int argc, char** argv ){
	if( argc < 2 ){
		fprintf( stderr, "usage: grf-server <game data dir> [port]\n" );
		return EXIT_FAILURE;
	}
	data_dir = argv[1];
	int port = argc > 2 ? atoi( argv[2] ) : 8001;

	std::vector<std::string> grfs = data_ini( data_dir );
	if( grfs.empty() )
		fprintf( stderr, "[grf-server] no DATA.INI in %s, serving its files only\n", data_dir.c_str() );
	for( const std::string& grf : grfs )
		load_grf( data_dir + "/" + grf );
	index_loose();

	int server = socket( AF_INET, SOCK_STREAM, 0 );
	int one = 1;
	sockaddr_in addr = {};

	signal( SIGPIPE, SIG_IGN );
	setsockopt( server, SOL_SOCKET, SO_REUSEADDR, &one, sizeof( one ) );
	addr.sin_family = AF_INET;
	addr.sin_port = htons( (uint16_t)port );
	addr.sin_addr.s_addr = htonl( INADDR_LOOPBACK );
	if( bind( server, (sockaddr*)&addr, sizeof( addr ) ) != 0 || listen( server, 64 ) != 0 ){
		perror( "[grf-server] bind" );
		return EXIT_FAILURE;
	}
	fprintf( stderr, "[grf-server] %zu files, listening on 127.0.0.1:%d\n", entries.size(), port );

	for( ;; ){
		int client = accept( server, nullptr, nullptr );
		if( client < 0 )
			continue;
		setsockopt( client, IPPROTO_TCP, TCP_NODELAY, &one, sizeof( one ) );
		std::thread( session, client ).detach();
	}
}
