# Marketplace Listing

## Name

Reference Switcher

## Summary

Switch NuGet package references to local .NET projects and restore them later.

## Description

Reference Switcher makes local package development a Rider operation instead of a manual project-file workflow.

Open a consuming `.csproj`, invoke **Use Local Project...** on a `PackageReference`, and select or automatically discover the corresponding local project. The plugin replaces only that XML element with a relative `ProjectReference`. When development is complete, **Restore Package Reference** puts back the exact original XML.

Features include configurable source-root discovery, remembered package mappings, analyzer/source-generator reference support, conservative target-framework checks, stale-switch detection, and a management dialog for active local references.

The plugin modifies `.csproj` files and intentionally does not attempt to reproduce every asset delivered by a NuGet package. Its documentation clearly describes build assets, transitive dependencies, out-of-solution projects, and other limitations.

## Tags

- .NET
- NuGet
- project management
- dependency management

## Links

- Source: https://github.com/tombiddulph/ReferenceSwitcher
- Issues: https://github.com/tombiddulph/ReferenceSwitcher/issues
- Privacy: https://github.com/tombiddulph/ReferenceSwitcher/blob/main/PRIVACY.md
- License: MIT

## Initial Upload

1. Build and verify with `./gradlew test buildPlugin verifyPlugin`.
2. Upload `build/distributions/rider-reference-switcher-0.1.5.zip` through the Marketplace web form.
3. Select the Tom Biddulph vendor profile and the MIT license.
4. Set the source, issue, and privacy links above.
5. Declare whether the vendor is a trader or non-trader as appropriate.
6. Add the listed tags and submit for review.
7. After the listing exists, add `PUBLISH_TOKEN` as a GitHub Actions secret and set the repository variable `MARKETPLACE_LISTING_READY` to `true` for automated updates.
