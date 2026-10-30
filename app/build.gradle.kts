plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "fr.upjv.bodyvision"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "fr.upjv.bodyvision"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.pose.detection)

    testImplementation(libs.junit)
}

// camera-view ramène appcompat par transitivité, mais seulement dans sa variante d'exécution :
// un import ne compilerait donc pas. Ses *ressources*, elles, sont bien fusionnées, et un
// parent de thème XML emprunté à cette pile compilerait sans rien dire. C'est exactement le
// correctif réflexe quand une ressource de thème manque, et il ramène toute la pile Views en
// douce. Le bon correctif est un parent du framework (android:Theme.*).
//
// Un seul marqueur, et volontairement court : « Theme.AppCompat » laissait passer
// ThemeOverlay.AppCompat, Widget.AppCompat, TextAppearance.AppCompat… c'est-à-dire la plus
// grande partie de ce qu'il fallait fermer. Aucun identifiant légitime de ce projet ne
// contient cette chaîne.
//
// Elle est citée ici, dans le script de compilation, et nulle part sous app/src : un fichier
// source qui la citerait déclencherait sa propre barrière.
val forbiddenViewsStackMarkers = listOf("AppCompat")

val checkNoAppCompat =
    tasks.register("checkNoAppCompat") {
        group = "verification"
        description = "Échoue si la pile Views est référencée dans app/src."
        val sources = fileTree("src") { include("**/*.kt", "**/*.xml") }
        val markers = forbiddenViewsStackMarkers
        // Une barrière ne vaut que si elle tourne : un rapport UP-TO-DATE ne prouve rien.
        outputs.upToDateWhen { false }
        doLast {
            val offenders =
                sources.files.flatMap { file ->
                    val text = file.readText()
                    markers.filter { text.contains(it) }.map { "${file.path} : $it" }
                }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    offenders.joinToString(
                        prefix = "Pile Views interdite dans app/src :\n",
                        separator = "\n",
                        postfix =
                            "\nSi une ressource de thème manque, pointer le parent vers un thème " +
                                "du framework (android:Theme.*).",
                    ) { "  - $it" },
                )
            }
        }
    }

tasks.named("check") { dependsOn(checkNoAppCompat) }
