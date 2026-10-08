rootProject.name = "qtrack-next"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(
    "native",
    "ir",
    "rules",
    "catalog",
    "extract-sql",
    "extract-mybatis",
    "extract-java",
    "graph-store",
    "graph-build",
    "query",
    "report",
    "cli",
    "bench",
    "golden",
)
