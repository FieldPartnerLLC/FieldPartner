pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    // Meta Wearables Device Access Toolkit and LiveKit are both on Maven Central.
    mavenCentral()
    // LiveKit's audioswitch fork is published on JitPack only.
    maven {
      url = uri("https://jitpack.io")
      content { includeGroupByRegex("com\\.github\\..*") }
    }
  }
}

rootProject.name = "FieldPartner"

include(":app")
