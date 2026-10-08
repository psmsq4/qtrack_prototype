// Tier C: JSqlParser 래퍼 — 테이블·컬럼·바인드·절 추출, 이름 해석 (FR-IN-05, FR-SQ)
dependencies {
    api(project(":ir"))
    api(project(":catalog"))
    api(libs.jsqlparser)
}
