plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 37
    namespace = "io.legado.app.quickjs"

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles += file("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkDependencies = true
        targetSdk = 37
    }
}

dependencies {
    implementation(libs.quickjs.kt)
    implementation(libs.kotlinx.coroutines.core)
}
