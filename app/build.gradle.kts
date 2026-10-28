import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jakliuyuy.batterypower"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jakliuyuy.batterypower"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "3.3.0"
    }

    // 签名策略：有 KEYSTORE_BASE64 环境变量（GitHub Actions secrets）时用正式签名，
    // 否则 fallback 到 debug 签名，保证任何环境都能出可安装 APK
    signingConfigs {
        create("release") {
            val b64 = System.getenv("KEYSTORE_BASE64")
            if (!b64.isNullOrEmpty()) {
                val f = file("${rootDir}/release.jks")
                if (!f.exists()) {
                    f.writeBytes(Base64.getDecoder().decode(b64))
                }
                storeFile = f
                storePassword = System.getenv("KEYSTORE_PASS") ?: "changeit"
                keyAlias = System.getenv("KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("KEY_PASS") ?: System.getenv("KEYSTORE_PASS") ?: "changeit"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 无 KEYSTORE_BASE64（本地构建）时 fallback debug 签名，保证任何环境可出可装 APK
            signingConfig = if (System.getenv("KEYSTORE_BASE64").isNullOrEmpty()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
