import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
}

// Every exported component is reachable by any app on the device. The merged manifest must
// export exactly these; checkExportedComponents<Variant> fails on anything else.
val allowedExportedComponents = setOf(
    "org.kysecurity.drive.MainActivity", // launcher
    // Third-party; callers need the platform DUMP permission.
    "androidx.profileinstaller.ProfileInstallReceiver",
)

android {
    namespace = "org.kysecurity.drive"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.kysecurity.drive"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // FLAG_SECURE is unconditional unless the debug type below overrides this.
        buildConfigField("boolean", "ALLOW_SCREENSHOTS", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Screenshot capture only: ./gradlew :app:assembleDebug -PallowScreenshots=true
            val allowScreenshots = project.findProperty("allowScreenshots") == "true"
            buildConfigField("boolean", "ALLOW_SCREENSHOTS", allowScreenshots.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        abortOnError = true
        fatal += listOf(
            "UnknownIssueId",
            "ExportedService",
            "ExportedReceiver",
            "ExportedContentProvider",
            "GrantAllUris",
            "UnsafeIntentLaunch",
            "TrustAllX509TrustManager",
            "InsecureBaseConfiguration",
            "CustomX509TrustManager",
            "BadHostnameVerifier",
            "AllowBackup",
            "WorldReadableFiles",
            "WorldWriteableFiles",
            "HardcodedDebugMode",
            "ContentDescription",
            "HardcodedText",
            "LabelFor",
            "SetTextI18n",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.zxing.embedded)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // Lint runs from its own classpath, which the root buildscript floors do not reach.
    constraints { libs.bundles.build.tool.floors.get().forEach { add("androidLintTool", it) } }
}

// Screenshot builds only (scripts/screenshots.sh passes -PscreenshotCa): a debuggable APK
// that also trusts the throwaway CA of the script's HTTPS test server, through debug-overrides
// in a generated copy of the network config. src/main and every other build are untouched.
val screenshotCa = providers.gradleProperty("screenshotCa").orNull
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    if (screenshotCa == null) return@onVariants
    val dir = layout.buildDirectory.dir("generated/screenshotCa/res").get().asFile
    File(dir, "raw").mkdirs()
    File(screenshotCa).copyTo(File(dir, "raw/screenshot_ca.pem"), overwrite = true)
    File(dir, "xml").mkdirs()
    val main = file("src/main/res/xml/network_security_config.xml").readText()
    val overrides = """    <debug-overrides><trust-anchors><certificates src="@raw/screenshot_ca" /></trust-anchors></debug-overrides>
</network-security-config>"""
    File(dir, "xml/network_security_config.xml").writeText(main.replace("</network-security-config>", overrides))
    variant.sources.res?.addStaticSourceDirectory(dir.path)
}

// RealServerTest runs only with a built kydrive-server; a different binary must not reuse results.
tasks.withType<Test>().configureEach {
    inputs.property("kydriveServerBin", providers.environmentVariable("KYDRIVE_SERVER_BIN").orElse(""))
}

androidComponents.onVariants { variant ->
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val allowed = allowedExportedComponents
    val gate = tasks.register("checkExportedComponents${variant.name.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        description = "Fails if the merged manifest exports a component outside the allowlist."
        inputs.file(mergedManifest).withPropertyName("mergedManifest")
        doLast {
            val ns = "http://schemas.android.com/apk/res/android"
            val root = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
                .documentElement
            val exported = listOf("activity", "activity-alias", "service", "receiver", "provider")
                .flatMap { tag ->
                    val nodes = root.getElementsByTagName(tag)
                    (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                }
                .filter { it.getAttributeNS(ns, "exported") == "true" }
                .map { it.getAttributeNS(ns, "name") }
                .toSortedSet()
            if (exported != allowed) {
                throw GradleException(
                    "Exported components must be exactly allowedExportedComponents in " +
                        "app/build.gradle.kts. Unexpected: ${exported - allowed}. " +
                        "Listed but not exported: ${allowed - exported}.",
                )
            }
        }
    }
    tasks.named("check") { dependsOn(gate) }
}
