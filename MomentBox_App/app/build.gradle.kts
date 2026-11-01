plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ntq.training.momentbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.momentbox_app"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures{
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug{
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:3000/\"")
            buildConfigField("String", "DUMMY_JSON_URL", "\"https://dummyjson.com/\"")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:3000/\"")
            buildConfigField("String", "DUMMY_JSON_URL", "\"https://dummyjson.com/\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions{
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX & Giao diện người dùng cốt lõi (UI Core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    // Tương thích ngược Splash Screen API cho Android cũ (< Android 12)
    implementation(libs.androidx.core.splashscreen)

    // Lưu trữ cấu hình bất đồng bộ, an toàn đa luồng thay thế SharedPreferences
    implementation(libs.androidx.datastore.preferences)

    // Quản lý trạng thái và vòng đời kiến trúc MVVM
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Xử lý bất đồng bộ đa luồng với Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Tầng mạng: Retrofit gọi REST API, Gson parse JSON, OkHttp quản lý kết nối HTTP & Interceptor
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Kiểm thử đơn vị trên máy tính cục bộ (Local Unit Tests)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.arch.core.testing)

    // Kiểm thử giao diện trên thiết bị thật / giả lập (Instrumented Tests)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}