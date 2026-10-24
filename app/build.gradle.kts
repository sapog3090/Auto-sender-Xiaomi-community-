plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xmsender.blunlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmsender.blunlock"
        minSdk = 26
        targetSdk = 35
        // Номер сборки GitHub Actions — чтобы каждая новая сборка была «новее» предыдущей.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = ciBuild
        versionName = "1.1.$ciBuild"
    }

    signingConfigs {
        // Постоянный ключ в репозитории: обновления ставятся поверх без удаления.
        create("release") {
            storeFile = file("release.jks")
            storePassword = "android1"
            keyAlias = "app"
            keyPassword = "android1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
