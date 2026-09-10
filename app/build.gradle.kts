import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.dorumrr.privacyflip"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dorumrr.privacyflip"
        minSdk = 24
        targetSdk = 35

        versionCode = 25
        versionName = "2.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Make version available in BuildConfig
        buildConfigField("String", "VERSION_NAME", "\"${versionName}\"")
    }

    // Disable dependency metadata for F-Droid compliance
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    // Load keystore properties for release signing
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file((keystoreProperties["storeFile"] as String).trim())
                storePassword = (keystoreProperties["storePassword"] as String).trim()
                keyAlias = (keystoreProperties["keyAlias"] as String).trim()
                keyPassword = (keystoreProperties["keyPassword"] as String).trim()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val appName = "PrivacyFlip"
                val versionName = variant.versionName
                val buildType = variant.buildType.name
                val outputFileName = "${appName}-v${versionName}-${buildType}.apk"
                output.outputFileName = outputFileName
            }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs the app's real resources/manifest on the test classpath to
            // resolve things like the accessibility service's manifest entry and string
            // resources - without this it fails with opaque "resource not found" errors.
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// No global exclusions - let dependencies work naturally

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Pure Android Views (NO GOOGLE DEPENDENCIES)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Navigation (excluding Google Material library)
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.5") {
        exclude(group = "com.google.android.material", module = "material")
    }
    implementation("androidx.navigation:navigation-ui-ktx:2.7.5") {
        exclude(group = "com.google.android.material", module = "material")
    }

    // ViewBinding
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Root access library
    implementation("com.github.topjohnwu.libsu:core:5.0.4")
    implementation("com.github.topjohnwu.libsu:service:5.0.4")

    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Dhizuku API
    implementation("io.github.iamr0s:Dhizuku-API:2.5.4")

    // Unit tests (J1) - JUnit 4, the standard for local Android unit tests, no Android
    // framework dependency needed for the pure string-parsing logic these tests target.
    // Eclipse Public License 1.0. One transitive dependency (hamcrest-core), already the
    // de-facto default for this kind of test in every Android project.
    testImplementation("junit:junit:4.13.2")

    // Robolectric - runs real Android framework classes (KeyguardManager, WorkManager,
    // AccessibilityService, SharedPreferences) on the plain JVM, so code that reacts to lock
    // state and schedules background work can be unit-tested with a real red/green test instead
    // of only code-reading and adversarial review. Test-only (testImplementation): never
    // packaged into the APK, so it has no effect on the shipped app's size, its dependency
    // footprint, or F-Droid's reproducible-build comparison. Apache 2.0 licence, maintained by
    // Google plus a large open source community, the de-facto standard for this kind of Android
    // unit test. Heavier than the other test deps here (its own shadow classes for large chunks
    // of the Android framework), which is exactly why it stays test-only and nothing in the main
    // dependency block above changes.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // ApplicationProvider.getApplicationContext() - the fake, real-enough Context Robolectric
    // tests construct a service or receiver against. Apache 2.0, maintained by Google.
    testImplementation("androidx.test:core:1.6.1")
    // Synchronous WorkManager for tests (androidx.work:work-runtime-ktx's own test artifact,
    // same version already used above) - lets a test check whether a specific unique work name
    // was actually enqueued, instead of WorkManager's normal async scheduling.
    testImplementation("androidx.work:work-testing:2.9.0")
}
