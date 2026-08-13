architectury {
	common("fabric", "neoforge")
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
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-common")
}
