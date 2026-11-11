import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp")
}

val momentBoxDebugBaseUrl = providers.gradleProperty("momentboxBaseUrl")
    .orElse("http://10.0.2.2:3000/").map { value ->
        URI(value).also { uri ->
            require(
                uri.scheme in listOf("http", "https") && uri.host != null &&
                        uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.endsWith(
                    "/"
                )
            ) {
                "momentboxBaseUrl must be an HTTP(S) base URL ending in / without credentials, query or fragment"
            }
        }.toASCIIString()
    }.get()

android {
    namespace = "com.ntq.training.momentbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ntq.training.momentbox"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"$momentBoxDebugBaseUrl\"")
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX & Giao diện người dùng cốt lõi (UI Core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
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
    implementation(libs.androidx.lifecycle.process)

    // Xử lý bất đồng bộ đa luồng với Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Tầng mạng: Retrofit gọi REST API, Gson parse JSON, OkHttp quản lý kết nối HTTP & Interceptor
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.2.10")

    // S11/S12: CameraX + EXIF
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.exifinterface)

    // M2: Bảng tin, media, realtime và lưu trữ cục bộ
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.socket.io.client)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Kiểm thử đơn vị trên máy tính cục bộ (Local Unit Tests)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.arch.core.testing)

    // Kiểm thử giao diện trên thiết bị thật / giả lập (Instrumented Tests)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
val parcelizeCompiler = configurations.create("momentboxParcelizeCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    add(
        parcelizeCompiler.name,
        "org.jetbrains.kotlin:kotlin-parcelize-compiler:2.2.10"
    )
}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add(parcelizeCompiler.elements.map { files -> "-Xplugin=" + files.single().asFile.absolutePath })
}