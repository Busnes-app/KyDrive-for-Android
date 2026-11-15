// Floors for build-tool transitives with published advisories that AGP 9.5.1 still requests,
// copied from KyPost-for-Android. Constraints only raise a version; drop a line once AGP's own
// request reaches it.
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("org.apache.commons:commons-lang3:3.21.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("com.google.guava:guava:33.7.2-jre")
            // AGP's built-in Kotlin would otherwise compile with the older KGP it bundles.
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
