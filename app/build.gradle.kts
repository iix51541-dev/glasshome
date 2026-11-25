plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.clean.glasshome"
    compileSdk = 34
    defaultConfig { applicationId = "com.clean.glasshome"; minSdk = 24; targetSdk = 28; versionCode = 2; versionName = "2.0" }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
