import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "2.2.0"
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.walter.spring.ai.ops"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.1.0"
extra["springCloudVersion"] = "2024.0.1"

// Separate configuration for SonarQube language plugin JARs.
// These are NOT added to the Spring Boot classloader — doing so would cause classpath
// conflicts because the plugin JARs bundle their own Jasper/Servlet versions.
// Instead, the JARs are copied to build/resources/main/sonar-plugins/ and served
// as raw byte resources by SonarPluginRegistry at runtime.
val sonarPlugins by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}"))
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}"))
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    implementation("io.github.openfeign:feign-okhttp")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-mustache")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("com.github.loki4j:loki-logback-appender:1.5.2")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")

    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("com.github.codemonstur:embedded-redis:1.4.3")
    implementation("org.webjars:sockjs-client:1.5.1")
    implementation("org.webjars:stomp-websocket:2.3.4")
    implementation("org.webjars:highlightjs:11.10.0")
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib"))

    implementation("org.sonarsource.api.plugin:sonar-plugin-api:10.3.0.1951") {
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    // SonarQube language analysis plugins — resolved into sonarPlugins configuration only,
    // NOT placed on the Spring Boot classloader. Copied to sonar-plugins/ resources by
    // the copySonarPlugins task and served as byte resources via SonarPluginRegistry.
    sonarPlugins("org.sonarsource.kotlin:sonar-kotlin-plugin:3.5.0.9240")
    sonarPlugins("org.sonarsource.java:sonar-java-plugin:8.28.0.43176")
    sonarPlugins("org.sonarsource.javascript:sonar-javascript-plugin:12.3.0.39932")
    sonarPlugins("org.sonarsource.python:sonar-python-plugin:5.21.0.32726")
    sonarPlugins("org.sonarsource.slang:sonar-ruby-plugin:1.7.0.883")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Copy sonar plugin JARs into the compiled resources directory so they are accessible
// at runtime as classpath:sonar-plugins/*.jar without polluting the application classloader.
tasks.register<Copy>("copySonarPlugins") {
    from(configurations["sonarPlugins"])
    into(layout.buildDirectory.dir("resources/main/sonar-plugins"))
}

tasks.named("processResources") {
    dependsOn("copySonarPlugins")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}