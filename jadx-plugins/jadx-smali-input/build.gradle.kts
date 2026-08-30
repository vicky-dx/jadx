plugins {
	id("jadx-library")
}

dependencies {
	api(project(":jadx-core"))

	implementation(project(":jadx-plugins:jadx-dex-input"))

	api(libs.smali) {
		exclude(group = "com.beust", module = "jcommander") // exclude old jcommander namespace
	}
	implementation(libs.guava.jre) // force the latest version for smali
}
