plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "works.earendil.pihost"
    // P2D §3/§13.7 预授权实证：androidx.activity:1.11.0（activity-compose 传递）硬性要求
    // compileSdk ≥ 36，AGP 8.13 在 :app 依赖校验里直接报错。targetSdk 恒 35 不动。
    compileSdk = 36

    defaultConfig {
        applicationId = "works.earendil.pihost"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        // 方案 §2.3 / §8：只发 arm64-v8a（Termux 运行时仅有 aarch64）
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
    }

    // 方案 §8：libpi_*.so 必须由安装器解压成真实文件（nativeLibraryDir）才能 execve，
    // 即 android:extractNativeLibs=true；P2B 起 app/src/main/jniLibs 带运行时产物。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    // §4.4 四路由全实装：sessions/settings 从 P2D 的占位屏换成真屏，app 因此要多两条 project 边。
    // 全部 implementation（不是 api）：app 只消费各 feature 的 @Composable 入口，
    // 不把 feature 的类型再转露给任何人（app 测试要引用 SessionsTags/SettingsTags 时，
    // implementation 对本模块的 test 源集本来就可见 —— Gradle 的 testImplementation 继承 implementation）。
    implementation(project(":feature:chat"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:settings"))
    implementation(project(":core:engine"))
    implementation(project(":core:environment"))

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

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // @HiltAndroidTest 需要 HiltTestApplication + 测试图（RECON-P2D §3 配方）
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // 只 testImplementation：见 feature/chat/build.gradle.kts 同条注释（APK manifest 体检基线）
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
