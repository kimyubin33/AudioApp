import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Google 웹 클라이언트 ID (공개돼도 되는 식별자). 저장소에 넣지 않고 ~/.gradle/gradle.properties 의
// googleWebClientId=... 로 둔다. 없으면 빈 값이고, 앱은 "로그인 설정이 안 됨"으로 안내한다.
val googleWebClientId = providers.gradleProperty("googleWebClientId").orElse("").get()

// debug에서 개발용 로그인 대신 실제 Google 로그인을 시험하려면 gradle.properties에 realLogin=true
val realLogin = providers.gradleProperty("realLogin").orElse("false").get()
val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl").orElse("https://api.example.invalid").get()
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

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
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", javaString(googleWebClientId))
    }

    buildTypes {
        debug {
            // 개발용 목업 서버 (dev-server/mock_server.py). 에뮬레이터에서 PC는 10.0.2.2 로 보인다.
            buildConfigField("String", "API_BASE_URL", "\"https://10.0.2.2:8443\"")
            buildConfigField("boolean", "DEV_LOGIN", (realLogin != "true").toString())
        }
        release {
            // ~/.gradle/gradle.properties 또는 CI의 -P 인자로 운영 주소를 공급한다.
            buildConfigField("String", "API_BASE_URL", javaString(releaseApiBaseUrl))
            buildConfigField("boolean", "DEV_LOGIN", "false")
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

val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    group = "verification"
    description = "Reject missing or development API/OAuth settings before a release build."
    inputs.property("apiBaseUrl", releaseApiBaseUrl)
    inputs.property("googleClientId", googleWebClientId)
    doLast {
        val uri = runCatching { URI(inputs.properties["apiBaseUrl"].toString()) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        check(uri?.scheme == "https" && host.contains('.') &&
            !host.endsWith(".invalid") && !host.endsWith(".test") &&
            host != "example.com" && !host.endsWith(".example.com") &&
            !host.matches(Regex("[0-9.]+")) && !host.contains(':') &&
            !host.endsWith(".localhost") && !host.endsWith(".local") &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "Set releaseApiBaseUrl to the deployed HTTPS API origin. Development/placeholder URLs cannot be released."
        }
        check(Regex("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com").matches(inputs.properties["googleClientId"].toString())) {
            "Set googleWebClientId to the production Google OAuth web client ID."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseConfiguration) }

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.credentials.core)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui.compose)
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