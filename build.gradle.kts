// Q-Track 차세대 엔진 프로토타입 — 루트 빌드 (설계서 2.3 모듈 구성)
allprojects {
    group = "com.ids.qtrack.next"
    version = "0.1.0-SNAPSHOT"
}

val nativeDir = project(":native").layout.buildDirectory.dir("lib/linux-x86_64")

subprojects {
    if (name == "native") return@subprojects
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing,-this-escape"))
    }
    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        dependsOn(":native:buildTreeSitterNative")
        systemProperty("qtrack.native.dir", nativeDir.get().asFile.absolutePath)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        maxHeapSize = "1g"
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
    tasks.withType<JavaExec>().configureEach {
        dependsOn(":native:buildTreeSitterNative")
        systemProperty("qtrack.native.dir", nativeDir.get().asFile.absolutePath)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
