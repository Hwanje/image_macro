plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.imagemacro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.imagemacro"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
        vectorDrawables { useSupportLibrary = true }
    }

    // 정식(릴리스) 서명. 디버그 키로 서명하면 Play Protect가 "개발자 미확인" 경고를
    // 띄우므로, 저장소에 포함된 고정 키스토어로 서명해 배포한다.
    // 이 키는 인앱 자동 업데이트의 서명 일치를 위해 항상 동일하게 유지해야 한다.
    signingConfigs {
        create("release") {
            storeFile = file("imagemacro-release.keystore")
            storePassword = "imagemacro2024"
            keyAlias = "imagemacro"
            keyPassword = "imagemacro2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 디버그 빌드도 같은 릴리스 키로 서명해 두 산출물의 서명을 일치시킨다.
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
}
