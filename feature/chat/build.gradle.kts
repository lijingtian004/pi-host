plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "works.earendil.pihost.feature.chat"
    // 与 :app 一致（规格书 §4.1.2）；36 的实证理由见 app/build.gradle.kts 注释
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
            // Robolectric 读合并 manifest（ui-test-manifest 注册的 ComponentActivity 从这里来）
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
    // api：ChatViewModel 的公开签名直接引用 core/engine 的 ChatEngine/RuntimeInstall/SessionState
    api(project(":core:engine"))

    // BOM 用 api(platform(...))：版本约束要能继续传给 :app
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

    // P2E 规格书 §3.3：markdown 渲染 = markwon core + syntax-highlight(prism4j)。
    // commonmark 0.13.0 是 core 的传递依赖（RECON-P2E §1 实测 pom），不是本模块新增的声明。
    implementation(libs.markwon.core)
    implementation(libs.markwon.syntax.highlight)
    // prism4j 显式声明：语法表要自己实现 GrammarLocator（prism4j 2.0.0 里**没有任何内置语法**，
    // 见 RECON-P2E §1.3）。
    // annotations-java5 的重复类问题在**根 build.gradle.kts** 上全仓排除才生效
    // （库模块的 exclude 不进消费者依赖图，第一轮 :app:assembleDebug 就是挂在这里，
    //  原文报错与推理见 RECON-P2E §1.2）；这里保留一条局部 exclude 只做「本模块自解析
    //  也拿不到它」的双保险，不承担 :app 侧的修复责任。
    implementation(libs.prism4j) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    // 测试栈（规格书 §7）：Robolectric + JUnit4 + coroutines-test + turbine + Compose UI test
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // RECON-P2D §3 配方：@HiltAndroidTest 走 HiltTestRunner + kspTest 生成测试组件
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    // Compose 测试宿主 Activity 只进**单测合并 manifest**（RECON-P2D §6：放 debugImplementation
    // 会把 androidx.activity.ComponentActivity 漏进 debug APK 的 AndroidManifest，
    // 改变 §8.6 的 aapt2 体检基线——所以只 testImplementation）
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
