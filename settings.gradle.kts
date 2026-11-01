import java.io.File

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info/")
        maven {
            url = uri("https://maven.pkg.github.com/Valenced/valenced")
            credentials(PasswordCredentials::class) {
                username = providers.gradleProperty("GitHubPackagesUsername").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("GitHubPackagesPassword").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "reaction-logs"

val pluginRoots = listOf("plugins", "themes")
pluginRoots.forEach { root ->
    file(root).listFiles()
        ?.filter { it.isDirectory && File(it, "src/main").isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { dir -> include(":\${root}:\${dir.name}") }
}
