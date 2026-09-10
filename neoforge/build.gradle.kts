
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
			property("devauth.enabled", "true")
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

	// JEI's fluid ingredient type is loader-specific, so the converter that names it has to be
	// compiled here - see JeiResourceStacks.
	modCompileOnly(libs.jei.neoforge)

	when (rootProject.property("recipe_viewer") as? String)
	{
		"jei" -> modLocalRuntime(libs.jei.neoforge)
		"rei" -> modLocalRuntime(libs.rei.neoforge)
		"emi" -> modLocalRuntime(libs.emi.neoforge)
	}

	modLocalRuntime(libs.devauth.neoforge)

	// Compose's own transitive libraries (okio, androidx.annotation, androidx.collection) are
	// deliberately *not* declared here. They have to reach NeoForge stamped `FMLModType: GAMELIBRARY`
	// or the first Compose screen dies with `NoClassDefFoundError:
	// androidx/collection/MutableScatterSet` - and Archie's own neoforge jar already nests them that
	// way (`META-INF/jars/collection-jvm-1.4.0-PatchedFMLModType.jar`) with the
	// `META-INF/jarjar/metadata.json` NeoForge needs to load them.
	//
	// Declaring them here as well is what breaks: loom remaps this module's copy while FML patches
	// Archie's, and the two module names both export the package - `Modules collection.jvm.<hash> and
	// collection.jvm.PatchedFMLModType export package androidx.collection`, which kills the launch
	// before any mod loads. Exactly one side provides them, and that side is Archie.

	modLocalRuntime("curse.maven:nbtedit-678133:6125444")
	modCompileOnly(libs.mekanism)
	modLocalRuntime(libs.mekanism)

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
