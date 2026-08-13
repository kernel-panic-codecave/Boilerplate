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
	mods {
		maybeCreate("main").apply {
			sourceSet(sourceSets.main.get())
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
		// "gradlew runGametest"/"runGametestClient" - see the dependency comment below for why
		// archie-gametest is safe to have on this run's classpath despite never shipping.
		create("gametest") {
			server()
			name = "Minecraft GameTest"
			property("fabric-api.gametest")
			property("archie.gametest", "true")
			property("archie.gametest.side", "server")
			property("archie.gametest.modid", "tubularstorage")
		}
		create("gametestClient") {
			client()
			name = "Minecraft GameTest Client"
			property("fabric-api.gametest")
			property("archie.gametest", "true")
			property("archie.gametest.side", "client")
			property("archie.gametest.modid", "tubularstorage")
		}
	}
}

dependencies {
	modImplementation(libs.fabric.loader)
	modApi(libs.fabric.api)
	modApi(libs.architectury.fabric)
	modImplementation(libs.kotlin.fabric)
	modApi(libs.archie.fabric)

	// Workaround, not a real Tubular Storage dependency: Archie's own Archie.init() eagerly
	// touches cloth-config's ModifierKeyCode class while initializing its config on the client,
	// even though Archie declares cloth-config as merely compileOnlyApi/suggested. Without this
	// present at runtime, loading any mod that depends on Archie crashes with a
	// NoClassDefFoundError. Remove once that's fixed upstream in Archie.
	modImplementation(libs.clothConfig.fabric)

	// Dev-only: compile-time visibility for GameTest code (gated behind
	// AGameTestPlatform.isGameTest - see common/build.gradle.kts) plus runtime presence for the
	// "gametest"/"gametestClient" runs above. modLocalRuntime never ships in the production jar
	// (it isn't part of the "common"/"shadowCommon" configurations shadowJar draws from), so these
	// two lines are exactly what keeps archie-gametest off the classpath in production while still
	// letting it run in dev.
	modCompileOnly(libs.archie.gametest.common)
	modCompileOnly(libs.archie.gametest.fabric)
	modLocalRuntime(libs.archie.gametest.common)
	modLocalRuntime(libs.archie.gametest.fabric)

	"common"(project(":tubularstorage-common", "namedElements")) { isTransitive = false }
	"shadowCommon"(project(":tubularstorage-common", "transformProductionFabric")) { isTransitive = false }
}

modResources {
	filesMatching.add("fabric.mod.json")
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-fabric")

	processResources {
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
		from(project(":tubularstorage-common").sourceSets.main.get().output)
	}

	sourcesJar {
		val commonSources = project(":tubularstorage-common").tasks.sourcesJar
		dependsOn(commonSources)
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		from(commonSources.get().archiveFile.map { zipTree(it) })
	}
}
