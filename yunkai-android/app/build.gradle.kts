plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.zhuolin.yunkai"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zhuolin.yunkai"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
buildFeatures { compose = true }
// JVM 单测允许 android.util.Log 等框架调用返回默认值（引擎层 Log.w 不炸测试）
testOptions { unitTests.isReturnDefaultValues = true }

// 门0 P10：Room schema 导出入仓——每个版本的 schema JSON 落 schemas/，迁移历史从作者脑中挪到盘上，
// MigrationTestHelper（后续版本迁移的自动化用例）以此为基
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
}

// run-all 门1 断言需要读到测试 stdout（LLM_CHAIN_OK）
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.pdfbox.android)   // 二期 PDF 文字型抽取（仅 Android；鸿蒙 PDF 挂 backlog）
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
