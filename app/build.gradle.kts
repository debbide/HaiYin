plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: create keystore.properties at the project root (gitignored):
//   storeFile=E:/keystore/haiyin-release.keystore
//   storePassword=xxxx
//   keyAlias=haiyin
//   keyPassword=xxxx
// Generate the keystore once with:
//   keytool -genkeypair -v -keystore E:/keystore/haiyin-release.keystore \
//     -alias haiyin -keyalg RSA -keysize 2048 -validity 9125
// Back it up somewhere safe: losing it means the app can never be updated.
val keystoreProps = java.util.Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) keystoreProps.load(keystorePropsFile.inputStream())

android {
    namespace = "com.haiyin.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.haiyin.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "0.9.1"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Formal release signing comes from keystore.properties at the project
            // root (gitignored, never commit it). Without it, release falls back
            // to the debug key for local testing only.
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-ripple")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QuickJS engine (harlonwang wrapper: 16KB page-aligned native lib, working promises)
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
