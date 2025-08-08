plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    // id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    // Add the maven-publish plugin
    id("maven-publish")
}

qupathExtension {
    name = "qupath-extension-abba"
    group = "qupath.ext.biop.abba"
    version = "0.4.0-SNAPSHOT"
    description = "QuPath extension to use Aligning Big Brain and Atlases"
    automaticModule = "qupath.ext.biop.abba"
}

dependencies {
    // Main dependencies for most QuPath extensions
    implementation(libs.bundles.qupath)
    implementation(libs.qupath.fxtras)
    implementation("commons-io:commons-io:2.11.0")
    implementation("net.imglib2:imglib2-realtransform:3.1.2")
    implementation("qupath.ext.warpy:qupath-extension-warpy:0.4.2")
}


publishing {
    repositories {
        maven {
            name = "scijava"
            //credentials(PasswordCredentials::class)
            url = if (version.toString().endsWith("SNAPSHOT")) {
                uri("https://maven.scijava.org/content/repositories/snapshots")
            } else {
                uri("https://maven.scijava.org/content/repositories/releases")
            }
            credentials {
                username = System.getenv("MAVEN_USER")
                password = System.getenv("MAVEN_PASS")
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                licenses {
                    license {
                        name = "GNU General Public License, Version 3"
                        url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    }
                }
            }
        }
    }
}