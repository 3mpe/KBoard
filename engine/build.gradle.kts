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
