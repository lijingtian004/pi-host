plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // P2D：Compose 编译器插件 + KSP（Hilt 代码生成）+ Hilt，全部 apply false 由各模块启用
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ── P2E 冒烟（RECON-P2E §1.2）：prism4j 的 annotations-java5 全局排除 ──────────────
// `io.noties:prism4j:2.0.0` 的 pom 带 `org.jetbrains:annotations-java5:17.0.0`，与
// kotlin-stdlib 2.2.20 拖入的 `org.jetbrains:annotations:23.0.0` 有 30+ 个同名 class
// （org.jetbrains.annotations.NotNull / org.intellij.lang.annotations.* …），
// AGP 8.13 的 `:app:checkDebugDuplicateClasses` 因此直接判失败（实测原文见 RECON-P2E §1.2）。
//
// **为什么必须写在这里、而不是只写在 :feature:chat 的依赖块上**（实测踩点）：
// 库模块 `implementation(...){ exclude(...) }` 的排除规则**不会**进入它暴露给消费者的依赖图——
// `:app` 解析 `debugRuntimeClasspath` 时重新走一遍图，annotations-java5 照样被 prism4j 拖进来。
// 第一轮冒烟就是这么挂的：`:feature:chat:assembleDebug` 侧干净，`:app:assembleDebug` 报重复类。
// 全仓每条 configuration 排除 = 一次性关掉这个面；被排除的是**纯编译期注解 jar**，
// 同一批 FQN 由 annotations 23.0.0 提供，运行期语义不变（§2 白名单未新增条目）。
allprojects {
    configurations.configureEach {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
}
