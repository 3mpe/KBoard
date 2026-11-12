plugins {
    java
    application
}

repositories {
    mavenCentral()
    // Zemberek-NLP Maven Central'da yok; yazarın kendi Maven deposu.
    maven("https://raw.githubusercontent.com/ahmetaa/maven-repo/master") {
        content { includeGroup("zemberek-nlp") }
    }
}

val zemberekVersion = "0.17.1"

dependencies {
    implementation("zemberek-nlp:zemberek-morphology:$zemberekVersion")
}

tasks.withType<JavaCompile>().configureEach {
    // JDK 17+ ile çalışır; ek toolchain indirmesi gerektirmez.
    options.release.set(17)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("kboard.spike.Spike")
}

tasks.named<JavaExec>("run") {
    // Orkestratör küçük; ölçümler ayrı alt JVM'lerde yapılır.
    maxHeapSize = "256m"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    workingDir = rootDir
    args = listOf(
        "bench",
        "--words", rootDir.resolve("../../eval/testset/seed-v0.jsonl").canonicalPath,
        "--out", layout.buildDirectory.file("rapor.md").get().asFile.path,
    ) + ((project.findProperty("spikeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
}
