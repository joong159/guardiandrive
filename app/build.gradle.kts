import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 클라이언트 ID·시크릿은 코드에 쓰지 않고 local.properties에서 읽음 (local.properties는 Git에 올라가지 않음)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(key: String) = "\"${localProps.getProperty(key, "")}\""

android {
    namespace = "ai.guardiandrive.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "ai.guardiandrive.app"
        minSdk = 28            // Pleos 요구사항: API 28 이상 (차량 사이버 보안 요건)
        targetSdk = 34         // Pleos Connect 에뮬레이터 이미지 API 34
        versionCode = 1
        versionName = "0.1-contest"
        buildConfigField("String", "PLEOS_CLIENT_ID", prop("pleos.clientId"))
        buildConfigField("String", "PLEOS_CLIENT_SECRET", prop("pleos.clientSecret"))
    }
    // 제출용 release 서명: 키 경로·비밀번호는 local.properties에서 읽음 (Git에 올라가지 않음)
    signingConfigs {
        create("release") {
            localProps.getProperty("release.storeFile")?.let { storeFile = file(it) }
            storePassword = localProps.getProperty("release.storePassword")
            keyAlias = localProps.getProperty("release.keyAlias")
            keyPassword = localProps.getProperty("release.keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // 제출 APK는 저장소에 올라가므로 클라이언트 ID·시크릿을 넣지 않음 (현재 코드에서 사용하지 않음)
            buildConfigField("String", "PLEOS_CLIENT_ID", "\"\"")
            buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"\"")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    // 속도·비상등을 안드로이드 표준 차량 API(CarPropertyManager)로 읽기 위해 사용
    useLibrary("android.car")
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Pleos Connect SDK — 프로젝트에서 '사용 가능'인 것
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // TODO: NaviHelper 좌표는 Playground 문서(NaviHelper › Project setup)에서 확인 후 추가
    // implementation("ai.pleos.playground:NaviHelper:<버전>")

    // '준비 중' — 열리면 추가 (그전엔 안드로이드 기본 기능 + 화면 버튼으로 동작)
    // implementation("ai.pleos.playground:TextToSpeech:<버전>")
    // implementation("ai.pleos.playground:SpeechToText:<버전>")
    // implementation("ai.pleos.playground:FusedLocation:<버전>")
}
