plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "works.earendil.pihost.core.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:environment"))
    // P2C：SessionSupervisor 直接消费 PiSession/PiEvent/RpcCommand（方案 §3.2）。
    api(project(":core:rpc"))
    // core/rpc 虽以 api 传递暴露 core/process，但本模块的公开签名直接引用
    // ProcessStarter/JvmProcessFactory，按「直接使用的依赖要显式声明」写死（RECON §3）。
    api(project(":core:process"))
    // 升级 implementation → api：本模块公开 API 是 suspend 函数与 StateFlow/SharedFlow，
    // 下游（app）不显式声明协程依赖也要能编译。
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    // SessionSupervisorTest 的虚拟时钟（runTest/advanceTimeBy），toml 已有条目（RECON §6）
    testImplementation(libs.kotlinx.coroutines.test)
}
