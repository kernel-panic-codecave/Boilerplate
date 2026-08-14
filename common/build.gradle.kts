architectury {
	common("fabric", "neoforge")
}

// See tubularstorage.accesswidener's own header comment for what this unlocks and why.
loom {
	accessWidenerPath = file("src/main/resources/tubularstorage.accesswidener")
}

// Generated blockstate/model JSON (see TubularStorageBlockStateProvider, "gradlew runDatagen")
// lands in its own src/main/generated tree rather than src/main/resources directly - Minecraft's
// datagen CachedOutput treats its whole output directory as exclusively its own and deletes
// anything in it a provider didn't just (re)write, which would otherwise silently destroy the
// hand-placed textures/gametest structures living alongside it in src/main/resources. Added here,
// on the common sourceSet, so both loaders' processResources merges (which already pull from
// project(":tubularstorage-common").sourceSets.main.get().resources) pick it up for free.
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

	// Compile-time only - see the matching comment in fabric/build.gradle.kts. GameTest classes
	// referencing these types must only ever run behind an AGameTestPlatform.isGameTest check, so
	// that in production - where archie-gametest-common is never on the runtime classpath - the
	// JVM never actually resolves them.
	modCompileOnly(libs.archie.gametest.common)

	// Compile-time only, same reasoning as archie-gametest-common above - datagen classes must
	// only ever run behind an ADataGeneratorPlatform.isDataGen check.
	modCompileOnly(libs.archie.datagen.common)
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-common")
}
