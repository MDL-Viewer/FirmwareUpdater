plugins {
    kotlin("jvm")
    id("application")
    id("maven-publish")
    id("com.github.jmongard.git-semver-plugin")
}

repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/MDL-Viewer/FirmwareUpdater")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("de.treichels.hott:hott-decoder:_")
    implementation("de.treichels.hott:hott-model:_")
    implementation("de.treichels.hott:hott-ui:_")
    implementation("de.treichels.hott:hott-serial:_")
    runtimeOnly("de.treichels.hott:jserialcommport:_")
}

semver {
    releaseTagNameFormat = "v%s"
}

version = semver.version

application {
    mainClass.set("de.treichels.hott.update.FirmwareUpdaterKt")
}

tasks {
    jar {
        manifest {
            attributes (
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Main-Class" to application.mainClass
            )
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MDL-Viewer/FirmwareUpdater")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("github") {
            artifactId = project.name.lowercase()
            group = "de.treichels.hott"
            from(components["java"])
        }          
    }
}
