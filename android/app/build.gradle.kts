import java.io.FileInputStream
import java.util.Properties

plugins { id("com.android.application") }

/**
 * 签名信息从两处读，先看 android/keystore.properties（本地开发，已被 .gitignore 忽略），
 * 再看环境变量（CI 用）。都没有就只出 debug 包，release 保持未签名——
 * 这样「clone 下来直接构建」不会因为缺 keystore 而失败。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun signingValue(propName: String, envName: String): String? =
    (keystoreProps.getProperty(propName) ?: System.getenv(envName))?.takeIf { it.isNotBlank() }

val storeFileResolved = signingValue("storeFile", "KEYSTORE_FILE")?.let { rootProject.file(it) }
val hasSigning = storeFileResolved?.exists() == true

android {
    namespace = "com.cuoti.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cuoti.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    // FileProvider：拍照结果要通过 content:// 交给 WebView，不能再用 file://
    implementation("androidx.core:core:1.13.1")
}
