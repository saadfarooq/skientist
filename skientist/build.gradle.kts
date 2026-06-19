plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
}

java { withSourcesJar() }

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
}

publishing {
    repositories {
        maven {
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
    publications {
        create<MavenPublication>("release") {
            groupId = project.property("GROUP") as String
            artifactId = "skientist"
            version = project.property("VERSION_NAME") as String
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("Skientist")
                description.set("A Kotlin library for carefully refactoring critical paths")
                url.set("https://github.com/saadfarooq/skientist")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("saadfarooq")
                        name.set("Saad Farooq")
                    }
                }
                scm {
                    url.set("https://github.com/saadfarooq/skientist")
                    connection.set("scm:git:github.com/saadfarooq/skientist.git")
                    developerConnection.set("scm:git:ssh://github.com/saadfarooq/skientist.git")
                }
            }
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["release"])
}
