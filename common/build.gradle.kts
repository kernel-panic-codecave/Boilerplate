architectury {
	common("fabric", "neoforge")
}

// See boilerplate.accesswidener's own header comment for what this unlocks and why.
loom {
	accessWidenerPath = file("src/main/resources/boilerplate.accesswidener")

	// Without this, `test` has no mod* configurations of its own (Loom only wires them up for
	// source sets it's told about) - modTestImplementation below would otherwise resolve
	// archie-gametest-common's raw, un-remapped (intermediary-named) published jar instead of one
	// remapped to match this project's own mappings, which crashed the JUnit bridge at runtime
	// with a NoClassDefFoundError for a class_NNNN-named Minecraft class the moment it tried to
	// reflect over @GameTest-annotated methods.
	createRemapConfigurations(sourceSets.test.get())
}

// Generated blockstate/model JSON (see BoilerplateBlockStateProvider, "gradlew runDatagen")
// lands in its own src/main/generated tree rather than src/main/resources directly - Minecraft's
// datagen CachedOutput treats its whole output directory as exclusively its own and deletes
// anything in it a provider didn't just (re)write, which would otherwise silently destroy the
// hand-placed textures/gametest structures living alongside it in src/main/resources. Added here,
// on the common sourceSet, so both loaders' processResources merges (which already pull from
// project(":boilerplate-common").sourceSets.main.get().resources) pick it up for free.
sourceSets {
	main {
		resources {
			srcDir("src/main/generated")
		}
	}
}

dependencies {
	compileOnly(kotlin("reflect"))

	modApi(libs.architectury.common)
	modApi(libs.archie.common)
	modApi(libs.flywheel.common)

	modCompileOnly(libs.archie.gametest.common)
	modCompileOnly(libs.archie.datagen.common)

	"modTestImplementation"(libs.archie.gametest.common)
	testImplementation(libs.junit.jupiter.api)
	testImplementation(kotlin("reflect"))
	testRuntimeOnly(libs.junit.jupiter.engine)
	testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-common")

	test {
		useJUnitPlatform()
		systemProperty("archie.junit.gametest", System.getProperty("archie.junit.gametest") ?: "true")
		systemProperty(
			"archie.junit.gametest.matrix",
			System.getProperty("archie.junit.gametest.matrix") ?: "fabric:server,neoforge:server",
		)
		systemProperty("archie.junit.gametest.timeoutMinutes", System.getProperty("archie.junit.gametest.timeoutMinutes") ?: "20")
		systemProperty("archie.junit.gametest.root", rootProject.rootDir.absolutePath)
	}
}
