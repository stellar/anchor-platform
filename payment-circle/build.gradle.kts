plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm") version "1.6.10"
 }

dependencies {
    // ***************************
    // Production dependencies
    // ***************************
    // From mavenCentral
    implementation(libs.log4j.core)
    implementation(libs.google.gson)
    implementation(libs.reactor.core)
    implementation(libs.commons.validator)
    implementation(libs.slf4j.log4j12)
    implementation(libs.okhttp3.mockserver)
    implementation(libs.reactor.netty)
    implementation(libs.lombok)
    api(libs.servlet.api)

    // Lombok should be used by all sub-projects to reduce Java verbosity
    annotationProcessor(libs.lombok)


    // From jitpack.io
    implementation(libs.java.stellar.sdk)

    // From projects
    implementation(project(":core"))

    // ***************************
    // Test dependencies
    // ***************************
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.kotlin.junit5)
    testImplementation(libs.kotlin.mockk)
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.jsonassert)
    testAnnotationProcessor(libs.lombok)
}

tasks.test {
    useJUnitPlatform()
}