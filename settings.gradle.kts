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
// P2 模块拆分进行中（方案 §2）；core/rpc、core/process 保持 Android-free JVM
include(":app")
include(":core:process")
include(":core:rpc")
include(":core:environment")
include(":core:engine")
