pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "sherpaOnnxGitHubReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "openwhispr"
include(":app")
