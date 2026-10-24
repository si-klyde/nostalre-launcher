plugins {
	id( "com.android.application" )
}

// Filled by scripts/build-apk.sh: jniLibs/arm64-v8a (servers, bridge, nostalRE), assets (server data, database, licenses)
val stage = rootProject.file( "../out/build/apk" )

// The release key, from the builder's own ~/.gradle/gradle.properties, never from this repository. Debug builds sign
// with it too, so a developer's build and a release can update each other on a device; without it they keep the
// Android debug key, and a release build is left unsigned (scripts/build-apk.sh --release refuses it)
fun key( name: String ) = providers.gradleProperty( name ).orNull
val releaseStore = key( "NOSTALRE_STORE_FILE" )

android {
	namespace = "io.github.siklyde.nostalre"
	compileSdk = 34

	defaultConfig {
		applicationId = "io.github.siklyde.nostalre"
		// MANAGE_EXTERNAL_STORAGE: the game files are read in place from shared storage
		minSdk = 30
		targetSdk = 34
		versionCode = 4
		versionName = "0.1.3"
		ndk { abiFilters += "arm64-v8a" }
	}

	signingConfigs {
		if( releaseStore != null )
			create( "nostalre" ) {
				storeFile = file( releaseStore )
				storePassword = key( "NOSTALRE_STORE_PASSWORD" )
				keyAlias = key( "NOSTALRE_KEY_ALIAS" )
				keyPassword = key( "NOSTALRE_KEY_PASSWORD" )
			}
	}

	buildTypes {
		getByName( "release" ) {
			isMinifyEnabled = false
			signingConfig = signingConfigs.findByName( "nostalre" )
		}
		getByName( "debug" ) {
			signingConfigs.findByName( "nostalre" )?.let { signingConfig = it }
		}
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
