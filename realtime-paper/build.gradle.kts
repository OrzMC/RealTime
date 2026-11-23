import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    id("io.papermc.hangar-publish-plugin")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "placeholderapi"
        url = uri("https://repo.extendedclip.com/releases/")
    }
}

dependencies {
    implementation(project(":realtime-core"))
    implementation(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("me.clip:placeholderapi:2.12.3")
    testImplementation(platform("org.junit:junit-bom:5.14.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation("me.clip:placeholderapi:2.12.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.jar {
    archiveBaseName.set("RealTime")
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("RealTime")
    archiveClassifier.set("")
    mergeServiceFiles()
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString())
        channel.set(if (project.version.toString().contains('-')) "Snapshot" else "Release")
        id.set(providers.gradleProperty("hangarProjectId"))
        apiKey.set(providers.environmentVariable("HANGAR_API_TOKEN"))

        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.shadowJar.flatMap { it.archiveFile })
                platformVersions.set(
                    providers
                        .gradleProperty("hangarPaperVersions")
                        .map { it.split(",").map(String::trim) }
                        .orElse(listOf("26.3")),
                )
            }
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
