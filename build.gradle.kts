plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.tombiddulph.referenceswitcher"
version = "0.1.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2026.1.5") {
            useInstaller = false
        }
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.tombiddulph.referenceswitcher"
        name = "Reference Switcher"
        version = project.version.toString()
        description = """
            Switch a .NET PackageReference to a local ProjectReference from Rider,
            then restore the exact original package reference when local development is complete.
        """.trimIndent()
        changeNotes = """
            <ul>
              <li>Initial Marketplace release.</li>
              <li>Switch and restore package references without reformatting the project file.</li>
              <li>Discover local projects from configurable source roots.</li>
              <li>Support normal and analyzer project references.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
        vendor {
            name = "Tom Biddulph"
            email = "tombiddulph@gmail.com"
            url = "https://github.com/tombiddulph"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
