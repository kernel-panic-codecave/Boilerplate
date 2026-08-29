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
	accessWidenerPath.set(project(":boilerplate-common").loom.accessWidenerPath)

	mods {
		maybeCreate("main").apply {
			sourceSet(sourceSets.main.get())
			sourceSet(project(":boilerplate-common").sourceSets.main.get())
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
			property("archie.gametest.modid", "boilerplate")
		}
		create("gametestClient") {
			client()
			name = "Minecraft GameTest Client"
			property("neoforge.enableGameTest", "true")
			property("archie.gametest.side", "client")
			property("archie.gametest", "true")
			property("archie.gametest.modid", "boilerplate")
		}
		// "gradlew runDatagen" - see BoilerplateBlockStateProvider. Writes to common's own
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
			programArgs("--all", "--mod", "boilerplate")
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

	modImplementation(libs.clothConfig.neoforge)

	modCompileOnly(libs.archie.gametest.neoforge)
	modLocalRuntime(libs.archie.gametest.neoforge)

	modCompileOnly(libs.archie.datagen.neoforge)
	modLocalRuntime(libs.archie.datagen.neoforge)

	// REI's own plugin class lives in `common` (its api is loader-agnostic) - only real REI
	// presence for local dev testing is needed here.
	modLocalRuntime(libs.rei.neoforge)

	// BoilerplateJEIPlugin lives in `common`, compiled against JEI's own shared common-api artifact
	// - only real JEI presence for local dev testing is needed here.
	modLocalRuntime(libs.jei.neoforge)

	// Same shape as JEI: EMI's api is only published as a classifier on its per-loader jar, no
	// shared common artifact.
	modCompileOnly("${libs.emi.neoforge.get()}:api")
	modLocalRuntime(libs.emi.neoforge)

	runtimeLibrary(libs.okio)
	runtimeLibrary(libs.androidx.annotation)
	runtimeLibrary(libs.androidx.collection)

	modLocalRuntime("curse.maven:nbtedit-678133:6125444")

	"common"(project(":boilerplate-common", "namedElements")) { isTransitive = false }
	"shadowCommon"(project(":boilerplate-common", "transformProductionNeoForge")) { isTransitive = false }
}

modResources {
	filesMatching.add("META-INF/neoforge.mods.toml")
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-neoforge")

	processResources {
		from(project(":boilerplate-common").sourceSets.main.get().resources) {
			include("assets/boilerplate/**")
			include("data/boilerplate/**")
			include("boilerplate.accesswidener")
			include("boilerplate-common.mixins.json")
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
		from(project(":boilerplate-common").sourceSets.main.get().output)
	}

	sourcesJar {
		val commonSources = project(":boilerplate-common").tasks.sourcesJar
		dependsOn(commonSources)
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		from(commonSources.get().archiveFile.map { zipTree(it) })
	}
}
