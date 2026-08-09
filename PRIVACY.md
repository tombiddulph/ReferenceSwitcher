# Privacy

Reference Switcher does not collect telemetry, transmit data, make network requests, or include advertising.

The plugin reads `.csproj` and `Directory.Build.props` files beneath source roots explicitly configured by the user. It stores the following data in JetBrains Rider's local configuration:

- Configured local source-root paths.
- Package IDs and remembered local project paths.
- Paths of consuming and local projects for active switches.
- The original `PackageReference` XML needed to restore a switch.

This information remains on the local machine and is used only for project discovery, switching, and restoration. Remove configured roots and mappings through Rider settings, and restore or forget active switches through **Tools | Local References...**.

Questions can be submitted through the [GitHub issue tracker](https://github.com/tombiddulph/ReferenceSwitcher/issues).
