plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

allprojects {
    group = "cn.orzmc.realtime"
    version = providers.gradleProperty("projectVersion").orElse("0.1.0-SNAPSHOT").get()

    repositories {
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.5.0")
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.5.0")
        }
    }
}
