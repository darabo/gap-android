plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gapmesh.droid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.gapmesh.droid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 8
        versionName = "1.23"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Product flavors: "full" includes Tor, ML Kit, geohash; "light" strips them for minimal APK
    flavorDimensions += "variant"
    productFlavors {
        create("full") {
            dimension = "variant"
            buildConfigField("boolean", "HAS_TOR", "true")
            buildConfigField("boolean", "HAS_GEOHASH", "true")
        }
        create("light") {
            dimension = "variant"
            applicationIdSuffix = ".light"
            versionNameSuffix = "-light"
            buildConfigField("boolean", "HAS_TOR", "false")
            buildConfigField("boolean", "HAS_GEOHASH", "false")
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // APK splits for GitHub/F-Droid releases - creates arm64, x86_64, and universal APKs
    // Disabled for App Bundle builds (Play Store) since AAB handles architecture automatically
    // See: https://issuetracker.google.com/402800800
    splits {
        abi {
            // Disable splits when building App Bundle to avoid conflict
            isEnable = project.gradle.startParameter.taskNames.none { 
                it.contains("bundle", ignoreCase = true) 
            }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true  // For F-Droid and fallback
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR (ZXing shared by both flavors)
    implementation(libs.zxing.core)

    // Full-only: ML Kit barcode scanning (live camera QR)
    "fullImplementation"(libs.mlkit.barcode.scanning)

    // Full-only: CameraX (for ML Kit camera preview)
    "fullImplementation"(libs.androidx.camera.camera2)
    "fullImplementation"(libs.androidx.camera.lifecycle)
    "fullImplementation"(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // Full-only: Google Play Services Location (for geohash features)
    "fullImplementation"(libs.gms.location)

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // WorkManager for Android 15+ boot-complete FGS workaround
    implementation(libs.androidx.work.runtime.ktx)

    
    // EXIF orientation handling for images
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
