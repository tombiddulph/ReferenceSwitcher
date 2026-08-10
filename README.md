# Reference Switcher

[![Build](https://github.com/tombiddulph/ReferenceSwitcher/actions/workflows/build.yml/badge.svg)](https://github.com/tombiddulph/ReferenceSwitcher/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Reference Switcher replaces a NuGet `PackageReference` with a local `ProjectReference` from JetBrains Rider and restores the exact original reference when local development is complete.

```text
PackageReference -> Use Local Project... -> edit local source -> Restore Package Reference
```

## Requirements

- JetBrains Rider 2025.1 or newer.
- SDK-style .NET projects using direct `<PackageReference Include="...">` items.
- The local project must already exist on disk.

## Install

Once approved, install **Reference Switcher** from **Settings | Plugins | Marketplace**.

For a development build:

1. Download or build the plugin ZIP.
2. Open **Settings | Plugins**.
3. Select the gear menu and **Install Plugin from Disk...**.
4. Select the ZIP from `build/distributions` and restart Rider when requested.

## Use

1. Open the consuming `.csproj` in Rider.
2. Right-click the `PackageReference` you want to replace.
3. Select **Use Local Project...**.
4. Choose the matching local `.csproj` if it is not found automatically.
5. When finished, right-click the consuming `.csproj` and select **Restore Package Reference**.

The switch changes only the selected XML element. The complete original `PackageReference`, including attributes, child elements, conditions, and formatting, is retained for restore.

## Project Discovery

Open **Settings | Tools | Local References**, or **Tools | Local References... | Configure Source Roots...**, and add directories containing local repositories.

The scanner:

- Searches recursively for `.csproj` files.
- Skips `.git`, `.vs`, `artifacts`, `bin`, `node_modules`, and `obj` directories.
- Accepts SDK-style projects only.
- Resolves identity from `PackageId`, then `AssemblyName`, then the project filename.
- Reads inherited `Directory.Build.props` values and expands `$(MSBuildProjectName)`.
- Matches package IDs case-insensitively.

Previously selected package-to-project mappings take priority. If one discovered project matches, it is selected automatically. Ambiguous matches display a chooser, and no match falls back to a file picker. To avoid slowing Rider startup, the index refreshes in the background when settings are applied or when **Refresh Local Projects** is selected; filesystem watching is not currently implemented.

## Safety And Limitations

Reference Switcher modifies the consuming `.csproj`. Review the change before committing it. The plugin never automatically reverts files when Rider closes.

- Duplicate or ambiguous package references are refused.
- Restore is refused if the expected managed `ProjectReference` has changed or disappeared.
- Central Package Management is supported by changing only the consuming project; `Directory.Packages.props` is not modified.
- Literal target frameworks are compared using a conservative subset of common `.NET` and `.NET Standard` compatibility rules. This is not full MSBuild or NuGet evaluation.
- Analyzer and source-generator projects are detected heuristically and require confirmation.
- Some project-declared packaged MSBuild assets are detected and produce a warning. Package contents, imported targets, native assets, content files, and transitive dependency differences cannot be reproduced reliably by a `ProjectReference`.
- Projects outside the loaded solution are not added to the solution. Rider navigation and debugging behavior for those projects depends on Rider's project model.
- `PackageReference Update="..."`, references inherited from props/targets files, legacy projects, and bulk switching are not supported.

If a switch is active, use **Tools | Local References...** to restore or forget it. **Forget** removes plugin state and does not edit the project file.

## Data And Privacy

The plugin has no telemetry, analytics, advertising, or network communication.

It scans only source roots you configure and stores those roots, remembered package mappings, local file paths, and original reference XML in Rider's local settings. This data is used only for discovery and restoration. See [PRIVACY.md](PRIVACY.md) for details.

## Build

Requirements: JDK 21 and an internet connection for the Rider SDK dependency.

```shell
./gradlew test buildPlugin verifyPlugin
```

To run the generated 100-repository discovery performance fixture:

```shell
REFERENCE_SWITCHER_PERFORMANCE_TEST=true ./gradlew test --tests '*LargeProjectFixtureTest*' --info
```

The installable archive is written to `build/distributions`.

## Support

- [Report a bug](https://github.com/tombiddulph/ReferenceSwitcher/issues/new?template=bug_report.yml)
- [Request a feature](https://github.com/tombiddulph/ReferenceSwitcher/issues/new?template=feature_request.yml)
- [Security policy](SECURITY.md)
- [Contributing guide](CONTRIBUTING.md)

## License

[MIT](LICENSE)
