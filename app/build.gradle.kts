import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val signingProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

/**
 * Configurable, non-secret build inputs. Override in `gradle.properties`, in
 * `~/.gradle/gradle.properties`, or on the command line with `-Psmartie.…`.
 */
fun setting(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "in.smartie.quotedesk"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.smartie.quotedesk"
        minSdk = 23
        targetSdk = 36
        versionCode = 5
        versionName = "2.0.0-native-beta03"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                storeType = signingProperties.getProperty("storeType", "PKCS12")
            }
        }
    }

    flavorDimensions += "environment"

    productFlavors {
        /**
         * Production keeps the exact package id of the installed app so the
         * version-upgrade path and the existing signing identity are preserved.
         */
        create("production") {
            dimension = "environment"
            buildConfigField("String", "FIREBASE_ENV", "\"production\"")
            buildConfigField("boolean", "IS_STAGING", "false")
            buildConfigField(
                "String",
                "PRIMARY_OWNER_EMAIL",
                "\"${setting("smartie.primaryOwnerEmail", "khan4mudassir1980@gmail.com")}\""
            )
            buildConfigField(
                "String",
                "APP_SHARE_URL",
                "\"${setting("smartie.appShareUrl", "")}\""
            )
        }

        /**
         * Staging installs side by side with production and must only ever be
         * pointed at the staging Firebase project.
         */
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "FIREBASE_ENV", "\"staging\"")
            buildConfigField("boolean", "IS_STAGING", "true")
            buildConfigField(
                "String",
                "PRIMARY_OWNER_EMAIL",
                "\"${setting("smartie.stagingPrimaryOwnerEmail", setting("smartie.primaryOwnerEmail", "khan4mudassir1980@gmail.com"))}\""
            )
            buildConfigField(
                "String",
                "APP_SHARE_URL",
                "\"${setting("smartie.stagingAppShareUrl", setting("smartie.appShareUrl", ""))}\""
            )
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Name every failing test in the console: the HTML report is not
            // reachable when the build runs on CI.
            it.testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = false
            }
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation(platform("com.google.firebase:firebase-bom:33.15.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
