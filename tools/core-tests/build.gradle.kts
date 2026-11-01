plugins {
    kotlin("jvm") version "2.1.21"
}


val pdfboxVersion = "2.0.27"

dependencies {
    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}

// Copy the Android app's core engine sources, rewriting PdfBox-Android packages
// to the desktop Apache PDFBox equivalents.
val coreSrcDir = rootDir.resolve("../../app/src/main/java/com/tandidong/pdf/core")
val generatedDir = layout.buildDirectory.dir("generated/core-src")

val syncCoreSources by tasks.registering(Sync::class) {
    from(coreSrcDir)
    into(generatedDir.map { it.dir("com/tandidong/pdf/core") })
    include("**/*.kt")
    filter { line: String ->
        line.replace("com.tom_roush.pdfbox", "org.apache.pdfbox")
            .replace("com.tom_roush.fontbox", "org.apache.fontbox")
    }
}

sourceSets["main"].kotlin.srcDir(generatedDir)

tasks.named("compileKotlin") { dependsOn(syncCoreSources) }

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
