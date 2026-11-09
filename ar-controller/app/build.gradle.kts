plugins {
    id("com.android.application")
}

android {
    namespace = "com.arcontroller"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arcontroller"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "2.0"
    }

    signingConfigs {
        // Same public debug key as earlier releases, so new versions install over old ones.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Lets FloatingAppLauncher call the hidden ActivityOptions.setLaunchWindowingMode(FREEFORM) on Android 9+.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
