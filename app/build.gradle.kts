plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.sbsconverter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sbsconverter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }

    aaptOptions {
        noCompress += listOf("onnx")
    }
}

val downloadModel by tasks.registering(Exec::class) {
    val modelFile = file("src/main/assets/model_fp16.onnx")
    outputs.file(modelFile)
    onlyIf { !modelFile.exists() }
    doFirst {
        modelFile.parentFile.mkdirs()
    }
    commandLine(
        "curl", "--retry", "3", "-L", "-C", "-", "-o", modelFile.absolutePath,
        "https://huggingface.co/onnx-community/depth-anything-v2-base/resolve/main/onnx/model_fp16.onnx"
    )
}

tasks.named("preBuild") {
    dependsOn(downloadModel)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
