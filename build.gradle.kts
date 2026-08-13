import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.jetbrains.kotlin.konan.properties.loadProperties

plugins {
	java
	alias(libs.plugins.architectury)
	alias(libs.plugins.architectury.loom) apply false
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.modfusioner)
}

architectury.minecraft = libs.versions.minecraft.get()

// Not committed - local.properties (repo root) can hold github_actor/github_token for developer
// machines (MrCrayfish's GitHub Packages, needed transitively through Archie's catalogue
// dependency below); CI would supply GITHUB_ACTOR/GITHUB_TOKEN env vars instead. Same pattern as
// Archie's own root build.gradle.kts.
val localProperties = kotlin.runCatching {
	val localPropsFile = rootDir.resolve("local.properties")
	if (localPropsFile.exists()) loadProperties(localPropsFile.path) else null
}.getOrNull()

val String.prop: String?
	get() = project.properties[this] as? String

val String.localOrEnv: String?
	get() = localProperties?.getProperty(this) ?: System.getenv(this.uppercase())

subprojects {
	apply(plugin = "dev.architectury.loom")

	val loom = project.extensions.getByName<LoomGradleExtensionAPI>("loom")

	configure<LoomGradleExtensionAPI> {
		silentMojangMappingsLicense()
	}

	// Tubular Storage links against Archie's full published artifact, which transitively pulls
	// in everything Archie itself integrates with (REI, cloth-config, modmenu, catalogue,
	// common-storage-lib, Compose's own deps, ...) even though Tubular Storage doesn't use most
	// of those directly yet. This repo list mirrors Archie's own root build.gradle.kts exactly,
	// for that reason - trim it once dependencies are pinned down and it's clear what's actually
	// needed.
	repositories {
		val githubUsername = "github_actor".localOrEnv
		val githubToken = "github_token".localOrEnv
		mavenCentral()
		mavenLocal()
		google {
			content {
				includeGroupByRegex("androidx\\..*")
				includeGroupByRegex("com\\.android.*")
			}
		}
		maven {
			name = "kernelpanic releases"
			url = uri("https://maven.kernelpanicsoft.net/releases")
		}
		maven {
			name = "kernelpanic snapshots"
			url = uri("https://maven.kernelpanicsoft.net/snapshots")
		}
		maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
		maven("https://maven.parchmentmc.org")
		maven("https://maven.fabricmc.net/")
		maven("https://maven.neoforged.net/releases/")
		maven("https://maven.terraformersmc.com/releases/")
		maven("https://repo.nyon.dev/releases")
		maven("https://maven.isxander.dev/releases") {
			name = "Xander Maven"
		}
		maven("https://maven.resourcefulbees.com/repository/maven-public/") {
			content {
				includeGroup("earth.terrarium.common_storage_lib")
			}
		}
		maven {
			url = uri("https://maven.pkg.github.com/MrCrayfish/Maven")
			credentials {
				username = githubUsername
				password = githubToken
			}
		}
		maven("https://maven.architectury.dev/")
	}

	@Suppress("UnstableApiUsage")
	dependencies {
		"minecraft"(rootProject.libs.minecraft)
		"mappings"(loom.layered {
			officialMojangMappings()
			parchment(rootProject.libs.parchment)
		})

		compileOnly("org.jetbrains:annotations:24.1.0")
	}
}

allprojects {
	apply(plugin = "java")
	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
	apply(plugin = "architectury-plugin")

	version = "mod_version".prop ?: "0.1.0-SNAPSHOT"
	group = "mod_group".prop ?: "net.kernelpanicsoft.tubularstorage"
	base.archivesName = "tubularstorage"

	tasks.withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.release.set(21)
	}

	architectury {
		compileOnly()
	}

	java.withSourcesJar()
}

// Merges the fabric+neoforge jars into one distributable artifact.
fusioner {
	packageGroup = project.group.toString()
	mergedJarName = "${project.base.archivesName.get()}-merged-${libs.versions.minecraft.get()}"
	jarVersion = project.version.toString()
	outputDirectory = "build/artifacts"

	fabric {
		projectName = "tubularstorage-fabric"
		inputTaskName = "remapJar"
	}

	neoforge {
		projectName = "tubularstorage-neoforge"
		inputTaskName = "remapJar"
	}
}

tasks {
	build {
		finalizedBy(fusejars)
	}
	assemble {
		finalizedBy(fusejars)
	}
}
