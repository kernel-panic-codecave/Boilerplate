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

	modCompileOnly("${libs.emi.neoforge.get()}:api")

	when (rootProject.property("recipe_viewer") as? String)
	{
		"jei" -> modLocalRuntime(libs.jei.neoforge)
		"rei" -> modLocalRuntime(libs.rei.neoforge)
		"emi" -> modLocalRuntime(libs.emi.neoforge)
	}

	// Compose runtime pulls these in transitively, but they have to reach NeoForge as *libraries*,
	// not as remapped mod jars. Archie nests them under `META-INF/jars/` (the Fabric layout) with no
	// `META-INF/jarjar/metadata.json` and no `FMLModType` in their manifests, so NeoForge's
	// ModuleClassLoader never loads them and the first Compose screen dies with
	// `NoClassDefFoundError: androidx/collection/MutableScatterSet`. `runtimeLibrary` is what stamps
	// `FMLModType: GAMELIBRARY` on them - Archie already does exactly this for `runtime-desktop`
	// itself, just not for these three. Remove again only once Archie patches all of its nested
	// libraries (and emits jarjar metadata, which shipped NeoForge jars also need).
	runtimeLibrary(libs.okio)
	runtimeLibrary(libs.androidx.annotation)
	runtimeLibrary(libs.androidx.collection)

	modLocalRuntime("curse.maven:nbtedit-678133:6125444")
	modLocalRuntime("curse.maven:mekanism-268560:7904058")

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
