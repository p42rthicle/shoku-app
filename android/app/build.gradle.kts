import java.util.Properties

/** Sync settings live in local.properties (not in git): shoku.syncUrl and shoku.syncKey. */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Compress the code inside the APK (Android unpacks it at install). Same app, much smaller file to send
    // when installing over the air instead of over USB.
    packaging {
        dex { useLegacyPackaging = true }
        jniLibs { useLegacyPackaging = true }
    }
    namespace = "app.shoku"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.shoku"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.9.31"
        buildConfigField("String", "SYNC_URL", "\"${localProps.getProperty("shoku.syncUrl", "")}\"")
        buildConfigField("String", "SYNC_KEY", "\"${localProps.getProperty("shoku.syncKey", "")}\"")
    }

    buildTypes {
        release {
            // Shrunk for sharing: the Anthropic SDK alone is ~15 MB unshrunk. Signed with the debug key so
            // anyone can build and install it. The phone runs this build (debug builds stutter at 120 Hz); backups go
            // through BackupReceiver instead of adb run-as.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        // The shrunk release under another id, to smoke-test R8 next to the installed app.
        create("releaseCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".check"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Robolectric (the screenshot tests) needs the merged resources and assets.
    testOptions.unitTests.isIncludeAndroidResources = true
}

/*
 * JVM tests. Every one runs with the network switched off (data/net/Network.kt): no sync, AI, Open Food
 * Facts or Health Connect call can leave the machine, even when local.properties points at a real server.
 *
 * The Robolectric screenshot tests (src/test/.../screenshots) are slow, so the normal run skips them.
 * Render them with:  ./gradlew :app:testDebugUnitTest -Pscreenshots   (PNGs go to ../docs/screenshots)
 */
val screenshots = providers.gradleProperty("screenshots").isPresent
val screenshotDir = rootProject.layout.projectDirectory.dir("../docs/screenshots").asFile.absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("shoku.network", "off")
    if (screenshots) {
        filter { includeTestsMatching("app.shoku.screenshots.*") }
        systemProperty("roborazzi.test.record", "true")
        systemProperty("shoku.screenshotDir", screenshotDir)
        maxHeapSize = "3g"
        outputs.upToDateWhen { false }
    } else {
        exclude("app/shoku/screenshots/**")
    }
}

ksp {
    // Room writes each schema version here so migrations can be tested against the real old schema.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.play.services.code.scanner)
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)

    // Screenshot tests: real screens rendered on the JVM (Robolectric native graphics + Roborazzi).
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
