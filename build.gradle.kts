import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "1.8.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_16
    targetCompatibility = JavaVersion.VERSION_16
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_16.toString()
    }
}

repositories {
    mavenCentral()
    maven(url = "https://www.jitpack.io")
}

dependencies {
    val exposedVersion = "0.41.1"
    val mindustryVersion = "v142"
    val arcVersion = "v142"

    compileOnly("com.github.anuken.arc:arc-core:$arcVersion")
    compileOnly("com.github.anuken.mindustryjitpack:core:$mindustryVersion")

    implementation("com.github.PersonTheCat:hjson-java:3.0.0-C11")
    implementation("de.svenkubiak:jBCrypt:0.4.3")
    implementation("com.mewna:catnip:3.3.5")
    //implementation("com.github.gimlet2:kottpd:0.2.1")
    implementation("org.apache.maven:maven-artifact:4.0.0-alpha-3")

    implementation("com.h2database:h2:2.1.214")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    implementation(files("libs/lingua.jar"))
    implementation("org.slf4j:slf4j-nop:2.0.6")

    testImplementation("com.github.anuken.arc:arc-core:$arcVersion")
    testImplementation("com.github.anuken.mindustryjitpack:core:$mindustryVersion")
    testImplementation("com.github.anuken.mindustryjitpack:server:$mindustryVersion")
    testImplementation("com.github.anuken.arc:backend-headless:$arcVersion")
    testImplementation("com.github.stefanbirkner:system-rules:1.19.0")
    testImplementation("net.datafaker:datafaker:1.7.0")

    val ktor_version = "2.2.3"
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-jackson:$ktor_version")
}

tasks.jar {
    if (!file("./src/main/resources/www").exists()) {
        dependsOn("web")
    }

    archiveFileName.set("Essentials.jar")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }){
        exclude("**/META-INF/*.SF")
        exclude("**/META-INF/*.DSA")
        exclude("**/META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.register("web") {
    if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
        exec {
            workingDir("./src/www")
            commandLine("npm.cmd", "run", "build")
        }
    } else { /* if os is unix-like */
        exec {
            workingDir("./src/www")
            commandLine("npm", "run", "build")
        }
    }
    project.delete(
        files("./src/main/resources/www")
    )
    copy {
        from("src/www/dist")
        into("src/main/resources/www")
    }
}

tasks.compileKotlin {
    kotlinOptions.allWarningsAsErrors = false
}

sourceSets{
    test{
        resources{
            srcDir("src/main/resources")
        }
    }
}