// Floors for build-tool transitives with published advisories, from libs.versions.toml. The app
// module applies the same floors to lint, which resolves its own classpath.
buildscript {
    dependencies {
        constraints {
            libs.bundles.build.tool.floors.get().forEach { classpath(it) }
            // AGP's built-in Kotlin would otherwise compile with the older KGP it bundles.
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
