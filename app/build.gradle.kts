plugins {
    alias(libs.plugins.android.application)
}

val gitBuildStamp: String = run {
    val hash = runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrDefault("nogit")
    hash.ifBlank { "nogit" }
}

android {
    namespace = "com.yumito.yumyhook"
    ndkVersion = "27.0.12077973"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.yumito.yumyhook"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BUILD_STAMP", "\"$gitBuildStamp\"")
        buildConfigField("String", "LINEAGE_FINGERPRINT", "\"YH-LIN-8d4e2f91-yumito\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            optimization {
                enable = false
            }
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        dataBinding = true
        prefab = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.shadowhook)

    compileOnly(libs.xposed.api)
}
