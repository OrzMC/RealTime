plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(project(":realtime-core"))
    implementation(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation(platform("org.junit:junit-bom:5.14.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.papermc.paper:paper-api:26.3.build.+")
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

tasks.build {
    dependsOn(tasks.shadowJar)
}
