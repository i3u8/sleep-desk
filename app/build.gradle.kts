plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.i3u8.sleepdesk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.i3u8.sleepdesk"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.3.2"
    }

    // CI / local release signing via env (never commit the keystore).
    // Secrets: ANDROID_KEYSTORE_BASE64 → file path in ANDROID_KEYSTORE_PATH,
    // ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val keyAliasEnv = System.getenv("ANDROID_KEY_ALIAS")
    val keyPasswordEnv = System.getenv("ANDROID_KEY_PASSWORD")
    val releaseKeystore = keystorePath?.takeIf { it.isNotBlank() }?.let { path ->
        java.io.File(path).takeIf { it.isFile }
    }
    val canSignRelease = releaseKeystore != null &&
        !keystorePassword.isNullOrBlank() &&
        !keyAliasEnv.isNullOrBlank() &&
        !keyPasswordEnv.isNullOrBlank()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Same upload key when secrets present so CI installables stay upgradeable.
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
