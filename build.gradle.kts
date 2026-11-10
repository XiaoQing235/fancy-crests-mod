import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	kotlin("jvm")
}

val minecraftVersion = project.property("minecraftVersion") as String
val loaderVersion = project.property("loaderVersion") as String
val fabricApiVersion = project.property("fabricApiVersion") as String
val fabricKotlinVersion = project.property("fabricKotlinVersion") as String
val modVersion = project.property("modVersion") as String
val mavenGroup = project.property("mavenGroup") as String
val archivesBaseName = project.property("archivesBaseName") as String

group = mavenGroup
version = modVersion

base {
	archivesName = archivesBaseName
}

repositories {
	// Loom adds the essential repositories (Fabric, Mojang, Maven Central) automatically.
	// Only add repositories here when depending on other mods.
}

loom {
	mods {
		register("fancycrests") {
			sourceSet(sourceSets.main.get())
		}
	}
}

dependencies {
	minecraft("com.mojang:minecraft:$minecraftVersion")
	implementation("net.fabricmc:fabric-loader:$loaderVersion")
	implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	// Kotlin language adapter for Fabric; bundles the Kotlin runtime for the server.
	implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")
}

tasks.processResources {
	val version = project.version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

java {
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
	withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.encoding = "UTF-8"
}

kotlin {
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_25)
	}
}

tasks.jar {
	val baseName = archivesBaseName
	inputs.property("baseName", baseName)

	from(rootProject.file("LICENSE")) {
		rename { "LICENSE_$baseName" }
	}
}
