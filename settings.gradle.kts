pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") {
			name = "Fabric"
		}
		mavenCentral()
		gradlePluginPortal()
	}

	// Plugin versions cannot be read from gradle.properties here (properties are
	// not injected yet during plugin management), so they are pinned literally.
	// All other toolchain versions live in gradle.properties.
	plugins {
		id("net.fabricmc.fabric-loom") version "1.19.3"
		id("org.jetbrains.kotlin.jvm") version "2.4.21"
	}
}

rootProject.name = "fancy-crests-mod"
