plugins {
	alias(libs.plugins.shadow)
	alias(libs.plugins.archie)
}

architectury {
	platformSetupLoomIde()
	fabric()
}

configurations {
	create("common")
	create("shadowCommon")
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
			vmArg("-XX:+AllowEnhancedClassRedefinition")
		}
		getByName("server") {
			name = "Minecraft Server"
			source(sourceSets.main.get())
			vmArgs("-XX:+AllowEnhancedClassRedefinition")
		}
		create("gametest") {
			server()
			name = "Minecraft GameTest"
			property("fabric-api.gametest")
			property("archie.gametest", "true")
			property("archie.gametest.side", "server")
			property("archie.gametest.modid", "boilerplate")
		}
		create("gametestClient") {
			client()
			name = "Minecraft GameTest Client"
			property("fabric-api.gametest")
			property("archie.gametest", "true")
			property("archie.gametest.side", "client")
			property("archie.gametest.modid", "boilerplate")
		}
		create("datagen") {
			client()
			name = "Minecraft Datagen"
			property("archie.datagen", "true")
			property("archie.datagen.client", "true")
			property("archie.datagen.server", "true")
			property("fabric-api.datagen")
			property("fabric-api.datagen.modid", "boilerplate")
			property("fabric-api.datagen.output-dir", file("../common/src/main/generated").absolutePath)

			runDir = "build/datagen"
		}
	}
}

dependencies {
	modImplementation(libs.fabric.loader)
	modApi(libs.fabric.api)
	modApi(libs.architectury.fabric)
	modImplementation(libs.kotlin.fabric)
	modApi(libs.archie.fabric)
	modImplementation(libs.flywheel.fabric)

	modImplementation(libs.clothConfig.fabric)

	modCompileOnly(libs.archie.gametest.fabric)
	modLocalRuntime(libs.archie.gametest.fabric)

	modCompileOnly(libs.archie.datagen.fabric)
	modLocalRuntime(libs.archie.datagen.fabric)

	modLocalRuntime("curse.maven:nbtedit-678133:6125442")

	"common"(project(":boilerplate-common", "namedElements")) { isTransitive = false }
	"shadowCommon"(project(":boilerplate-common", "transformProductionFabric")) { isTransitive = false }
}

modResources {
	filesMatching.add("fabric.mod.json")
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-fabric")

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
		configurations =
			listOf(project.configurations.getByName("shadowCommon"), project.configurations.getByName("shadow"))
		archiveClassifier.set("dev-shadow")
	}

	remapJar {
		injectAccessWidener.set(true)
		inputFile.set(shadowJar.get().archiveFile)
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
