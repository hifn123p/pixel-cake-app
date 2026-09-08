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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 只保留中英文资源，剔除 AndroidX 等依赖带入的多语言字符串（约可省几百 KB）。
    // resourceConfigurations 已在 AGP 8.8 起废弃，8.13 中报 deprecation。
    androidResources {
        localeFilters += listOf("zh-rCN", "en")
    }

    // 签名配置只在 CI 注入了 KEYSTORE_PATH 时创建。
    // 本地或无 Secrets 的 CI 环境回退到 debug 签名，保证 assembleRelease 永不因缺签名而失败。
    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
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

    // 第 2 步引入 LibRaw 后，此处添加 externalNativeBuild { cmake { ... } }
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

    debugImplementation(libs.compose.ui.tooling)
}
