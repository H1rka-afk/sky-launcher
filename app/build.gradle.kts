plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sky.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sky.launcher"
        minSdk = 26
        // 28 — чтобы работали переключатели Wi‑Fi и Bluetooth в Пункте управления
        targetSdk = 28
        versionCode = 2
        versionName = "2.0"
    }
    signingConfigs {
        create("skyos") {
            storeFile = file("skyos.jks")
            storePassword = "skyos123"
            keyAlias = "skyos"
            keyPassword = "skyos123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("skyos") }
        release { signingConfig = signingConfigs.getByName("skyos"); isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("androidx.test:core:1.6.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
}
