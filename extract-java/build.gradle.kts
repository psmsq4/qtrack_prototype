// Tier A + D: jtreesitter CST → SSA lowering → MethodIR, 제어 의존(PREDICATE/CONTROL), Rule Pack 적용 (설계서 3.1~3.3)
dependencies {
    api(project(":ir"))
    api(project(":rules"))
    api(project(":extract-sql"))
    implementation(libs.snakeyaml)
}
