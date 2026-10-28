import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional keys go in local.properties (not committed):
//   NREL_API_KEY=...   (free at developer.nrel.gov; DEMO_KEY is used if missing)
//   OCM_API_KEY=...    (Open Charge Map; GitHub builds take it from the OCM_API_KEY repository secret)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String, default: String = "") = "\"${localProps.getProperty(name, default)}\""

android {
    namespace = "app.puddlejumper"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.puddlejumper"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.4"
        buildConfigField("String", "NREL_API_KEY", key("NREL_API_KEY", "DEMO_KEY"))
        // Never commit keys here: they come from local.properties or the CI secret.
        buildConfigField("String", "OCM_API_KEY", key("OCM_API_KEY"))
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
