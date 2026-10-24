cmake_minimum_required( VERSION 3.13 )
project( grf-server CXX )

set( CMAKE_CXX_STANDARD 17 )
set( CMAKE_CXX_STANDARD_REQUIRED ON )

find_package( Threads REQUIRED )
find_package( ZLIB REQUIRED )

add_executable( grf-server grf_server.cpp )
target_compile_options( grf-server PRIVATE -Wall -Wextra )
target_link_libraries( grf-server Threads::Threads ZLIB::ZLIB )
if( ANDROID )
	target_link_options( grf-server PRIVATE -static-libstdc++ )
endif()
