// CLI: index / query / report (FR-RP-01, 설계서 8장)
plugins { application }

dependencies {
    implementation(project(":extract-java"))
    implementation(project(":extract-mybatis"))
    implementation(project(":graph-build"))
    implementation(project(":query"))
    implementation(project(":report"))
    implementation(libs.picocli)
}

application {
    mainClass.set("com.ids.qtrack.next.cli.Main")
    applicationName = "qtrack-next"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dqtrack.native.dir=__APP_HOME__/lib/native")
}

// 배포본에 tree-sitter 네이티브 라이브러리 포함
distributions {
    main {
        contents {
            from(project(":native").layout.buildDirectory.dir("lib/linux-x86_64")) { into("lib/native") }
        }
    }
}
tasks.named("installDist") { dependsOn(":native:buildTreeSitterNative") }
tasks.named("distZip") { dependsOn(":native:buildTreeSitterNative") }
tasks.named("distTar") { dependsOn(":native:buildTreeSitterNative") }
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.writeText(unixScript.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("__APP_HOME__", "%APP_HOME%"))
    }
}
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
