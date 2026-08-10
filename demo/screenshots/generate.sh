#!/bin/sh
set -eu

ROOT="${1:-build/screenshot-demo}"
rm -rf "$ROOT"
mkdir -p "$ROOT/feed" "$ROOT/packages/Acme.Demo.Formatting" "$ROOT/source/Acme.Demo.Formatting" "$ROOT/source/Acme.Demo.Analyzers" "$ROOT/consumer/Acme.Demo.App"

cat > "$ROOT/global.json" <<'EOF'
{
  "sdk": {
    "version": "10.0.100",
    "rollForward": "latestPatch"
  }
}
EOF

cat > "$ROOT/packages/Acme.Demo.Formatting/Acme.Demo.Formatting.csproj" <<'EOF'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <PackageId>Acme.Demo.Formatting</PackageId>
    <Version>1.0.0</Version>
    <Authors>Acme Demo</Authors>
  </PropertyGroup>
</Project>
EOF

cat > "$ROOT/packages/Acme.Demo.Formatting/MessageFormatter.cs" <<'EOF'
namespace Acme.Demo.Formatting;

public static class MessageFormatter
{
    public static string Format(string message) => $"Package: {message}";
}
EOF

cat > "$ROOT/source/Directory.Build.props" <<'EOF'
<Project>
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <Authors>Acme Demo</Authors>
  </PropertyGroup>
</Project>
EOF

cat > "$ROOT/source/Acme.Demo.Formatting/Acme.Demo.Formatting.csproj" <<'EOF'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <PackageId>Acme.Demo.Formatting</PackageId>
    <Description>Generated local formatting library</Description>
  </PropertyGroup>
</Project>
EOF

cat > "$ROOT/source/Acme.Demo.Formatting/MessageFormatter.cs" <<'EOF'
namespace Acme.Demo.Formatting;

public static class MessageFormatter
{
    public static string Format(string message) => $"Local project: {message}";
}
EOF

cat > "$ROOT/source/Acme.Demo.Analyzers/Acme.Demo.Analyzers.csproj" <<'EOF'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>netstandard2.0</TargetFramework>
    <PackageId>Acme.Demo.Analyzers</PackageId>
    <IsRoslynComponent>true</IsRoslynComponent>
    <DevelopmentDependency>true</DevelopmentDependency>
  </PropertyGroup>
</Project>
EOF

cat > "$ROOT/source/Acme.Demo.Analyzers/GeneratedAnalyzer.cs" <<'EOF'
namespace Acme.Demo.Analyzers;

public sealed class GeneratedAnalyzer
{
}
EOF

cat > "$ROOT/consumer/Acme.Demo.App/Acme.Demo.App.csproj" <<'EOF'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Acme.Demo.Formatting" Version="1.0.0" />
  </ItemGroup>
</Project>
EOF

cat > "$ROOT/consumer/Acme.Demo.App/Program.cs" <<'EOF'
using Acme.Demo.Formatting;

Console.WriteLine(MessageFormatter.Format("Reference Switcher demo"));
EOF

cat > "$ROOT/NuGet.Config" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="Generated demo feed" value="$(cd "$ROOT/feed" && pwd)" />
  </packageSources>
</configuration>
EOF

dotnet pack "$ROOT/packages/Acme.Demo.Formatting/Acme.Demo.Formatting.csproj" -c Release -o "$ROOT/feed" --nologo
dotnet new sln -n Acme.Demo -o "$ROOT" --format sln --force
dotnet sln "$ROOT/Acme.Demo.sln" add \
    "$ROOT/consumer/Acme.Demo.App/Acme.Demo.App.csproj" \
    "$ROOT/source/Acme.Demo.Formatting/Acme.Demo.Formatting.csproj"
dotnet restore "$ROOT/Acme.Demo.sln" --configfile "$ROOT/NuGet.Config" --nologo

printf 'Generated demo at %s\n' "$(cd "$ROOT" && pwd)"
