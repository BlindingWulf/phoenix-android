import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Owner-only local file. It is intentionally absent from source control and contains no defaults.
val ownerSigning = Properties().apply {
    val localFile = rootProject.file("keystore.properties")
    if (localFile.isFile) localFile.inputStream().use(::load)
}

// Minecraft.How Google browser handoff. Empty unless the provider issues a public client ID and
// documents same-origin authorize/exchange paths; no secret belongs here. Values are validated
// so a property can never inject code into BuildConfig.
fun minecraftHowProperty(name: String, pattern: Regex): String {
    val value = providers.gradleProperty("phoenix.minecraftHow.$name").orNull?.trim().orEmpty()
    require(value.isEmpty() || value.matches(pattern)) { "phoenix.minecraftHow.$name is malformed" }
    return value
}
val minecraftHowClientId = minecraftHowProperty("clientId", Regex("[A-Za-z0-9._-]{1,128}"))
val minecraftHowAuthorizePath = minecraftHowProperty("authorizePath", Regex("/[A-Za-z0-9._~/-]{1,200}"))
val minecraftHowExchangePath = minecraftHowProperty("exchangePath", Regex("/[A-Za-z0-9._~/-]{1,200}"))

// Public link to a published copy of deploy/oracle-proxy (a release .zip), for Oracle's "Deploy to Oracle Cloud" page.
// Oracle accepts GitHub, GitLab and Object Storage links. Empty: Phoenix saves the bundled copy for the owner to upload.
// Public configuration only; no credential belongs here.
val oracleStackZipUrl = providers.gradleProperty("phoenix.oracle.stackZipUrl").orNull?.trim().orEmpty().also {
    require(it.isEmpty() || it.matches(Regex("https://(github\\.com|gitlab\\.com|objectstorage\\.[a-z0-9-]+\\.oraclecloud\\.com)/[A-Za-z0-9._~/%-]{1,400}\\.zip"))) {
        "phoenix.oracle.stackZipUrl must be an https GitHub, GitLab or OCI Object Storage .zip link"
    }
}

// The Oracle Resource Manager stack, zipped from deploy/oracle-proxy into the APK's assets. Publish the same zip for zipUrl.
// Third-party notices for bundled agents, packaged as assets/licenses/.
val licenseNotices = tasks.register<Copy>("licenseNotices") {
    from(rootProject.file("tools/playit/LICENSE-PLAYIT-AGENT.txt"))
    into(layout.buildDirectory.dir("generated/notices/licenses"))
}

val oracleStackZip = tasks.register<Zip>("oracleStackZip") {
    from(rootProject.file("deploy/oracle-proxy")) { include("*.tf", "schema.yaml", "bootstrap.sh", "phoenix_pair.py", "README.md") }
    archiveFileName.set("phoenix-oracle-proxy.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

android {
    namespace = "dev.phoenix.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.phoenix.server"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MINECRAFT_HOW_CLIENT_ID", "\"$minecraftHowClientId\"")
        buildConfigField("String", "MINECRAFT_HOW_AUTHORIZE_PATH", "\"$minecraftHowAuthorizePath\"")
        buildConfigField("String", "MINECRAFT_HOW_EXCHANGE_PATH", "\"$minecraftHowExchangePath\"")
        buildConfigField("String", "ORACLE_STACK_ZIP_URL", "\"$oracleStackZipUrl\"")
        // The bundled NetBird client is arm64-only and the only verified runtime device is ARM64, so x86_64 natives
        // (~10 MB) are not packaged. Remove this filter and the asset pattern below to restore x86_64 emulator builds.
        ndk { abiFilters += "arm64-v8a" }
    }
    // Skip the ~52 MB x86_64 JRE data asset that matches the filtered ABI. Setting the list replaces aapt's defaults,
    // so they are repeated here before the x86_64 entry.
    androidResources {
        ignoreAssetsPatterns += listOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~", "!x86_64")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    sourceSets.getByName("main") {
        java.srcDir("../runtime/src/main/java")
        jniLibs.srcDir("../runtime/generated/jniLibs")
        assets.srcDir("../runtime/generated/assets")
    }
    // libplayitagent.so is Playit's official agent (playitd, tools/playit), an executable run as a child process.
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }

    buildTypes {
        debug { applicationIdSuffix = ".stage10a"; versionNameSuffix = "-dev" }
        // The debug app (same id, signing key and data) without the debuggable flag, so Android compiles it ahead of
        // time instead of interpreting it. Install with assembleFast for real-world speed; debug keeps run-as access.
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            isJniDebuggable = false
            versionNameSuffix = "-fast"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        // A clean-install check: the production application id (its own data, its own NetBird identity) signed with the
        // debug key, so it installs beside the .stage10a development app without touching that app's server or world.
        create("freshCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ""
            versionNameSuffix = "-fresh"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        // A throwaway fresh-install copy for checking screens: its own id and data, so it can be installed and removed
        // without touching the development app, its running server or its accounts. NetBird can't connect from it.
        create("uiCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uicheck"
            versionNameSuffix = "-uicheck"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ownerSigning.containsKey("storeFile")) {
                signingConfig = signingConfigs.create("ownerRelease") {
                    storeFile = file(ownerSigning.getProperty("storeFile"))
                    storePassword = ownerSigning.getProperty("storePassword")
                    keyAlias = ownerSigning.getProperty("keyAlias")
                    keyPassword = ownerSigning.getProperty("keyPassword")
                }
            }
        }
        // The release configuration (R8, not debuggable, BuildConfig.DEBUG false) under a throwaway id and the debug key,
        // so a device can run the release code beside the other copies. It is a test build only; the shipped app is release.
        create("rcCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".rccheck"
            versionNameSuffix = "-rccheck"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("fast") {
        java.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(oracleStackZip, Zip::getDestinationDirectory)
        variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/notices").get().asFile.absolutePath)
    }
}
// AGP 9 records the directory as an input but did not always run the zip first; make the order explicit. Release lint
// (lintVital, part of assembleRelease) reads the same generated asset directories, and Gradle 9 refuses it without the edge.
tasks.matching { it.name.matches(Regex("merge\\w+Assets|generate\\w+LintVitalReportModel|lintVitalAnalyze\\w+")) }
    .configureEach { dependsOn(oracleStackZip, licenseNotices) }

dependencies {
    // Built from pinned NetBird v0.80 client sources; only arm64-v8a is bundled today.
    // Separate artifacts embed the app-private WireGuard socket path for each applicationId.
    debugImplementation(files("libs/netbird-android-v0.80.0-debug.aar"))
    // The fast build keeps the .stage10a application id, so it needs the same socket-path artifact as debug.
    "fastImplementation"(files("libs/netbird-android-v0.80.0-debug.aar"))
    "uiCheckImplementation"(files("libs/netbird-android-v0.80.0-debug.aar"))
    releaseImplementation(files("libs/netbird-android-v0.80.0-release.aar"))
    // The production id needs the artifact whose WireGuard socket path belongs to dev.phoenix.server.
    "freshCheckImplementation"(files("libs/netbird-android-v0.80.0-release.aar"))
    // The release-configuration test copy uses the release artifact like release (NetBird is not exercised under its id).
    "rcCheckImplementation"(files("libs/netbird-android-v0.80.0-release.aar"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":core"))
    // Compose 1.11 is the latest stable line compatible with API 36 / AGP 9.0.
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the real one lets mod and pack checks run on real jars.
    testImplementation("org.json:json:20250107")
}
