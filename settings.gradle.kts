pluginManagement {
    repositories {
        // plugins.gradle.org 不可达：Gradle 插件与 JVM 依赖统一走 Maven Central
        mavenCentral()
        // P2 Android 起用（dl.google.com 实测可达）
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "pi-android"
// P1 POC：Android-free JVM 库（P2 拆分见方案 §2）
include(":core:process")
include(":core:rpc")
