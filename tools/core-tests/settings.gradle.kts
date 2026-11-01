// Standalone JVM harness that compiles the app's pure-PDFBox core engine
// (app/src/main/java/com/tandidong/pdf/core) against desktop Apache PDFBox 2.0.x
// and runs its unit tests. PdfBox-Android mirrors the PDFBox 2.0 API under the
// com.tom_roush package, so sources are copied with the package names rewritten.
pluginManagement {
    repositories {
        providers.gradleProperty("mavenMirror").orNull?.let { maven(url = it) }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        providers.gradleProperty("mavenMirror").orNull?.let { maven(url = it) }
        mavenCentral()
    }
}
rootProject.name = "core-tests"
