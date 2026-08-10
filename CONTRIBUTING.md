# Contributing

Contributions and focused bug reports are welcome.

## Development

Requirements: JDK 21 and Rider 2025.1 or newer.

```shell
./gradlew test buildPlugin verifyPlugin
```

Keep Rider integration thin and place reference transformation behavior in Rider-independent code with focused tests. Changes must preserve project content outside the selected reference.

## Bug Reports

Include the relevant reference XML, target frameworks, operating system, and Rider build. Remove proprietary paths, package names, credentials, and source code before posting.

## Pull Requests

- Explain the behavior change and its motivation.
- Add regression tests for fixes.
- Update `CHANGELOG.md` for user-visible changes.
- Do not change the plugin ID.
- Confirm the complete verification command succeeds.

## Releases

Versions follow semantic versioning. A maintainer updates `build.gradle.kts` and `CHANGELOG.md`, verifies the plugin, and pushes a matching `vX.Y.Z` tag. The release workflow creates the GitHub release and publishes Marketplace updates after the initial listing has been approved.
