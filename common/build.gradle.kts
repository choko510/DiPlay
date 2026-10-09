plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val splitViewMode = providers.gradleProperty("diplaySplitViewMode").orElse("off").get()
require(splitViewMode in setOf("off", "local", "dynamic-experimental")) {
    "diplaySplitViewMode must be 'off', 'local', or 'dynamic-experimental'"
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "SPLIT_VIEW_MODE", "\"$splitViewMode\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    api(project(":shared"))
    implementation(libs.mozilla.geckoview)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
