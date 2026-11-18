@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidTargetSdkVersion: Int by rootProject.extra
val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra
val managerVersionCode: Int by rootProject.extra
val managerVersionName: String by rootProject.extra

android {
    namespace = "com.kernelai.app"
    compileSdk = androidCompileSdkVersion
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.kernelai.app"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK / CMake 原生构建配置
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
            }
        }

        // 仅构建 arm64-v8a（目标设备为 Android 手机）
        ndk {
            abiFilters += providers.gradleProperty("qingwei.abis").orNull
                ?.split(',')?.filter { it in setOf("arm64-v8a", "x86_64") }
                ?: listOf("arm64-v8a")
        }
    }

    // CMake 构建脚本路径
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val distributionKey = providers.environmentVariable("QINGWEI_KEYSTORE_PATH").orNull
    if (!distributionKey.isNullOrBlank()) {
        signingConfigs.create("distribution") {
            storeFile = file(distributionKey)
            storePassword = providers.environmentVariable("QINGWEI_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("QINGWEI_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("QINGWEI_KEY_PASSWORD").get()
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release {
            if (!distributionKey.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (!distributionKey.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    // Compose BOM (统一版本管理)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Activity Compose
    implementation(libs.androidx.activity.compose)

    // Lifecycle (ViewModel + Runtime + Compose 集成)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Navigation3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigationevent.compose)

    // Kotlinx Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Kotlinx Serialization (MCP JSON 协议)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp + SSE (MCP Streamable HTTP 传输)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.0-alpha03")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Miuix UI 组件库
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)

    // Commonmark (Markdown 渲染)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    // Hidden API Bypass (访问系统隐藏 API)
    implementation(libs.hiddenapibypass)

    // WebKit (WebView 增强)
    implementation(libs.androidx.webkit)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}
