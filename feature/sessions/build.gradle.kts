plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "works.earendil.pihost.feature.sessions"
    // 与 :feature:chat / :app 对齐（P2D 起定案：36 的实证理由见 app/build.gradle.kts 注释）
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // SessionScannerTest 用 TemporaryFolder 造真实 jsonl；Robolectric 的
            // ComponentActivity 宿主来自 ui-test-manifest 合并进单测 manifest 的声明
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
    // api：SessionsViewModel 的公开签名引用 core/engine 的 ChatEngine；
    // kotlinx-serialization-json 经 core:engine → core:rpc 的 **api** 边传递到达
    // （SessionScanner 逐行 parseToJsonElement 不需要本模块再声明一次）。
    api(project(":core:engine"))

    api(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 测试栈与 :feature:chat 逐条同构（RECON-P2D §3 配方）
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
