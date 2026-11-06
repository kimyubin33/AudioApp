plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kimyubin33.audioapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kimyubin33.audioapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 개발용 목업 서버 (dev-server/mock_server.py). 에뮬레이터에서 PC는 10.0.2.2 로 보인다.
            buildConfigField("String", "API_BASE_URL", "\"https://10.0.2.2:8443\"")
        }
        release {
            // 실제 서버가 생기기 전까지는 연결될 수 없는 예약 주소(.invalid)를 둔다.
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.invalid\"")
            // R8 축소·최적화. 템플릿의 packageScope(라이브러리 패키지만 처리)는 실행 시작 중
            // IllegalAccessError(kotlin.collections.* 접근 불가)를 일으켜 빼고, 앱 전체를 일관되게 처리한다.
            // UniFFI·JNA처럼 이름으로 접근하는 코드는 src/main/keepRules/rules.keep 에서 지키는 규칙을 둔다.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}