plugins { application }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

repositories { mavenCentral(); mavenLocal() }

dependencies {
    implementation("org.freedesktop.wayland:wayland-lite")
}

application {
    mainClass.set("demo.Demo")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
