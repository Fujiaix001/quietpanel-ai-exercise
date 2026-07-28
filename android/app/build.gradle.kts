import java.io.File

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
        versionCode = 9010
        versionName = "9.0.10"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("public") {
            dimension = "distribution"
            buildConfigField("boolean", "INCLUDE_STOROPIA", "false")
        }
        create("private") {
            dimension = "distribution"
            buildConfigField("boolean", "INCLUDE_STOROPIA", "true")
        }
    }

    sourceSets {
        getByName("private") {
            assets.srcDir("src/private/assets")
        }
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

    buildFeatures {
        buildConfig = true
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

val verifyPhotoFonts by tasks.registering {
    val directoryPath = layout.projectDirectory.dir("src/main/assets/fonts").asFile.absolutePath
    inputs.dir(directoryPath)
    inputs.property("fontDirectoryPath", directoryPath)

    doLast {
        val openPhotoFontNames = setOf(
            "font_audiowide.ttf",
            "font_digital.ttf",
            "font_heavy.ttf",
            "font_kai.ttf",
            "font_orbitron.ttf",
            "font_oxanium.ttf",
            "font_rounded.ttf",
            "font_sairastencil.ttf",
            "font_sans.ttf",
            "font_serif.ttf",
            "font_zendots.ttf",
        )
        val directory = File(inputs.properties["fontDirectoryPath"] as String)
        val actualNames = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("ttf", ignoreCase = true) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        val allowedNames = openPhotoFontNames + "Storopia-Subset.ttf"

        check(actualNames.containsAll(openPhotoFontNames)) {
            "Missing open photo fonts: ${openPhotoFontNames - actualNames}"
        }
        check(actualNames.all { it in allowedNames }) {
            "Unexpected photo fonts: ${actualNames - allowedNames}"
        }

        actualNames.forEach { name ->
            val font = directory.resolve(name)
            check(font.length() > 1_000L) { "Font asset is empty or invalid: $name" }
            val signature = font.inputStream().use { input ->
                ByteArray(4).also { bytes ->
                    check(input.read(bytes) == bytes.size) { "Cannot read font header: $name" }
                }
            }
            val isTrueType = signature.contentEquals(byteArrayOf(0, 1, 0, 0))
            val isOpenType = signature.contentEquals("OTTO".toByteArray(Charsets.US_ASCII))
            check(isTrueType || isOpenType) { "Unsupported font header: $name" }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyPhotoFonts)
}
