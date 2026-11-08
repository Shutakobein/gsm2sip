plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.callagent.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.callagent.gateway"
        minSdk = 26
        targetSdk = 34
        // 472 / 1.4.33: the gateway now applies its own uplink route knob —
        // `callagent_uplink_via=0` is written through the existing root shell at
        // bring-up and at boot (`UplinkRouteSetting`), instead of being typed in by
        // hand from ADB.  Audio behaviour is unchanged: 0 is what the code already
        // used when the key was absent, and the staged playback writer
        // (`PlaybackWriter`, `callagent_play_stage`) and the `stage=`/`wr:`/`played=`
        // counters on the RTP stats line are byte for byte what 471 ran.
        //
        // The number moves because a field run has to be able to say which build a
        // stats line came from — `posture.ps1` prints this pair — and because the
        // bring-up lines named `Uplink route:` only exist from here on.  It is not a
        // behaviour claim.
        versionCode = 472
        versionName = "1.4.33"
    }

    // A release build is signed with the same debug key the debug build uses.
    // That is deliberate: it keeps the signature identical, so a release APK can
    // replace a debug one inside the Magisk module without PackageManager
    // rejecting it for a signature mismatch.  The point of building release here
    // is not secrecy, it is `debuggable=false` — ART compiles a debuggable app in
    // a deoptimizable mode with much weaker inlining, which costs real CPU in the
    // per-frame audio loops.
    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // The unit tests under app/src/test run on a plain JVM, where android.util.Log
    // is a stub whose methods throw "not mocked" unless the harness makes them
    // return defaults instead.  Code under test logs — that is not a reason to
    // put a phone in the loop for a parser test.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}
