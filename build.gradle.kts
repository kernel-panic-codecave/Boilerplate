import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.konan.properties.loadProperties

plugins {
	java
	alias(libs.plugins.architectury)
	alias(libs.plugins.architectury.loom) apply false
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.compose)
	alias(libs.plugins.modfusioner)
	alias(libs.plugins.modpublisher)
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

	// Boilerplate links against Archie's full published artifact, which transitively pulls
	// in everything Archie itself integrates with (REI, cloth-config, modmenu, catalogue,
	// common-storage-lib, Compose's own deps, ...) even though Boilerplate doesn't use most
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
		maven("https://maven.blamejared.com/")
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
		maven("https://cursemaven.com") {
			content {
				includeGroup("curse.maven")
			}
		}
		maven {
			name = "createmod maven"
			url = uri("https://maven.createmod.net/")
		}
		maven {
			url = uri("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
		}
		maven {
			url = uri("https://modmaven.dev/")
		}
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

	// One MavenPublication per module, published to kernelpanicsoft.net's Reposilite - the same
	// place Archie publishes to, so an addon can compile against Boilerplate exactly as Boilerplate
	// compiles against Archie.
	apply(plugin = "maven-publish")

	// base.archivesName only reaches its final "boilerplate-fabric"-style value once the module's
	// own build.gradle.kts has run, so reading it here would still see the root's bare "boilerplate".
	// Defer until this project has finished configuring.
	afterEvaluate {
		extensions.configure<PublishingExtension>("publishing") {
			publications {
				create<MavenPublication>("maven") {
					artifactId = base.archivesName.get()
					from(components["java"])
				}
			}

			repositories {
				mavenLocal()
				maven {
					name = "Reposilite"
					val releasesUrl = "https://maven.kernelpanicsoft.net/releases"
					val snapshotsUrl = "https://maven.kernelpanicsoft.net/snapshots"

					url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl)

					credentials {
						username = localProperties?.getProperty("reposilite.username")
							?: System.getenv("REPOSILITE_USERNAME")
						password = localProperties?.getProperty("reposilite.password")
							?: System.getenv("REPOSILITE_PASSWORD")
					}
				}
			}
		}
	}
}

allprojects {
	apply(plugin = "java")
	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
	apply(plugin = "org.jetbrains.kotlin.plugin.compose")
	apply(plugin = "org.jetbrains.compose")
	apply(plugin = "architectury-plugin")
	apply(plugin = "maven-publish")

	version = "mod_version".prop ?: "0.1.0-SNAPSHOT"
	group = "mod_group".prop ?: "net.kernelpanicsoft.boilerplate"
	base.archivesName = "boilerplate"

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
		projectName = "boilerplate-fabric"
		inputTaskName = "remapJar"
	}

	neoforge {
		projectName = "boilerplate-neoforge"
		inputTaskName = "remapJar"
	}
}

// A `-SNAPSHOT` version publishes as an **alpha**: the suffix is dropped for `-alpha`, and the
// upload is marked alpha on both platforms. Anything else publishes as a release. So the version in
// gradle.properties stays a snapshot while a cycle is in progress and the release type follows from
// it, instead of being a second thing to remember to change at release time.
val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")
val releaseVersion = project.version.toString().removeSuffix("-SNAPSHOT") + if (isSnapshot) "-alpha" else ""

// Uploads the merged jar to CurseForge, Modrinth and a GitHub release. Keys come from
// local.properties on a developer machine or CURSEFORGE_API_KEY/MODRINTH_API_KEY/GITHUB_TOKEN in
// CI, the same way every other credential in this build is resolved.
publisher {
	apiKeys {
		curseforge("curseforge_api_key".localOrEnv)
		modrinth("modrinth_api_key".localOrEnv)
		github("github_token".localOrEnv)
	}

	// CurseForge addresses a project by its numeric id, where Modrinth takes the slug.
	curseID = "1691611"
	modrinthID = "boilerplate-logistics"
	githubRepo = "mod_source".prop!!

	projectVersion = "${libs.versions.minecraft.get()}-$releaseVersion"
	displayName = "Boilerplate-Merged-${projectVersion.get()}"
	gameVersions = listOf(libs.versions.minecraft.get())
	loaders = listOf("neoforge", "fabric")
	curseEnvironment = "both"
	versionType = if (isSnapshot) "alpha" else "release"
	artifact = tasks.fusejars.get()
	javaVersions = listOf(JavaVersion.VERSION_21)

	changelog = file("build/latest-changelog.md")

	// Both sides of the same dependency list, because the two platforms slug some of these
	// differently - `kotlinlangforge` on CurseForge against `kotlin-lang-forge` on Modrinth.
	curseDepends {
		required = listOf("fabric-api", "fabric-language-kotlin", "kotlinlangforge", "architectury-api", "cloth-config", "archie")
	}

	modrinthDepends {
		required = listOf("fabric-api", "fabric-language-kotlin", "kotlin-lang-forge", "architectury-api", "cloth-config", "archie")
	}
}

tasks {
	build {
		finalizedBy(fusejars)
	}
	assemble {
		finalizedBy(fusejars)
	}
	named("publish") {
		dependsOn(publishMod)
	}
	// The loader jars fusioner merges are named as bare paths in its own config, so nothing tells
	// Gradle they are this task's inputs - on a fresh checkout `fusejars` would otherwise run with
	// neither side built and produce a merged jar with no loader metadata in it, which CurseForge
	// rejects for having no neoforge.mods.toml. Locally it is masked by a previous build having
	// left the jars behind.
	fusejars {
		dependsOn(":boilerplate-fabric:remapJar", ":boilerplate-neoforge:remapJar")
	}
	// Writes build/latest-changelog.md (what the publisher uploads) and folds the same entry into
	// CHANGELOG.md, from the commits since the previous tag.
	register<Exec>("generateChangelog") {
		group = "publishing"
		workingDir = rootDir
		val latestChangelog = rootDir.resolve("build/latest-changelog.md")
		doFirst { latestChangelog.parentFile.mkdirs() }
		// The script writes nothing at all when the range comes out empty - a tag sitting on the
		// commit it was cut from, most obviously - and the publisher then fails on a changelog file
		// that does not exist. A release with nothing to report is still a release.
		doLast { if (!latestChangelog.exists()) latestChangelog.writeText("No changes recorded for this release.\n") }
		commandLine(
			"python3", ".github/scripts/generate_release_notes.py",
			"--repo", "mod_source".prop!!.removePrefix("https://github.com/"),
			"--new-tag", "v$releaseVersion",
			"--range-end", "HEAD",
			"--changelog-path", "CHANGELOG.md",
			"--latest-path", "build/latest-changelog.md",
		)
	}
	listOf("publishCurseforge", "publishModrinth", "publishGitHub", "publishMod").forEach {
		named(it) { dependsOn(getByName("generateChangelog")) }
	}
}
