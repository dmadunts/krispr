# dmadunts/krispr

A fork of [timusus/krispr](https://github.com/timusus/krispr) (Apache License 2.0) that builds and hosts
krispr for the owner's projects until upstream publishes it.

## What differs from upstream

- `MutationTransformer` reads sources with LF line endings. The Kotlin compiler counts IR offsets over LF text,
  and on a CRLF checkout (Windows) every slice taken from a file was shifted by one character per line above it:
  descriptions, declaration hashes and the mutants decided from the text. Sent upstream as timusus/krispr#6.
- A module with `testProject` takes that project's test classpath from a task of the `testProject` itself
  (`KrisprTestClasspathTask`). Gradle resolves a project's configurations only under that project's lock, so in a
  parallel build the krispr tasks failed when the configuration cache stored them and when they ran.

## Versions

A tag `fb-v<version>` publishes that version into the `maven-repo` branch
(`.github/workflows/publish-maven-repo.yml`). Versions are named after upstream's with an `-fb.N` suffix:
`0.1.0-fb.1` is upstream at 15ddf06 plus the first change above; `0.1.0-fb.2` adds the second.

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
    id("dev.krispr") version "0.1.0-fb.2"
}
```

No credentials are needed: the branch is public.
