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
		// "gradlew runGametest"/"runGametestClient" - see the dependency comment below for why
		// archie-gametest is safe to have on this run's classpath despite never shipping.
		create("gametest") {
			server()
			name = "Minecraft GameTest"
			property("neoforge.enableGameTest", "true")
			property("neoforge.gameTestServer", "true")
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
	}
}

dependencies {
	neoForge(libs.neoforge)
	modApi(libs.architectury.neoforge)
	implementation(libs.kotlin.neoforge)
	modApi(libs.archie.neoforge)

	// Workaround, not a real Tubular Storage dependency - see the matching comment in
	// fabric/build.gradle.kts: Archie's own config init requires cloth-config's ModifierKeyCode
	// class present at runtime regardless of consumer.
	modImplementation(libs.clothConfig.neoforge)

	// NeoForge's FML classloader layering means a plain implementation/modApi dependency on a
	// Kotlin-ecosystem library isn't reliably visible at the right point in the mod-bus lifecycle
	// (forgeRuntimeLibrary alone lands things on MC-BOOTSTRAP, invisible to KotlinLangForge's
	// stdlib on PLUGIN). import net.kernelpanicsoft.archie.plugin.runtimeLibrary (or
	// .bundleRuntimeLibrary if it also needs to ship in our jar) and use that instead of a plain
	// implementation/modImplementation for any future Kotlin-ecosystem dependency added here - it
	// routes the dependency through the archie-plugin's PatchFMLModType artifact transform.
	// Fabric doesn't have this problem.

	// Dev-only: compile-time visibility for GameTest code (gated behind
	// AGameTestPlatform.isGameTest - see common/build.gradle.kts) plus runtime presence for the
	// "gametest"/"gametestClient" runs above. modLocalRuntime never ships in the production jar
	// (it isn't part of the "common"/"shadowCommon" configurations shadowJar draws from), so these
	// two lines are exactly what keeps archie-gametest off the classpath in production while still
	// letting it run in dev.
	modCompileOnly(libs.archie.gametest.common)
	modCompileOnly(libs.archie.gametest.neoforge)
	modLocalRuntime(libs.archie.gametest.common)
	modLocalRuntime(libs.archie.gametest.neoforge)

	"common"(project(":tubularstorage-common", "namedElements")) { isTransitive = false }
	"shadowCommon"(project(":tubularstorage-common", "transformProductionNeoForge")) { isTransitive = false }
}

modResources {
	filesMatching.add("META-INF/neoforge.mods.toml")
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-neoforge")

	processResources {
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
