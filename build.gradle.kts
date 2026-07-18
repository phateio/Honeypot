plugins {
    `java-library`
}

group = "io.github.phateio"
version = "2.0.0"

java {
    // Paper 26.2 (and thus its paper-api artifact) requires Java 25.
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pinned to the running server's build. Only the stable Bukkit/Paper surface
    // is used (events, commands, bans, Adventure/MiniMessage via transitive deps).
    compileOnly("io.papermc.paper:paper-api:26.2.build.40-alpha")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Lint everything except the noisy [classfile] warnings from the paper-api
    // jar's compile-only JetBrains annotations, which are not on our classpath.
    options.compilerArgs.add("-Xlint:all,-classfile")
}

tasks.processResources {
    // Mirrors Maven-style ${name} / ${version} filtering in plugin.yml.
    val props = mapOf(
        "name" to project.name,
        "version" to project.version,
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
