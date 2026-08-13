architectury {
	common("fabric", "neoforge")
}

dependencies {
	compileOnly(kotlin("reflect"))

	modApi(libs.architectury.common)
	modApi(libs.archie.common)
}

tasks {
	base.archivesName.set(base.archivesName.get() + "-common")
}
