plugins { application }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

dependencies {
    // Everything Wayland comes from the vendored library.
    implementation("org.freedesktop.wayland:wayland-lite")
}

application {
    mainClass.set("wcm.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The isolation tool used to pin the poll()/upcall bugs down.
tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("wcm.Probe")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args = ((findProperty("probeArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() }
}
