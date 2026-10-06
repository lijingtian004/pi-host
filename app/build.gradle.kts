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
        versionCode = 1
        versionName = "0.1.0"
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
    implementation(project(":feature:chat"))
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
