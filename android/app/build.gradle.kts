plugins {
    id("com.android.application")
}

android {
    namespace = "com.quietpanel.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quietpanel.client"
        minSdk = 17
        targetSdk = 36
        versionCode = 670
        versionName = "6.7.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
}
