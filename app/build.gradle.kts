plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "works.earendil.pihost"
    compileSdk = 35

    defaultConfig {
        applicationId = "works.earendil.pihost"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 方案 §2.3 / §8：只发 arm64-v8a（Termux 运行时仅有 aarch64）
        ndk { abiFilters += "arm64-v8a" }
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
    implementation(project(":core:engine"))
    implementation(project(":core:environment"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
