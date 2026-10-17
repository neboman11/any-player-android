import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val sherpaOnnxVersion = "1.13.7"
val sherpaOnnxAarSha256 = "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
val sherpaOnnxAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
val sherpaOnnxAarFile = project.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

android {
    namespace = "com.anyplayer.android.dj"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":playback"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // On-device LLM inference for AI DJ breaks: MediaPipe runs `.task` models, LiteRT-LM runs
    // `.litertlm` ones.
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.litertlm.android)

    // Offline Piper/Kokoro TTS via sherpa-onnx. No Maven artifact exists; k2-fsa publish a
    // prebuilt AAR (all four ABIs) as a GitHub release asset, fetched by downloadSherpaOnnxAar
    // into libs/ and resolved through the flatDir repository declared in settings.gradle.kts.
    implementation(mapOf("name" to "sherpa-onnx", "version" to sherpaOnnxVersion, "ext" to "aar"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Fetched once and cached in libs/ (gitignored) rather than committed.
val downloadSherpaOnnxAar = tasks.register("downloadSherpaOnnxAar") {
    // Without these, editing the pinned URL/hash is ignored once the AAR exists on disk.
    inputs.property("sherpaOnnxAarUrl", sherpaOnnxAarUrl)
    inputs.property("sherpaOnnxAarSha256", sherpaOnnxAarSha256)
    outputs.file(sherpaOnnxAarFile)
    doLast {
        fun download() {
            sherpaOnnxAarFile.parentFile.mkdirs()
            logger.lifecycle("Downloading sherpa-onnx AAR from $sherpaOnnxAarUrl")
            URI(sherpaOnnxAarUrl).toURL().openStream().use { input ->
                sherpaOnnxAarFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        fun sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(sherpaOnnxAarFile.readBytes())
            .joinToString("") { "%02x".format(it) }

        if (!sherpaOnnxAarFile.exists() || sherpaOnnxAarFile.length() == 0L) download()
        if (sha256() != sherpaOnnxAarSha256) {
            logger.warn("Corrupt sherpa-onnx AAR at ${sherpaOnnxAarFile.absolutePath}; re-downloading")
            sherpaOnnxAarFile.delete()
            download()
            val rechecked = sha256()
            if (rechecked != sherpaOnnxAarSha256) {
                throw GradleException(
                    "sherpa-onnx AAR SHA-256 mismatch after re-download: expected $sherpaOnnxAarSha256 but got $rechecked"
                )
            }
        }
    }
}
tasks.named("preBuild").configure {
    dependsOn(downloadSherpaOnnxAar)
}
