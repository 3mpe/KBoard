// KBoard öneri motoru: saf Kotlin/JVM, Android bağımlılığı yok.
// Masaüstünde test edilir ve eval/ aracından JSON-satır protokolüyle çağrılır.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.kboard.engine.cli.EvalCliKt")
}

dependencies {
    // app/ zaten aynı sürümü kullanıyor; yeni bir bağımlılık değil
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// Aksan düzeltici dizini (deasciify-tr.kbd), uygulamanın sözlüğünden (main_tr.dict) derleme
// sırasında üretilir; app/ bunu asset olarak paketler. Python 3 gerekir (data/tools/dict_dump.py).
val kboardDir = layout.buildDirectory.dir("kboard")
val dumpTurkishDict by tasks.registering(Exec::class) {
    description = "main_tr.dict → kelime<TAB>sıklık<TAB>saldırgan"
    val dict = file("../app/src/main/assets/dicts/main_tr.dict")
    val script = file("../data/tools/dict_dump.py")
    val out = kboardDir.map { it.file("main_tr.tsv") }
    inputs.files(dict, script)
    outputs.file(out)
    commandLine("python3", script.path, dict.path, "--offensive", "-o", out.get().asFile.path)
}
val buildDeasciifyIndex by tasks.registering(JavaExec::class) {
    description = "Aksan düzeltici dizinini (deasciify-tr.kbd) derler"
    val tsv = kboardDir.map { it.file("main_tr.tsv") }
    val protect = file("../data/deasciify-koruma.txt")
    val out = kboardDir.map { it.file("deasciify-tr.kbd") }
    dependsOn(dumpTurkishDict)
    inputs.files(tsv, protect)
    outputs.file(out)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.kboard.engine.deasciify.DeasciifyToolKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    args("build", tsv.get().asFile.path, protect.path, out.get().asFile.path)
}

// Sonraki kelime / bağlam dil modeli (kboard-tr.kbn): data/corpus/ altındaki CC0 korpustan, test
// setiyle 4-gram örtüşen cümleler atılarak üretilir (data/tools/build_ngram.py). Python 3 gerekir.
val buildNgramModel by tasks.registering(Exec::class) {
    description = "N-gram dil modelini (kboard-tr.kbn) derler"
    val script = file("../data/tools/build_ngram.py")
    val corpus = file("../data/corpus/commonvoice-tr.txt")
    val testset = file("../eval/testset/seed-v0.jsonl")
    val out = kboardDir.map { it.file("kboard-tr.kbn") }
    inputs.files(script, corpus, testset)
    outputs.file(out)
    commandLine(
        "python3", script.path, "--corpus", "cv=${corpus.path}", "--exclude-jsonl", testset.path,
        "--min-uni", "1", "--min-bi", "1", "--min-tri", "2", "--max-succ", "60", "-o", out.get().asFile.path,
    )
}
