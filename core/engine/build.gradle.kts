plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    // P2D §4.2.2：本模块自带 @AndroidEntryPoint（PiEngineService）与 @Module/@Binds，
    // 所以 hilt 插件 + ksp 必须落在这里，不能只在 :app 声明——:app 的字节码变换
    // 不会替库模块把 @AndroidEntryPoint 的 Hilt 基类生成出来。
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    // Hilt：@AndroidEntryPoint 服务 + @Module 装配（规格书 §4.2.2）。
    // 升 api：下游（:feature:chat / :app）注入点要引用 dagger 注解与 @ApplicationContext。
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    // SessionSupervisorTest 的虚拟时钟（runTest/advanceTimeBy），toml 已有条目（RECON §6）
    testImplementation(libs.kotlinx.coroutines.test)
    // PiEngineServiceTest 重写：@HiltAndroidTest + HiltTestApplication（RECON-P2D §3 配方）
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.turbine)
    kspTest(libs.hilt.compiler)
}
