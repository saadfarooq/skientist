plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":skientist"))
    implementation(project(":skientist-ksp"))
    ksp(project(":skientist-ksp"))
    implementation(libs.kotlinx.coroutines.core)
}

ksp {
    arg("skientist.version", "0.1.0")
}
