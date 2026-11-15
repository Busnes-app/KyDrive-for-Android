package org.kysecurity.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Rules from AGENTS.md that a reviewer would otherwise have to remember. Runs from app/. */
class SourceRulesTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun sourcesAreFound() {
        assertTrue("no sources under ${File("src/main/java").absolutePath}", sources.size > 3)
    }

    @Test
    fun nothingLogs() {
        // Logs reach logcat and bug reports; file names, tokens and URLs must never go there.
        val offenders = sources.filter { Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(""").containsMatchIn(it.readText()) }
        assertEquals(emptyList<File>(), offenders)
    }

    @Test
    fun everyActivityIsSecure() {
        val declared = Regex("""<activity\s[^>]*android:name="\.(\w+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue(declared.isNotEmpty())
        for (name in declared) {
            val file = sources.single { it.nameWithoutExtension == name }
            assertTrue("$name must extend SecureActivity", Regex("""class $name\s*:\s*SecureActivity\(\)""").containsMatchIn(file.readText()))
        }
        // Only SecureActivity itself may extend the AppCompat base directly.
        val direct = sources.filter { Regex(""":\s*AppCompatActivity\(\)""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("SecureActivity.kt"), direct)
    }

    @Test
    fun manifestKeepsDataOnTheDevice() {
        for (attr in listOf(
            """android:allowBackup="false"""",
            """android:fullBackupContent="false"""",
            """android:dataExtractionRules="@xml/data_extraction_rules"""",
            """android:networkSecurityConfig="@xml/network_security_config"""",
            """android:usesCleartextTraffic="false"""",
        )) {
            assertTrue("manifest lacks $attr", manifest.contains(attr))
        }
    }

    @Test
    fun onlySystemCasAreTrusted() {
        val config = File("src/main/res/xml/network_security_config.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(config.contains("""cleartextTrafficPermitted="false""""))
        assertEquals(listOf("system"), Regex("""<certificates src="(\w+)"""").findAll(config).map { it.groupValues[1] }.toList())
        assertTrue("no per-domain overrides", !config.contains("<domain-config"))
    }

    @Test
    fun backupAndTransferExcludeEveryDomain() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val domains = listOf("root", "database", "sharedpref", "file", "external", "device_root", "device_database", "device_sharedpref", "device_file")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(rules)!!.groupValues[1]
            for (d in domains) assertTrue("$section misses $d", body.contains("""<exclude domain="$d" path="." />"""))
            assertTrue("$section must not include anything", !body.contains("<include"))
        }
    }
}
