// tree-sitter 네이티브 빌드 (NFR-03, 설계서 10장)
// - buildTreeSitterNative       : 로컬 C 컴파일러(cc)로 빌드
// - buildTreeSitterNativeDocker : Docker(gcc 이미지) 안에서 같은 스크립트로 빌드 → 팀 전체 동일 환경
import java.net.URI

val coreVersion = libs.versions.treesitter.core.get()
val javaGrammarVersion = libs.versions.treesitter.java.get()
val srcRoot = layout.buildDirectory.dir("native-src")
val outDir = layout.buildDirectory.dir("lib/linux-x86_64")

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
}

val fetchTreeSitterSources by tasks.registering {
    group = "native"
    description = "tree-sitter 코어와 tree-sitter-java 소스 tarball을 내려받아 푼다"
    inputs.property("core", coreVersion)
    inputs.property("java", javaGrammarVersion)
    outputs.dir(srcRoot)
    doLast {
        val root = srcRoot.get().asFile
        val dl = layout.buildDirectory.dir("native-dl").get().asFile
        listOf(
            Triple("tree-sitter", "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v$coreVersion.tar.gz", "tree-sitter-$coreVersion"),
            Triple("tree-sitter-java", "https://github.com/tree-sitter/tree-sitter-java/archive/refs/tags/v$javaGrammarVersion.tar.gz", "tree-sitter-java-$javaGrammarVersion"),
        ).forEach { (name, url, top) ->
            val tgz = File(dl, "$name.tar.gz")
            if (!tgz.exists()) download(url, tgz)
            val target = File(root, name)
            delete(target)
            copy {
                from(tarTree(resources.gzip(tgz)))
                into(root)
            }
            File(root, top).renameTo(target)
        }
    }
}

val buildTreeSitterNative by tasks.registering(Exec::class) {
    group = "native"
    description = "libtree-sitter.so / libtree-sitter-java.so 빌드 (로컬 cc)"
    dependsOn(fetchTreeSitterSources)
    inputs.dir(srcRoot)
    inputs.file("build-native.sh")
    outputs.dir(outDir)
    commandLine("sh", file("build-native.sh").absolutePath,
        srcRoot.get().asFile.absolutePath, outDir.get().asFile.absolutePath)
}

tasks.register<Exec>("buildTreeSitterNativeDocker") {
    group = "native"
    description = "Docker(gcc) 컨테이너에서 네이티브 라이브러리 빌드"
    dependsOn(fetchTreeSitterSources)
    val dockerDir = layout.buildDirectory.dir("docker").get().asFile
    doFirst {
        dockerDir.mkdirs()
        file("build-native.sh").copyTo(File(dockerDir, "build-native.sh"), overwrite = true)
    }
    commandLine("sh", "-c", """
        docker build -q -t qtrack-next-native:${coreVersion} -f ${file("Dockerfile").absolutePath} ${dockerDir.absolutePath} &&
        docker run --rm -u $(id -u):$(id -g) \
          -v ${file("build-native.sh").absolutePath}:/work/build-native.sh:ro \
          -v ${srcRoot.get().asFile.absolutePath}:/work/src:ro \
          -v ${outDir.get().asFile.absolutePath}:/work/out \
          qtrack-next-native:${coreVersion}
    """.trimIndent())
}
