/*
 * HushhBoard word engine (pure Kotlin/JVM, no Android dependencies).
 * Licensed under the Apache License, Version 2.0.
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotest)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks {
    compileKotlin {
        compilerOptions.jvmTarget = JvmTarget.JVM_11
    }
    compileTestKotlin {
        compilerOptions.jvmTarget = JvmTarget.JVM_11
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The production dictionary lives in the app's assets; tests read it from there.
    systemProperty(
        "hushh.dict",
        rootProject.file("app/src/main/assets/ime/dict/hushh/en_ke_sw.hdict").absolutePath,
    )
    systemProperty(
        "hushh.ngrams",
        rootProject.file("app/src/main/assets/ime/dict/hushh/en_ke_sw.hngm").absolutePath,
    )
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
