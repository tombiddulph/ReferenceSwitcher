import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.tombiddulph.referenceswitcher"
version = "0.1.6"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2025.1.7") {
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
              <li>Support Rider 2025.1 and newer.</li>
              <li>Move local-project discovery out of Rider startup and into cancellable background tasks.</li>
              <li>Cache unchanged projects and inherited build properties for faster refreshes.</li>
              <li>Avoid duplicate source-root scans and parse each project only once during discovery.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "251"
        }
        vendor {
            name = "Tom Biddulph"
            email = "tombiddulph@gmail.com"
            url = "https://github.com/tombiddulph"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.Rider, "2025.1.7") { useInstaller = false }
            create(IntelliJPlatformType.Rider, "2025.2.6.1") { useInstaller = false }
            create(IntelliJPlatformType.Rider, "2025.3.5") { useInstaller = false }
            create(IntelliJPlatformType.Rider, "2026.1.5") { useInstaller = false }
            create(IntelliJPlatformType.Rider, "2026.2.0.2") { useInstaller = false }
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
