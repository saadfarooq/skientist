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
    implementation(project(":skientist"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.compile.testing)
    testImplementation(libs.kotlinx.coroutines.test)
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
            artifactId = "skientist-ksp"
            version = project.property("VERSION_NAME") as String
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("Skientist KSP")
                description.set("KSP processor for generating Skientist experiment proxies from annotated interfaces")
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
