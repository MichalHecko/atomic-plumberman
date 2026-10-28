rootProject.name = "atomic-plumberman"
include("game-core", "content-tools", "network", "desktop-app")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
