plugins {
    java
}

group = "dev.hardcorereset"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

tasks.withType<JavaCompile> {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("HardcoreReset.jar")
}
