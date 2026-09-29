plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.safenavi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.safenavi.app"
        minSdk = 26
        targetSdk = 35
        manifestPlaceholders["NAVER_MAP_CLIENT_ID"] = System.getenv("NAVER_MAP_CLIENT_ID") ?: ""
        versionCode = 32
        versionName = "32"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("com.naver.maps:map-sdk:3.24.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    kapt("androidx.room:room-compiler:2.7.2")
}
