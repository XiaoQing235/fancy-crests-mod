package dev.fancycrests.core.config

import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Moves existing FancyCrests files once, before the configuration or database is opened. */
internal object ConfigMigration {
    fun migrate(configRoot: Path): Path {
        val directory = configRoot.resolve("FancyCrests")
        val configs = listOf(
            "fancycrests.json" to "config.yaml",
            "fancycrests.json.last-good" to "config.yaml.last-good",
            "fancycrests.baseline.json" to "baseline.yaml",
        )
        val databaseFiles = listOf("fancycrests-data.db", "fancycrests-data.db-wal", "fancycrests-data.db-shm")
        val moves = configs + databaseFiles.map { it to it }
        // Check the whole set before moving anything. Never replace a destination.
        for ((oldName, newName) in moves) {
            check(!(Files.exists(configRoot.resolve(oldName)) && Files.exists(directory.resolve(newName)))) {
                "Cannot migrate FancyCrests: both $oldName and FancyCrests/$newName exist"
            }
        }
        Files.createDirectories(directory)
        for ((oldName, newName) in configs) {
            val source = configRoot.resolve(oldName)
            if (!Files.exists(source)) continue
            val destination = directory.resolve(newName)
            val original = Files.readString(source, StandardCharsets.UTF_8)
            // Retain invalid files verbatim so normal last-good/protection handling can report them.
            val converted = try {
                ConfigYaml.dump(JsonParser.parseString(original))
            } catch (_: Exception) {
                original
            }
            val temp = directory.resolve("$newName.migrating")
            check(!Files.exists(temp)) { "Incomplete FancyCrests migration: $temp" }
            Files.writeString(temp, converted, StandardCharsets.UTF_8)
            Files.move(temp, destination)
            Files.delete(source)
        }
        for (name in databaseFiles) {
            val source = configRoot.resolve(name)
            if (Files.exists(source)) Files.move(source, directory.resolve(name))
        }
        return directory
    }
}
