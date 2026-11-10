plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

// ./gradlew :core:runScenarioCheck  → 시연 시나리오 11개 + 음성 분류 자동 검증
tasks.register<JavaExec>("runScenarioCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.guardiandrive.core.ScenarioCheckKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
}
