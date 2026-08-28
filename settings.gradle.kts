enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Boilerplate"

pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/")
		maven("https://maven.architectury.dev/")
		maven("https://maven.neoforged.net/releases/")
		maven("https://maven.firstdarkdev.xyz/releases")
		maven {
			name = "kernelpanic releases"
			url = uri("https://maven.kernelpanicsoft.net/releases")
		}
		maven {
			name = "kernelpanic snapshots"
			url = uri("https://maven.kernelpanicsoft.net/snapshots")
		}
		mavenLocal()
		gradlePluginPortal()
	}
}

include("common")
include("fabric")
include("neoforge")

project(":common").name = "boilerplate-common"
project(":fabric").name = "boilerplate-fabric"
project(":neoforge").name = "boilerplate-neoforge"
