plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.clean.glasshome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clean.glasshome"
        minSdk = 24
        targetSdk = 28
        versionCode = 7
        versionName = "5.2"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "key"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName(if (file("debug.keystore").exists()) "fixed" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
