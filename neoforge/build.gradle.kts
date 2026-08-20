import net.kernelpanicsoft.archie.plugin.runtimeLibrary

plugins {
	alias(libs.plugins.shadow)
	alias(libs.plugins.archie)
}

architectury {
	platformSetupLoomIde()
	neoForge()
}

configurations {
	create("common")
	create("shadowCommon")
	configureEach {
		// Keep the NeoForge Kotlin runtime provided by KotlinLangForge only.
		exclude(group = "thedarkcolour", module = "kotlinforforge-neoforge")
		exclude(group = "remapped.thedarkcolour", module = "kotlinforforge-neoforge-1d1bcbf2")
	}
	compileClasspath.get().extendsFrom(configurations["common"])
	runtimeClasspath.get().extendsFrom(configurations["common"])
	testCompileClasspath.get().extendsFrom(compileClasspath.get())
	testRuntimeClasspath.get().extendsFrom(runtimeClasspath.get())
}

loom {
	accessWidenerPath.set(project(":tubularstorage-common").loom.accessWidenerPath)

	mods {
		maybeCreate("main").apply {
			sourceSet(sourceSets.main.get())
			sourceSet(project(":tubularstorage-common").sourceSets.main.get())
		}
	}

	runs {
		getByName("client") {
			name = "Minecraft Client"
			source(sourceSets.main.get())
			vmArgs("-XX:+AllowEnhancedClassRedefinition")
		}
		getByName("server") {
			name = "Minecraft Server"
			source(sourceSets.main.get())
			vmArgs("-XX:+AllowEnhancedClassRedefinition")
		}
		create("gametest") {
			server()
			name = "Minecraft GameTest"
			property("neoforge.enableGameTest", "true")
			property("neoforge.gameTestServer", "true")
			property("archie.gametest.side", "server")
			property("archie.gametest", "true")
			property("archie.gametest.modid", "tubularstorage")
		}
		create("gametestClient") {
			client()
			name = "Minecraft GameTest Client"
			property("neoforge.enableGameTest", "true")
			property("archie.gametest.side", "client")
			property("archie.gametest", "true")
			property("archie.gametest.modid", "tubularstorage")
		}
		// "gradlew runDatagen" - see TubularStorageBlockStateProvider. Writes to common's own
		// src/main/generated (wired in as an extra resources root there), not src/main/resources
		// directly - Minecraft's datagen cache deletes anything in its output directory it didn't
		// just write, which would otherwise destroy the hand-placed textures/gametest structures
		// living alongside it.
		create("datagen") {
			data()
			name = "Minecraft Datagen"
			property("archie.datagen", "true")
			property("archie.datagen.client", "true")
			property("archie.datagen.server", "true")
			programArgs("--all", "--mod", "tubularstorage")
			programArgs("--output", file("../common/src/main/generated").absolutePath)
		}
	}
}

dependencies {
	neoForge(libs.neoforge)
	modApi(libs.architectury.neoforge)
	implementation(libs.kotlin.neoforge)
	modApi(libs.archie.neoforge)
	modImplementation(libs.flywheel.neoforge)
	// compose.runtime's own transitive deps, pulled in transitively via archie.neoforge - already
	// embedded in archie-core-neoforge's own jar for a real deployed environment, but Loom's dev-run
	// GAMELIBRARY discovery doesn't walk a dependency's transitive deps the way production JarJar
	// packaging does, so each needs its own explicit declaration here too, or runClientNeoForge
	// crashes the moment Compose touches one of them (e.g. Recomposer needing
	// androidx.collection.MutableScatterSet) - see CLAUDE.md's NeoForge + Kotlin classloading caveat.
	runtimeLibrary(libs.androidx.annotation)
	runtimeLibrary(libs.androidx.collection)
	runtimeLibrary(libs.okio)

	modImplementation(libs.clothConfig.neoforge)

	modCompileOnly(libs.archie.gametest.neoforge)
	modLocalRuntime(libs.archie.gametest.neoforge)

	modCompileOnly(libs.archie.datagen.neoforge)
	modLocalRuntime(libs.archie.datagen.neoforge)

	modLocalRuntime("curse.maven:nbtedit-678133:6125444")

	"common"(project(":tubularstorage-common", "namedElements")) { isTransitive = false }
	"shadowCommon"(project(":tubularstorage-common", "transformProductionNeoForge")) { isTransitive = false }
}

modResources {
	filesMatching.add("META-INF/neoforge.mods.toml")
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-neoforge")

	processResources {
		from(project(":tubularstorage-common").sourceSets.main.get().resources) {
			include("assets/tubularstorage/**")
			include("data/tubularstorage/**")
			include("tubularstorage.accesswidener")
			include("tubularstorage-common.mixins.json")
		}
		dependsOn(processTestResources)
	}

	classes {
		finalizedBy(testClasses)
	}

	shadowJar {
		exclude("fabric.mod.json")
		configurations =
			listOf(project.configurations.getByName("shadowCommon"), project.configurations.getByName("shadow"))
		archiveClassifier.set("dev-shadow")
	}

	remapJar {
		inputFile.set(shadowJar.get().archiveFile)
		atAccessWideners.set(setOf(loom.accessWidenerPath.get().asFile.name))
		dependsOn(shadowJar)
	}

	jar.get().archiveClassifier.set("dev")

	jar {
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		from(project(":tubularstorage-common").sourceSets.main.get().output)
	}

	sourcesJar {
		val commonSources = project(":tubularstorage-common").tasks.sourcesJar
		dependsOn(commonSources)
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		from(commonSources.get().archiveFile.map { zipTree(it) })
	}
}
