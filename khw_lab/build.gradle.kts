// 根构建脚本:只声明插件版本,不 apply。
// 注:AGP 9 内置 Kotlin,不再使用 org.jetbrains.kotlin.android 插件。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}
