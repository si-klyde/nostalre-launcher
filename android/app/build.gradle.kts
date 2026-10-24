plugins {
	id( "com.android.application" )
}

// Filled by scripts/build-apk.sh: jniLibs/arm64-v8a (servers, bridge, nostalRE), assets (server data, database, licenses)
val stage = rootProject.file( "../out/build/apk" )

android {
	namespace = "io.github.siklyde.nostalre"
	compileSdk = 34

	defaultConfig {
		applicationId = "io.github.siklyde.nostalre"
		// MANAGE_EXTERNAL_STORAGE: the game files are read in place from shared storage
		minSdk = 30
		targetSdk = 34
		versionCode = 2
		versionName = "0.1.1"
		ndk { abiFilters += "arm64-v8a" }
	}

	sourceSets {
		getByName( "main" ) {
			jniLibs.directories.add( stage.resolve( "jniLibs" ).path )
			assets.directories.add( stage.resolve( "assets" ).path )
		}
	}

	// the "libraries" are executables: they must exist as real files in the native library dir to be run
	packaging { jniLibs { useLegacyPackaging = true } }

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}
