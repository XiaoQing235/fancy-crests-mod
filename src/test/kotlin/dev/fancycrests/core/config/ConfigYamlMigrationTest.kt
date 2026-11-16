package dev.fancycrests.core.config

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ConfigYamlMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generated defaults are yaml that reloads successfully`() {
        val path = directory.resolve("config.yaml")
        val service = ConfigService(path, Executor { it.run() })
        try {
            assertTrue(service.loadOrCreate() is ConfigLoadResult.Success)
            val yaml = Files.readString(path)
            assertTrue(yaml.contains("channels:"))
            assertTrue(service.loadOrCreate() is ConfigLoadResult.Success)
        } finally {
            service.stop()
        }
    }

    @Test
    fun `yaml supports unquoted true and false keys and rejects duplicates`() {
        val parsed = ConfigYaml.parse("""
            conditions:
              nick:
                true: "%player%"
                false: "~%player%"
        """.trimIndent()).asJsonObject
        val nick = parsed.getAsJsonObject("conditions").getAsJsonObject("nick")
        assertEquals("%player%", nick.get("true").asString)
        assertEquals("~%player%", nick.get("false").asString)
        assertFailsWith<IllegalArgumentException> { ConfigYaml.parse("a: 1\na: 2") }
        assertFailsWith<IllegalArgumentException> { ConfigYaml.parse("a: &a [*a]") }
    }

    @Test
    fun `legacy files move without losing configuration or database bytes`() {
        val json = """{"nickname":{"presets":["&aGreen"]},"titles":[]}"""
        Files.writeString(directory.resolve("fancycrests.json"), json)
        Files.writeString(directory.resolve("fancycrests.json.last-good"), json)
        Files.writeString(directory.resolve("fancycrests.baseline.json"), json)
        for (name in listOf("fancycrests-data.db", "fancycrests-data.db-wal", "fancycrests-data.db-shm")) {
            Files.writeString(directory.resolve(name), name)
        }
        val target = ConfigMigration.migrate(directory)
        assertEquals(JsonParser.parseString(json), ConfigYaml.parse(Files.readString(target.resolve("config.yaml"))))
        assertEquals(JsonParser.parseString(json), ConfigYaml.parse(Files.readString(target.resolve("config.yaml.last-good"))))
        assertTrue(Files.isRegularFile(target.resolve("baseline.yaml")))
        assertFalse(Files.exists(directory.resolve("fancycrests.json")))
        for (name in listOf("fancycrests-data.db", "fancycrests-data.db-wal", "fancycrests-data.db-shm")) {
            assertEquals(name, Files.readString(target.resolve(name)))
            assertFalse(Files.exists(directory.resolve(name)))
        }
        assertEquals(target, ConfigMigration.migrate(directory))
    }

    @Test
    fun `destination conflict fails before moving anything`() {
        Files.writeString(directory.resolve("fancycrests.json"), "{}")
        Files.writeString(directory.resolve("fancycrests-data.db"), "old")
        val target = Files.createDirectory(directory.resolve("FancyCrests"))
        Files.writeString(target.resolve("fancycrests-data.db"), "existing")
        assertFailsWith<IllegalStateException> { ConfigMigration.migrate(directory) }
        assertTrue(Files.exists(directory.resolve("fancycrests.json")))
        assertEquals("old", Files.readString(directory.resolve("fancycrests-data.db")))
        assertEquals("existing", Files.readString(target.resolve("fancycrests-data.db")))
    }
}
