import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "me.kys.sakhr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    macosArm64()
    linuxArm64()
    linuxX64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            executable {
                entryPoint = "me.kys.sakhr.lang.main"
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
        }
    }
}
