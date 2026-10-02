# dmadunts/krispr

A fork of [timusus/krispr](https://github.com/timusus/krispr) (Apache License 2.0) that builds and hosts
krispr for the owner's projects until upstream publishes it.

## What differs from upstream

- `MutationTransformer` reads sources with LF line endings. The Kotlin compiler counts IR offsets over LF text,
  and on a CRLF checkout (Windows) every slice taken from a file was shifted by one character per line above it.

## Versions

A tag `fb-v<version>` publishes that version into the `maven-repo` branch
(`.github/workflows/publish-maven-repo.yml`). Versions are named after upstream's with an `-fb.N` suffix:
`0.1.0-fb.1` is upstream at 15ddf06 plus the change above.

## Using it in a project

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://raw.githubusercontent.com/dmadunts/krispr/maven-repo/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://raw.githubusercontent.com/dmadunts/krispr/maven-repo/")
        mavenCentral()
    }
}

// build.gradle.kts of a module
plugins {
    id("dev.krispr") version "0.1.0-fb.1"
}
```

No credentials are needed: the branch is public.
