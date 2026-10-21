import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release 签名：与 HyperMusicCover 同一把密钥，本地从 keystore.properties（git 忽略）读取。
 * 没有该文件时回退到 debug 密钥——能装，但不是用户升级时认的那个身份。
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    // 按 UTF-8 读：路径里有「。」，Properties 默认的 ISO-8859-1 会读成乱码
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val releaseStorePath: String? = keystoreProperties.getProperty("storeFile")

android {
    namespace = "com.github.zyl6932.vcutlower"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.zyl6932.vcutlower"
        minSdk = 35
        targetSdk = 37
        versionCode = 19000
        versionName = "1.9"
    }

    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // material-icons-extended 有几十 MB 生成代码，只用到几个图标，不开 R8 体积会很大
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    androidResources {
        localeFilters += listOf("zh")
    }

    packaging {
        resources.excludes += listOf(
            "META-INF/**/LICENSE*",
            "META-INF/*.version",
            "META-INF/*.kotlin_module",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 关于页显示 BuildConfig.VERSION_NAME（与 HyperMusicCover 相同）
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material.icons.extended)

    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
}
