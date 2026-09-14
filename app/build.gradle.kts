import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hifn.pixelcake"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hifn.pixelcake"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // 刻意**不声明** testInstrumentationRunner（审计 M8）：本项目没有 androidTest 源集、
        // 也没有 androidx.test 依赖，裸声明是死配置 —— 一旦有人加仪器测试会因缺依赖直接红；
        // 而且仪器测试在本项目的 CI 里根本跑不了（无反无模拟器）。
        // 需要护栏的 UI 逻辑一律抽成**纯函数**走 JVM 单测：预览落点换算
        // `ui/editor/EditorScreen.kt: fitContentRect` → `FitContentRectTest` 就是这条路的样板。
        // 将来真要引入仪器测试，请连同 compose-ui-test-junit4 依赖一起加回来。

        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        ndk {
            // P1b 仅面向一加15(arm64-v8a)；限单一 ABI 缩短 CI 构建并规避 x86 NDK 差异
            abiFilters += "arm64-v8a"
            // F12：固定 NDK 版本，保证 CI 与本地构建可复现（r30 为 2026 LTS，与 CI 安装版本一致）
            ndkVersion = "30.0.16248370"
        }
    }

    // 只保留中英文资源，剔除 AndroidX 等依赖带入的多语言字符串（约可省几百 KB）。
    // resourceConfigurations 已在 AGP 8.8 起废弃，8.13 中报 deprecation。
    androidResources {
        localeFilters += listOf("zh-rCN", "en")
        // P1+：.tflite 模型不参与 APK 压缩，保证可从 assets 直接 mmap（LiteRT 加载要求）
        noCompress += "tflite"
    }

    // 签名配置分两条路：
    // 1) debug —— 固定绑定入库的 app/debug.keystore（PKCS12）。
    //    动机：以前 CI 从不注入 keystore，AGP 会在每台全新 runner 上自动生成 debug 密钥，
    //    于是两次构建的 debug 包「包名相同、签名不同」⇒ 覆盖安装被系统拒绝
    //    （INSTALL_FAILED_UPDATE_INCOMPATIBLE，一加/ColorOS 提示「证书冲突」）。
    //    仓库 .gitignore 已用 `!debug.keystore` 反忽略该文件，故可入库。
    // 2) release —— 仅在 CI 注入 KEYSTORE_PATH 时创建；缺 Secrets 时回退 debug 签名，
    //    保证 assembleRelease 永不因缺签名而失败。
    signingConfigs {
        val bundledDebugKeystore = file("debug.keystore")
        if (bundledDebugKeystore.exists()) {
            maybeCreate("debug").apply {
                storeFile = bundledDebugKeystore
                // Android 官方 debug 密钥约定口令，非机密；入库只为让签名跨构建稳定
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                // 该密钥库由 openssl 导出为 PKCS12，必须显式声明，
                // 否则默认按 .keystore 后缀当 JKS 解析而报「密钥库格式错误」
                storeType = "PKCS12"
            }
        }

        val keystorePath = System.getenv("KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                // 同上：CI 从 Secrets 解出的是 PKCS12
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// kotlinOptions 在 KGP 2.2 已废弃，改用 compilerOptions DSL
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // UI 改版：动效（AnimatedContent / AnimatedVisibility / SharedTransitionLayout + spring）
    implementation(libs.compose.animation)

    debugImplementation(libs.compose.ui.tooling)

    // P1+：端侧推理（LiteRT / CompiledModel）。模型文件见 app/src/main/assets/models/
    implementation(libs.litert)

    // M0b：纯 Kotlin ARW 预览解析的 JVM 单测
    testImplementation("junit:junit:4.13.2")
}
