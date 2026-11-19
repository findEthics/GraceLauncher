plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.aboutlibraries)
}

// Only this personal-use credential is embedded; never package the .env file itself.
val tavilyKey = providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.orNull
    ?.lineSequence()?.firstOrNull { it.trimStart().startsWith("TAVILY_API_KEY=") }
    ?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.removeSurrounding("'").orEmpty()
require(tavilyKey.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Invalid Tavily key format" }

android {
    namespace = "com.galaxyrio.gracelauncher"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.galaxyrio.gracelauncher"
        minSdk = 28
        targetSdk = 37
        versionCode = 19
        versionName = "1.2.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TAVILY_API_KEY", "\"$tavilyKey\"")
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Map bundled fonts directly; variable weights share the same native
        // font buffer instead of inflating separate copies on the UI thread.
        noCompress += "ttf"
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    lint {
        // Community translations can be incomplete; Android falls back to English.
        // Keep reporting missing strings without blocking release builds.
        warning += "MissingTranslation"
    }
    // These local tests fake Context/Bundle access, not Android framework behavior.
    testOptions { unitTests.isReturnDefaultValues = true }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.material.kolor)
    implementation(libs.aboutlibraries.core)
    implementation(libs.jsoup)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // JVM unit tests only: android.jar stubs org.json (returnDefaultValues), real parser needed for Wikipedia fixtures
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

aboutLibraries {
    offlineMode = true
    collect {
        includeTestVariants = false
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        excludeFields.add("License.content")
    }
}
