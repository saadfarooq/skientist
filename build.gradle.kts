plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jreleaser)
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

jreleaser {
    project {
        description.set("A Kotlin library for carefully refactoring critical paths")
        copyright.set("2026 Saad Farooq")
    }
    signing {
        active.set(org.jreleaser.model.Active.NEVER)
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(org.jreleaser.model.Active.ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    sign.set(false)
                    username.set(providers.gradleProperty("mavencentralusername").orNull)
                    password.set(providers.gradleProperty("mavencentralpassword").orNull)
                    stagingRepositories.add("skientist/build/staging-deploy")
                    stagingRepositories.add("skientist-ksp/build/staging-deploy")
                }
            }
        }
    }
}
