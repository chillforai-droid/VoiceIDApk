// Top-level build file
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    // Push notifications (incoming-call wake-up when the app isn't in the foreground).
    // Only actually applied in app/build.gradle.kts when a real google-services.json is
    // present, so the project keeps building normally before Firebase is set up.
    id("com.google.gms.google-services") version "4.4.4" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
