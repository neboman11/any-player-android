import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { stream ->
            load(stream)
        }
    }
}
val spotifyClientId = (
    (project.findProperty("spotifyClientId") as String?)?.trim()
        ?: localProperties.getProperty("spotifyClientId")?.trim()
).orEmpty()
val rustSharedWorkspaceDir = rootProject.file("../any-player-shared-rust")
val rustFfiArm64Target = "aarch64-linux-android"
val rustFfiX8664Target = "x86_64-linux-android"
val rustFfiArmv7Target = "armv7-linux-androideabi"
val rustFfiX86Target = "i686-linux-android"
val rustFfiCrate = "any_player_ffi_android"
val rustFfiSoName = "libany_player_ffi_android.so"
val rustFfiArm64OutputSo = rustSharedWorkspaceDir.resolve("target/$rustFfiArm64Target/release/$rustFfiSoName")
val rustFfiX8664OutputSo = rustSharedWorkspaceDir.resolve("target/$rustFfiX8664Target/release/$rustFfiSoName")
val rustFfiArmv7OutputSo = rustSharedWorkspaceDir.resolve("target/$rustFfiArmv7Target/release/$rustFfiSoName")
val rustFfiX86OutputSo = rustSharedWorkspaceDir.resolve("target/$rustFfiX86Target/release/$rustFfiSoName")
val rustFfiJniRootDir = layout.buildDirectory.dir("generated/rustJniLibs").get().asFile
val rustFfiArm64JniDir = rustFfiJniRootDir.resolve("arm64-v8a")
val rustFfiX8664JniDir = rustFfiJniRootDir.resolve("x86_64")
val rustFfiArmv7JniDir = rustFfiJniRootDir.resolve("armeabi-v7a")
val rustFfiX86JniDir = rustFfiJniRootDir.resolve("x86")
val skipRustFfiBuild =
    ((project.findProperty("skipRustFfiBuild") as String?)?.toBooleanStrictOrNull()) ?: false

fun resolveNdkDir(): java.io.File {
    val explicitNdkDir = (project.findProperty("androidNdkDir") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: localProperties.getProperty("ndk.dir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    if (explicitNdkDir != null) {
        val ndkDir = file(explicitNdkDir)
        if (!ndkDir.exists()) {
            throw GradleException("Configured Android NDK directory does not exist: $explicitNdkDir")
        }
        return ndkDir
    }

    val sdkDir = localProperties.getProperty("sdk.dir")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw GradleException(
            "Missing sdk.dir in local.properties; required for Rust arm64 build."
        )
    val ndkRoot = file("$sdkDir/ndk")
    val ndkDir = ndkRoot
        .listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.firstOrNull()
        ?: throw GradleException("No Android NDK installation found under ${ndkRoot.absolutePath}")
    return ndkDir
}

android {
    namespace = "com.anyplayer.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anyplayer.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
            abiFilters += "armeabi-v7a"
            abiFilters += "x86"
        }
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(rustFfiJniRootDir)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.ExperimentalStdlibApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "log4j2.xml"
            excludes += "log4j.properties"
            pickFirsts += "**/*.proto"
        }
    }
}

dependencies {
    val hiltVersion = "2.57.2"
    val roomVersion = "2.7.2"
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    implementation("androidx.media:media:1.7.0")

    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.car.app:app:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.robolectric:robolectric:4.12.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

if (!skipRustFfiBuild) {
    val buildRustFfiArm64 = tasks.register("buildRustFfiArm64", Exec::class.java) {
        group = "build"
        description = "Builds Rust any_player_ffi_android for arm64-v8a"
        workingDir = rustSharedWorkspaceDir
        commandLine("cargo", "build", "-p", rustFfiCrate, "--target", rustFfiArm64Target, "--release")
        doFirst {
            val ndkDir = resolveNdkDir()
            val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
            val linker = toolchainBin.resolve("aarch64-linux-android26-clang")
            val ar = toolchainBin.resolve("llvm-ar")
            if (!rustSharedWorkspaceDir.exists()) {
                throw GradleException("Rust shared workspace not found: ${rustSharedWorkspaceDir.absolutePath}")
            }
            if (!linker.exists()) {
                throw GradleException("Missing NDK linker: ${linker.absolutePath}")
            }
            if (!ar.exists()) {
                throw GradleException("Missing NDK llvm-ar: ${ar.absolutePath}")
            }
            rustFfiArm64JniDir.mkdirs()
            environment("CC_aarch64_linux_android", linker.absolutePath)
            environment("AR_aarch64_linux_android", ar.absolutePath)
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
        }
    }

    val syncRustFfiArm64 = tasks.register("syncRustFfiArm64", Copy::class.java) {
        group = "build"
        description = "Copies Rust arm64-v8a FFI library into app jniLibs"
        dependsOn(buildRustFfiArm64)
        from(rustFfiArm64OutputSo)
        into(rustFfiArm64JniDir)
        doFirst {
            if (!rustFfiArm64OutputSo.exists()) {
                throw GradleException("Rust FFI output missing: ${rustFfiArm64OutputSo.absolutePath}")
            }
        }
    }

    val buildRustFfiX8664 = tasks.register("buildRustFfiX8664", Exec::class.java) {
        group = "build"
        description = "Builds Rust any_player_ffi_android for x86_64 emulator ABI"
        workingDir = rustSharedWorkspaceDir
        commandLine("cargo", "build", "-p", rustFfiCrate, "--target", rustFfiX8664Target, "--release")
        doFirst {
            val ndkDir = resolveNdkDir()
            val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
            val linker = toolchainBin.resolve("x86_64-linux-android26-clang")
            val ar = toolchainBin.resolve("llvm-ar")
            if (!rustSharedWorkspaceDir.exists()) {
                throw GradleException("Rust shared workspace not found: ${rustSharedWorkspaceDir.absolutePath}")
            }
            if (!linker.exists()) {
                throw GradleException("Missing NDK linker: ${linker.absolutePath}")
            }
            if (!ar.exists()) {
                throw GradleException("Missing NDK llvm-ar: ${ar.absolutePath}")
            }
            rustFfiX8664JniDir.mkdirs()
            environment("CC_x86_64_linux_android", linker.absolutePath)
            environment("AR_x86_64_linux_android", ar.absolutePath)
            environment("CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER", linker.absolutePath)
        }
    }

    val syncRustFfiX8664 = tasks.register("syncRustFfiX8664", Copy::class.java) {
        group = "build"
        description = "Copies Rust x86_64 FFI library into app jniLibs"
        dependsOn(buildRustFfiX8664)
        from(rustFfiX8664OutputSo)
        into(rustFfiX8664JniDir)
        doFirst {
            if (!rustFfiX8664OutputSo.exists()) {
                throw GradleException("Rust FFI output missing: ${rustFfiX8664OutputSo.absolutePath}")
            }
        }
    }

    val buildRustFfiArmv7 = tasks.register("buildRustFfiArmv7", Exec::class.java) {
        group = "build"
        description = "Builds Rust any_player_ffi_android for armeabi-v7a"
        workingDir = rustSharedWorkspaceDir
        commandLine("cargo", "build", "-p", rustFfiCrate, "--target", rustFfiArmv7Target, "--release")
        doFirst {
            val ndkDir = resolveNdkDir()
            val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
            val linker = toolchainBin.resolve("armv7a-linux-androideabi26-clang")
            val ar = toolchainBin.resolve("llvm-ar")
            if (!rustSharedWorkspaceDir.exists()) {
                throw GradleException("Rust shared workspace not found: ${rustSharedWorkspaceDir.absolutePath}")
            }
            if (!linker.exists()) {
                throw GradleException("Missing NDK linker: ${linker.absolutePath}")
            }
            if (!ar.exists()) {
                throw GradleException("Missing NDK llvm-ar: ${ar.absolutePath}")
            }
            rustFfiArmv7JniDir.mkdirs()
            environment("CC_armv7_linux_androideabi", linker.absolutePath)
            environment("AR_armv7_linux_androideabi", ar.absolutePath)
            environment("CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER", linker.absolutePath)
        }
    }

    val syncRustFfiArmv7 = tasks.register("syncRustFfiArmv7", Copy::class.java) {
        group = "build"
        description = "Copies Rust armeabi-v7a FFI library into app jniLibs"
        dependsOn(buildRustFfiArmv7)
        from(rustFfiArmv7OutputSo)
        into(rustFfiArmv7JniDir)
        doFirst {
            if (!rustFfiArmv7OutputSo.exists()) {
                throw GradleException("Rust FFI output missing: ${rustFfiArmv7OutputSo.absolutePath}")
            }
        }
    }

    val buildRustFfiX86 = tasks.register("buildRustFfiX86", Exec::class.java) {
        group = "build"
        description = "Builds Rust any_player_ffi_android for x86 ABI"
        workingDir = rustSharedWorkspaceDir
        commandLine("cargo", "build", "-p", rustFfiCrate, "--target", rustFfiX86Target, "--release")
        doFirst {
            val ndkDir = resolveNdkDir()
            val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
            val linker = toolchainBin.resolve("i686-linux-android26-clang")
            val ar = toolchainBin.resolve("llvm-ar")
            if (!rustSharedWorkspaceDir.exists()) {
                throw GradleException("Rust shared workspace not found: ${rustSharedWorkspaceDir.absolutePath}")
            }
            if (!linker.exists()) {
                throw GradleException("Missing NDK linker: ${linker.absolutePath}")
            }
            if (!ar.exists()) {
                throw GradleException("Missing NDK llvm-ar: ${ar.absolutePath}")
            }
            rustFfiX86JniDir.mkdirs()
            environment("CC_i686_linux_android", linker.absolutePath)
            environment("AR_i686_linux_android", ar.absolutePath)
            environment("CARGO_TARGET_I686_LINUX_ANDROID_LINKER", linker.absolutePath)
        }
    }

    val syncRustFfiX86 = tasks.register("syncRustFfiX86", Copy::class.java) {
        group = "build"
        description = "Copies Rust x86 FFI library into app jniLibs"
        dependsOn(buildRustFfiX86)
        from(rustFfiX86OutputSo)
        into(rustFfiX86JniDir)
        doFirst {
            if (!rustFfiX86OutputSo.exists()) {
                throw GradleException("Rust FFI output missing: ${rustFfiX86OutputSo.absolutePath}")
            }
        }
    }

    tasks.named("preBuild").configure {
        dependsOn(syncRustFfiArm64)
        dependsOn(syncRustFfiX8664)
        dependsOn(syncRustFfiArmv7)
        dependsOn(syncRustFfiX86)
    }
}
