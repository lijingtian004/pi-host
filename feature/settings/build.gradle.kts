plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "works.earendil.pihost.feature.settings"
    // 与 :feature:chat / :feature:sessions / :app 对齐
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
    // api：SettingsViewModel 的公开签名同时引用 core/engine 的 ChatEngine（get_state 探针）
    // 与 SettingsRepository/AppSettings/MemTier（DataStore 三键的实现与模型都在 core 侧，
    // 规格书 §4.2 + RECON-P2E §7.6：envFor 要同步读 memTier，故 repository 不能落 feature）。
    // androidx-datastore-preferences 经 core:engine 的 api 边传递到达，本模块不重复声明。
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
