pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every repository is declared here and nowhere else. A module that adds its
    // own is a build failure rather than a surprise: the BOOX repo below serves
    // plain http, and the one thing worse than an insecure repository is an
    // insecure repository nobody knew was in the graph.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // g-paper is published from ~/git/g-paper by `./gradlew publishToMavenLocal`.
        // It is first because a locally built engine is always the one we mean.
        mavenLocal()
        google()
        mavenCentral()
        // The Onyx SDK that gpaper-onyx's POM pulls in lives only here, and BOOX
        // publishes no https mirror. Removing this line does not make the build
        // safer — it makes it not resolve.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "paintsprout_onyx"
include(":app")
