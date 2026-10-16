plugins {
    id("com.android.application")
}

val previewPdf = providers.gradleProperty("previewPdf").orNull
val previewAssetsDirectory = layout.buildDirectory.dir("generated/previewAssets")
val preparePreviewAssets = previewPdf?.let { pdfName ->
    tasks.register<Sync>("preparePreviewAssets") {
        from(project.file("src/main/assets/manga/$pdfName")) {
            into("manga")
        }
        into(previewAssetsDirectory)
    }
}

android {
    namespace = "com.pessoal.leitordemanga"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pessoal.leitordemanga"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    if (!previewPdf.isNullOrBlank()) {
        require(!previewPdf.contains('/') && !previewPdf.contains('\\')
                && previewPdf.endsWith(".pdf", ignoreCase = true)) {
            "previewPdf must be the name of one PDF file inside app/src/main/assets/manga."
        }
        require(project.file("src/main/assets/manga/$previewPdf").isFile) {
            "The preview PDF was not found: app/src/main/assets/manga/$previewPdf"
        }
        sourceSets.getByName("main").assets.setSrcDirs(
            listOf(previewAssetsDirectory.get().asFile)
        )
    } else {
        sourceSets.getByName("main").assets.setSrcDirs(emptyList<Any>())
    }
}

if (preparePreviewAssets != null) {
    tasks.configureEach {
        if (name.startsWith("merge") && name.endsWith("Assets")) {
            dependsOn(preparePreviewAssets)
        }
    }
}
