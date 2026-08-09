# Rider Local Reference Switcher — Implementation Proposal (v2)

> Historical design proposal. The implementation and public documentation are authoritative where they differ from this document.

> Revision note: this version incorporates review findings around MSBuild
> semantics (analyzers, source generators, target frameworks, transitive
> versions), IDE document handling, and an added Phase 0 spike. Sections
> marked **[new]** or **[revised]** did not exist, or differ materially,
> in v1.

## 1. Overview

Rider Local Reference Switcher is an open-source JetBrains Rider plugin that allows .NET developers to switch between NuGet `PackageReference` dependencies and local `ProjectReference` dependencies directly from Rider.

The primary objective is to remove the configuration and command-line overhead associated with tools such as DNT.

The intended workflow is:

1. A developer has a project containing a NuGet `PackageReference`.
2. The corresponding library is also checked out locally.
3. From Rider, the developer chooses **Use Local Project**.
4. The plugin locates or asks for the corresponding `.csproj`.
5. The `PackageReference` is replaced by a `ProjectReference`.
6. Rider reloads the affected project.
7. The developer can later choose **Restore Package Reference** to reverse the operation.

No repository-level configuration should be required.

---

## 2. Goals

Production-ready v1 should provide:

- Rider-native package/project switching.
- `PackageReference` → `ProjectReference`.
- `ProjectReference` → original `PackageReference`.
- Automatic discovery of local projects.
- Manual project selection when discovery fails.
- Persistent package-to-project mappings.
- Preservation of the original package reference.
- Central Package Management support.
- Correct handling of analyzer and source-generator packages. **[new]**
- Pre-flight compatibility checks before modifying anything. **[new]**
- Safe `.csproj` modification through Rider's document layer.
- Rider project refresh after changes.
- Cross-platform support for macOS, Windows and Linux.
- Clear handling of unsupported or ambiguous project configurations.
- Automated tests for reference transformation logic.

The overriding implementation constraint is fast delivery. Features that substantially increase complexity without being necessary for the core workflow should be deferred.

---

## 3. Non-Goals for v1

The following should explicitly not be part of v1:

- Visual Studio support.
- VS Code support.
- Standalone CLI.
- Automatic Git cloning.
- Package publishing.
- NuGet version management.
- Transitive dependency *replacement* (detection is in scope — see §8). **[revised]**
- Automatic modification of arbitrary MSBuild targets.
- Multi-project-to-single-package mapping.
- Legacy non-SDK-style project support.
- Solution-wide mass switching.
- Synchronising mappings between developers.

These can be considered after the Rider workflow is proven.

### Why `.csproj` mutation rather than a non-invasive import **[new]**

An alternative design would leave the `.csproj` untouched and inject a
gitignored `.props` file containing the `ProjectReference`, imported by
`Directory.Build.props`.

That approach has better Git ergonomics but requires the repository to
opt in by adding the import — which is precisely the configuration
overhead this project exists to remove. Direct `.csproj` mutation is
therefore chosen deliberately, and §20 (Git Safety) exists to mitigate
its main drawback.

This should be stated in the README so the trade-off is visible to users.

---

## 4. User Experience

### Initial Configuration

The plugin exposes a Rider settings page:

    Settings
      → Tools
        → Local References

The developer configures one or more directories containing local repositories:

    Local project roots

    ~/Projects
    ~/src

    [+ Add]
    [- Remove]

These directories are scanned recursively for `.csproj` files.

The plugin extracts information such as:

    Project:
      /Users/user/Projects/Messaging/src/Messaging.csproj

    ProjectName:
      Messaging

    PackageId:
      Company.Messaging

This produces a local project index.

---

## 5. Switching a Package to a Project

Given:

```xml
<PackageReference Include="Company.Messaging" Version="2.4.1" />
```

the developer sees the dependency normally in Rider.

The plugin provides an action:

    Use Local Project

The plugin searches its local project index for:

    PackageId == Company.Messaging

If exactly one project matches, it can present that project immediately.

For example:

    Company.Messaging

    Local project found:

    ~/Projects/Messaging/src/Company.Messaging.csproj

    [Use Local Project]

If multiple projects match, the developer selects one.

If no project matches, the developer is presented with a `.csproj` file picker.

The selected mapping is remembered.

---

## 6. Reference Transformation

The plugin captures the complete original `PackageReference` before changing anything.

For example:

```xml
<PackageReference Include="Company.Messaging"
                  Version="2.4.1"
                  PrivateAssets="all"
                  GeneratePathProperty="true" />
```

is replaced with:

```xml
<ProjectReference Include="../../Messaging/src/Company.Messaging.csproj" />
```

The original XML should be preserved in plugin state rather than reconstructed from only package ID/version.

This protects attributes such as:

- Version
- PrivateAssets
- IncludeAssets
- ExcludeAssets
- GeneratePathProperty
- Aliases
- conditions

Restoring the package can therefore reproduce the original reference.

### Anchoring the restore **[new]**

Storing the original XML alone is not sufficient. If the file has been
reformatted, or item groups reordered, re-inserting a saved XML blob can
land in the wrong place.

State should therefore record both:

- the original element XML, and
- an anchor identifying the owning `ItemGroup` (its index and `Condition`).

Restore replaces the `ProjectReference` element **in place**, reparsing
the stored XML into a new element rather than splicing text.

---

## 7. Non-Assembly Package Assets **[new]**

This is the highest-risk correctness gap in a naive transformation, and
must be handled in v1.

A NuGet package is not equivalent to its source project. Packages can
deliver analyzers, source generators, MSBuild `build/*.props` and
`*.targets`, content files, and native assets. A plain `ProjectReference`
reproduces none of these.

### Analyzers and source generators

Consumed as:

```xml
<PackageReference Include="Company.Generators"
                  PrivateAssets="all" />
```

The correct equivalent is **not** a plain `ProjectReference`:

```xml
<ProjectReference Include="../../Generators/Company.Generators.csproj"
                  OutputItemType="Analyzer"
                  ReferenceOutputAssembly="false" />
```

Emitting a plain `ProjectReference` produces a build that compiles but in
which the generator never runs. The failure is silent and extremely
confusing to diagnose.

Detection heuristics, applied to the *target* project:

    <IsRoslynComponent>true</IsRoslynComponent>
            or
    <DevelopmentDependency>true</DevelopmentDependency>
            or
    references Microsoft.CodeAnalysis.CSharp
            or
    targets netstandard2.0 and consumed with PrivateAssets="all"

Where detection is confident, emit the analyzer form. Where it is
ambiguous, ask:

    Company.Generators looks like an analyzer or source generator.

    Reference it as:

    ( ) Analyzer  (OutputItemType="Analyzer",
                   ReferenceOutputAssembly="false")
    ( ) Normal project reference

The chosen form is recorded in state so restore and subsequent switches
are consistent.

### MSBuild targets, content and native assets

If the target project's `.csproj` contains packaging items that ship
build logic — for example:

```xml
<None Include="build/Company.Messaging.targets"
      Pack="true"
      PackagePath="build/" />
```

then a `ProjectReference` will not import those targets, and the build
may differ from the packaged behaviour.

v1 should not attempt to replicate this. It should warn once:

    Company.Messaging ships MSBuild targets in its package.

    A project reference will not import them, so the build may
    behave differently from the published package.

    [Switch anyway]  [Cancel]

---

## 8. Target Framework and Dependency Compatibility **[new]**

Transitive dependency replacement remains a non-goal, but its
consequences must be surfaced rather than left to fail at restore.

### Target framework check

NuGet resolves the best-matching asset from a package. A
`ProjectReference` has no such flexibility: the referenced project must
target a framework compatible with the consumer, or restore fails with
NU1201.

Before switching, compare:

    consumer TargetFramework(s)
              vs
    local project TargetFramework(s)

If no compatible target exists, refuse:

    Cannot use local project for Company.Messaging.

    Application targets net8.0.
    Company.Messaging targets net9.0.

    No changes were made.

### Transitive version skew

If the package version being replaced pulls transitive dependencies at
one version, and the local checkout references them at another, the
result is NU1605 downgrade errors or silent version skew.

Example:

    NuGet:  Company.Messaging 2.4.1 → Company.Core 2.4.1
    Local:  Company.Messaging       → Company.Core 2.5.0

The plugin cannot resolve this, and should not try. It should detect the
difference and warn before switching, because the alternative is a
restore failure the developer has to reverse-engineer.

### Diamond references

If another package in the graph still depends on `Company.Messaging`, the
consumer may end up with both the project output and the package
assembly. Detection is a best-effort warning in v1; resolution is out of
scope.

---

## 9. Central Package Management

Central Package Management should be supported in v1 because it is common in modern .NET repositories.

For example:

```xml
<PackageReference Include="Company.Messaging" />
```

with:

```xml
<PackageVersion Include="Company.Messaging"
                Version="2.4.1" />
```

in `Directory.Packages.props`.

The plugin should modify only the consuming `.csproj`.

It should NOT remove the corresponding `PackageVersion` from `Directory.Packages.props`.

Therefore:

```xml
<PackageReference Include="Company.Messaging" />
```

becomes:

```xml
<ProjectReference Include="../../Messaging/src/Company.Messaging.csproj" />
```

while:

```xml
<PackageVersion Include="Company.Messaging"
                Version="2.4.1" />
```

remains untouched.

Switching back simply restores:

```xml
<PackageReference Include="Company.Messaging" />
```

An unreferenced `PackageVersion` entry is harmless, which is what makes
this simplification safe.

This considerably reduces the complexity of CPM support.

---

## 10. State Management **[revised]**

State should be local to Rider rather than committed into the repository.

Three types of state are required.

### Project Discovery Configuration

For example:

    ~/Projects
    ~/src

Stored as application-level Rider plugin settings.

### Reference Mappings

Application-scoped, so mappings are reused across solutions:

```json
{
  "Company.Messaging": {
    "project": "/Users/user/Projects/Messaging/src/Company.Messaging.csproj",
    "referenceKind": "normal"
  }
}
```

`referenceKind` is `normal` or `analyzer` (see §7).

### Active Switch State

Solution-scoped, and necessarily a **collection**: the same package can
be switched in several consuming projects simultaneously, and the Local
References window (§23) assumes this.

Keyed by `(consumingProject, packageId)`:

```json
{
  "activeSwitches": [
    {
      "project": "/Users/user/Projects/Application/Application.csproj",
      "packageId": "Company.Messaging",
      "localProject": "/Users/user/Projects/Messaging/src/Company.Messaging.csproj",
      "referenceKind": "normal",
      "itemGroupAnchor": { "index": 1, "condition": "" },
      "originalReference": "<PackageReference Include=\"Company.Messaging\" Version=\"2.4.1\" />"
    }
  ]
}
```

This is what makes **Restore Package Reference** reliable.

---

## 11. Avoiding Stale State

Persistent state introduces one important problem:

    Plugin switches reference
        ↓
    User changes .csproj manually
        ↓
    Plugin state no longer represents reality

The plugin must therefore treat the project file as authoritative.

Before restoring, verify that the expected `ProjectReference` still exists.

If it does not, do not blindly modify the file.

Instead report:

    Company.Messaging is no longer using the
    ProjectReference created by Local References.

    No changes were made.

The same principle applies to every modification:

> Plugin state describes previous operations; the MSBuild project is always the source of truth.

Branch switches and solution reloads should trigger revalidation of
active switches, marking any that no longer match as stale in the Local
References window rather than silently repairing them.

---

## 12. Project Discovery **[revised]**

For fastest delivery, project discovery should initially be filesystem based.

For each configured source root:

    find *.csproj

excluding `bin`, `obj`, `node_modules`, `.git`, `.vs` and `artifacts`.
Without these exclusions, a first-run scan of `~/src` is unpleasantly
slow and produces duplicate results from build output.

### Package identity resolution

Parsing the `.csproj` alone is not sufficient. `PackageId` is frequently
set centrally:

```xml
<!-- Directory.Build.props -->
<PropertyGroup>
    <PackageId>Company.$(MSBuildProjectName)</PackageId>
</PropertyGroup>
```

Resolution must therefore walk up the directory tree collecting
`Directory.Build.props` before falling back:

    PackageId (project, then inherited props)
        ↓
    AssemblyName
        ↓
    Project filename

Simple property functions and `$(MSBuildProjectName)` substitution should
be handled; arbitrary MSBuild evaluation should not be attempted in v1.
Where a property cannot be resolved, fall through to the next step rather
than guessing.

Fallback matches should be presented to the user rather than silently selected.

---

## 13. Discovery Cache

Scanning `~/Projects` recursively every time the user opens a context menu would be unnecessarily expensive.

Maintain an in-memory index:

    PackageId
       │
       ├── Project A
       └── Project B

Scan when:

- Rider starts/project opens.
- Source roots change.
- User manually selects Refresh Local Projects.

Filesystem watching can be added later if necessary.

For v1, explicit refresh plus startup indexing is considerably simpler and less error-prone.

---

## 14. Rider Architecture **[revised]**

Rider plugins can operate across two environments:

    ┌─────────────────────────────┐
    │ IntelliJ Platform           │
    │ Kotlin/JVM                  │
    │                             │
    │ UI                          │
    │ Actions                     │
    │ Settings                    │
    │ Notifications               │
    └──────────────┬──────────────┘
                   │
                   │ Rider protocol
                   │
    ┌──────────────▼──────────────┐
    │ ReSharper Backend           │
    │ .NET / C#                   │
    │                             │
    │ Project inspection          │
    │ Reference discovery         │
    │ Switching logic             │
    │ Rider project integration   │
    └─────────────────────────────┘

### Whether a backend part is required is an open question

v1 of this proposal assumed a backend. That assumption is the single
largest cost driver in the project and has not been validated.

A backend part requires the ReSharper SDK, an rd protocol model project,
generated protocol code, dual-process debugging, and a build pinned per
Rider release. It is also the main source of ongoing version-compatibility
maintenance.

It may not be necessary:

| Entry point | Layer | Backend needed? |
|---|---|---|
| `<PackageReference>` element in the `.csproj` editor | XML PSI, frontend | Probably not |
| NuGet tool window | Frontend | Probably not |
| Dependencies node in Solution Explorer | Backend-driven tree | Probably yes |

If a frontend-only implementation can deliver the core workflow, the
switching engine becomes Kotlin rather than C#, the ReSharper SDK
dependency disappears, and version compatibility becomes far cheaper.

This is resolved by the Phase 0 spike (§30). Architecture, repository
structure and language choice below are written assuming a backend, and
should be revised down if the spike says otherwise.

If the backend is retained, the implementation should minimise
communication between layers: Kotlin owns presentation, C# owns
.NET-specific logic.

---

## 15. Suggested Repository Structure

    rider-local-references/
    │
    ├── README.md
    ├── LICENSE
    ├── CHANGELOG.md
    │
    ├── src/
    │   │
    │   ├── RiderLocalReferences/
    │   │   ├── Actions/
    │   │   ├── Settings/
    │   │   ├── UI/
    │   │   └── Protocol/
    │   │
    │   └── RiderLocalReferences.Rider/
    │       ├── Discovery/
    │       ├── References/
    │       ├── Switching/
    │       ├── State/
    │       └── Protocol/
    │
    └── tests/
        └── RiderLocalReferences.Tests/
            ├── Discovery/
            ├── Switching/
            └── Fixtures/

Exact structure should follow the current JetBrains Rider plugin template rather than establishing a custom build structure unnecessarily.

---

## 16. Core Domain Model

Keep the switching logic independent from Rider where practical.

For example:

```csharp
public enum ReferenceKind { Normal, Analyzer }

public sealed record PackageReferenceInfo(
    string PackageId,
    string ProjectFile,
    string OriginalXml,
    ItemGroupAnchor Anchor);

public sealed record LocalProjectInfo(
    string ProjectFile,
    string PackageId,
    IReadOnlyList<string> TargetFrameworks,
    ReferenceKind SuggestedKind);

public sealed record ReferenceMapping(
    PackageReferenceInfo Package,
    LocalProjectInfo Project,
    ReferenceKind Kind);
```

A service can then expose operations conceptually similar to:

```csharp
Task<CompatibilityReport> CheckAsync(
    PackageReferenceInfo package,
    LocalProjectInfo project);

Task<SwitchResult> SwitchToProjectAsync(
    PackageReferenceInfo package,
    LocalProjectInfo project,
    ReferenceKind kind);

Task<SwitchResult> SwitchToPackageAsync(
    ReferenceMapping mapping);
```

`CheckAsync` returns the §8 compatibility findings so the UI can warn or
refuse before any file is touched.

This code should have no dependency on Rider.

That makes the most dangerous part of the plugin — modifying project files — straightforward to unit test.

---

## 17. Project File Modification **[revised]**

Avoid implementing project modification with string replacement.

The transformation needs to understand XML/MSBuild structure.

For example:

```xml
<ItemGroup Condition="'$(TargetFramework)' == 'net8.0'">

    <PackageReference
        Include="Company.Messaging"
        Version="2.4.1" />

</ItemGroup>
```

The replacement should remain inside the same `ItemGroup`:

```xml
<ItemGroup Condition="'$(TargetFramework)' == 'net8.0'">

    <ProjectReference
        Include="../../Messaging/Company.Messaging.csproj" />

</ItemGroup>
```

This preserves the surrounding MSBuild semantics.

Where practical, whitespace and formatting should also be retained.

### Writing through the IDE, not around it

v1 of this proposal specified atomic file writes. That is correct for a
CLI and wrong for an IDE: replacing a file on disk while it is open in an
editor triggers external-change reconciliation and discards undo history.

The rule should be:

    File open in an editor
        → modify through the platform document / PSI layer

    File not open
        → write atomically (temp file + rename)

Going through the document layer also gives VCS awareness, local history
and editor refresh without additional work.

The pure transformation logic (§16) still operates on strings and stays
independently testable; only the final commit differs.

---

## 18. Safety **[revised]**

A tool modifying `.csproj` files needs conservative behaviour.

Before changing anything:

1. Verify the file still exists.
2. Verify the `PackageReference` is declared **in this `.csproj`** and not injected by `Directory.Build.props`.
3. Verify the expected reference exists exactly once.
4. Verify the target project exists and is SDK-style.
5. Verify target framework compatibility (§8).
6. Determine the correct reference kind (§7).
7. Verify there isn't already an equivalent `ProjectReference`.
8. Calculate the relative path.
9. Perform the modification.
10. Commit through the document layer or atomically (§17).
11. Request Rider project refresh and restore.

If any assumption is ambiguous, fail without modifying the project.

For example:

    Unable to switch Company.Messaging.

    Multiple PackageReference elements were found
    with different MSBuild conditions.

    No changes were made.

Or:

    Unable to switch Company.Messaging.

    This PackageReference is declared in
    Directory.Build.props, not in Application.csproj.

    No changes were made.

### Expect refusals to be common

Step 3 will fire more often than it appears. Multi-targeted libraries with
per-TFM conditional `ItemGroup`s are exactly the population that gets
developed locally, so "exists exactly once" excludes a meaningful share of
real projects.

Refusing is still the right v1 behaviour, but this should be anticipated
as a leading bug report and as the first candidate for v1.1: handling the
multi-targeted case by replacing every matching element within a single
transaction.

---

## 19. Undo **[revised]**

With §17 in place, editor undo works naturally for files open in the
editor, because changes go through the document layer rather than around
it.

The more important v1 capability remains deterministic:

    Restore Package Reference

Because the original reference is persisted, this provides an
application-level undo that also works for closed files and across
sessions.

---

## 20. Git Safety

The plugin should make it obvious that `.csproj` has been modified.

It should not automatically revert references on Rider shutdown.

That behaviour would be dangerous because the user may have legitimate edits in the same file.

Instead, active local references can be surfaced in Rider:

    Local References

    ● Company.Messaging
      → ~/Projects/Messaging/...

This also helps prevent accidentally committing local project references.

A later release could integrate with commit checks.

---

## 21. Plugin Settings

Keep the settings page minimal:

    Local References

    Local source roots:

      /Users/user/Projects
      /Users/user/src

      [+] [-]

    [Refresh Projects]

    Discovered projects: 47

Potential future settings should not be added until required.

---

## 22. Context Actions

The core actions should be:

### Package reference

    Use Local Project

### Project reference managed by the plugin

    Restore Package Reference

### Tools menu

    Local References...

The latter opens a small management window showing active switches and discovered projects.

---

## 23. Local References Window

A production v1 should include a simple overview:

    Local References

    Active

    Company.Messaging
    Application.csproj
    → ~/Projects/Messaging/src/Company.Messaging.csproj

                    [Restore Package]

    Company.Telemetry
    Worker.csproj
    → ~/Projects/Telemetry/src/Company.Telemetry.csproj
      ⚠ stale — reference no longer present

                    [Forget]

    ─────────────────────────────────────────

    [Refresh Local Projects]

This becomes particularly useful when several references are switched simultaneously, and is where stale entries (§11) surface.

---

## 24. Testing Strategy

The majority of tests should target the transformation layer without launching Rider.

Fixture:

```xml
<Project Sdk="Microsoft.NET.Sdk">

  <ItemGroup>
    <PackageReference Include="Company.Messaging"
                      Version="1.2.3" />
  </ItemGroup>

</Project>
```

Operation:

    Switch Company.Messaging
        →
    ../Messaging/Messaging.csproj

Expected:

```xml
<Project Sdk="Microsoft.NET.Sdk">

  <ItemGroup>
    <ProjectReference Include="../Messaging/Messaging.csproj" />
  </ItemGroup>

</Project>
```

Tests should cover:

- Basic PackageReference.
- Version attribute.
- Version child element.
- Central Package Management.
- PrivateAssets.
- IncludeAssets.
- Conditional ItemGroup.
- Conditional PackageReference.
- Multiple ItemGroups.
- Existing ProjectReference.
- Duplicate PackageReference.
- Relative paths.
- Paths containing spaces.
- Windows paths.
- Unix paths.
- Restore operation.
- Round-trip preservation.

Additional cases from this revision: **[new]**

- Analyzer package → `OutputItemType="Analyzer"` form, and its round trip.
- Source generator project detection via `IsRoslynComponent`.
- `PackageReference` injected via `Directory.Build.props` → refusal.
- `PackageId` inherited from `Directory.Build.props`, including `$(MSBuildProjectName)`.
- Multi-targeted consumer, single-targeted local project.
- Incompatible TFM → refusal.
- Transitive version skew → warning, not refusal.
- Reformatted file between switch and restore (anchor resolution).
- Discovery excludes `bin`/`obj`.

A particularly valuable invariant is:

    original
       ↓
    switch-to-project
       ↓
    switch-to-package
       ↓
    original

The resulting package reference should be semantically identical to the starting reference.

---

## 25. Error Handling

Errors should be actionable.

Avoid:

    Failed to switch reference.

Prefer:

    Cannot use local project for Company.Messaging.

    The selected project no longer exists:

    /Users/user/Projects/Messaging/Messaging.csproj

Or:

    Two local projects publish Company.Messaging.

    Select the project you want to use.

Failures should never leave a partially modified `.csproj`.

---

## 26. Logging

Debug logging should capture:

- Discovery scans.
- Package/project matching.
- Compatibility check results.
- Reference transformations.
- Rider reload requests.
- State changes.
- Errors.

Do not log entire project files by default.

This will be particularly important for diagnosing Marketplace bug reports involving unusual MSBuild configurations.

---

## 27. Open-Source Repository

Use a permissive licence such as MIT.

The repository should contain:

    README.md
    LICENSE
    CONTRIBUTING.md
    CHANGELOG.md

The README should lead with the workflow rather than architecture:

    Developing a NuGet package locally?

    Right-click dependency
            ↓
    Use Local Project
            ↓
    Edit and debug local source
            ↓
    Restore Package Reference

A short GIF showing this workflow will probably explain the project more effectively than extensive documentation.

The README should also state plainly that the plugin modifies `.csproj`
files, and what it does not attempt to replicate (§7, §8).

---

## 28. Prior Art Check **[new]**

Before naming and publishing, check JetBrains Marketplace for existing
plugins in this space. The plugin ID must be unique, and if something
comparable already exists it is worth knowing whether to contribute to it
instead.

Half a day, done during Phase 0.

---

## 29. Delivery Plan

### Phase 0 — Spike **[new]**

Timeboxed to two days. Nothing else starts until these are answered.

**Q1. Can the action live entirely on the frontend?**

Try attaching an intention/context action to a `<PackageReference>`
element in the `.csproj` editor using XML PSI, with no backend part. If
that works, evaluate whether a frontend-only plugin covers the workflow.

Outcome: backend required, or not.

**Q2. Does an out-of-solution `ProjectReference` give real navigation and debugging?**

Add a `ProjectReference` to a `.csproj` outside the loaded solution, by
hand, and check whether Rider gives go-to-definition into source and
working breakpoints — or treats the output as an assembly reference.

This is the assumption most likely to invalidate the milestone in §31.
If navigation is degraded, the plugin must also add the project to the
`.sln`, which is a materially larger feature with its own cleanup story
and should be scoped into v1 explicitly.

**Q3. Does Rider pick up the change automatically?**

Modify a `.csproj` externally and observe whether Rider reloads and
restores, or whether an explicit refresh/restore request is needed.

Everything downstream of these three answers is comparatively
predictable.

---

### Phase 1 — Plugin Skeleton

Create the Rider plugin from the official JetBrains template, in the
configuration Phase 0 selected.

Establish:

- Kotlin frontend.
- C# backend (only if Q1 says it is required).
- Rider/ReSharper communication (as above).
- Build.
- Test project.
- GitHub Actions build.

Goal:

    Plugin installs and loads in Rider.

---

### Phase 2 — Switching Engine

Implement the Rider-independent core.

Support:

    PackageReference
          ↓
    ProjectReference

and:

    ProjectReference
          ↓
    PackageReference

including the analyzer reference form (§7).

Add comprehensive fixture tests.

This should be developed before substantial UI work.

---

### Phase 3 — Rider Integration

Determine the selected dependency/project and invoke the switching engine.

Add:

    Use Local Project...

Initially a file picker is sufficient.

This gives the first complete vertical slice:

    right-click
        ↓
    select .csproj
        ↓
    reference changes
        ↓
    Rider reloads

This should be the first development milestone.

---

### Phase 4 — State and Restore

Persist:

- Package → project mappings.
- Original PackageReference and item group anchor.
- Reference kind.
- Active switches (as a collection).

Add:

    Restore Package Reference

At this point the core workflow is complete.

---

### Phase 5 — Discovery

Add configurable source roots.

Scan `.csproj` files, with exclusions and `Directory.Build.props`
walk-up.

Build the PackageId → project index.

Change the workflow from:

    Use Local Project
          ↓
    file picker

to:

    Use Local Project
          ↓
    matching project automatically suggested

Keep the file picker as fallback.

---

### Phase 6 — Compatibility and Safety **[new/expanded]**

Add:

- Target framework compatibility checks.
- Transitive version skew detection and warnings.
- Analyzer/source-generator detection and prompt.
- MSBuild-targets warning.
- `Directory.Build.props`-injected reference refusal.
- Document-layer writes for open files.
- Stale-state revalidation on reload and branch switch.

---

### Phase 7 — Production Hardening

Add:

- Central Package Management tests.
- Conditional-reference handling.
- Cross-platform path tests.
- Better diagnostics.
- Settings UI.
- Active references UI.
- Rider version compatibility testing.

---

### Phase 8 — Marketplace Release

Prepare:

- Plugin metadata.
- Icons.
- README.
- Screenshots/GIF.
- Changelog.
- Marketplace description.
- GitHub release automation.

Submit and allow for review turnaround. JetBrains Marketplace submissions
are human-reviewed and first submissions are not same-day.

Publish v1.0.

---

## 30. Estimated Effort **[revised]**

For a developer experienced with C#/.NET but new to Rider plugin development.

The estimate now branches on the Phase 0 outcome.

| Area | Frontend-only | With backend |
|---|---:|---:|
| Phase 0 spike | 2 days | 2 days |
| Plugin bootstrap and learning | 2 days | 5 days |
| Switching engine | 1 day | 1 day |
| Tests | 1–2 days | 1–2 days |
| Rider actions/integration | 1–2 days | 3 days |
| State/restore | 1 day | 1 day |
| Discovery/indexing | 1–2 days | 1–2 days |
| Compatibility checks (§7, §8) | 2 days | 2 days |
| Settings/UI | 1–2 days | 2 days |
| Hardening/edge cases | 2–3 days | 3–4 days |
| Marketplace/release | 1 day | 2 days |
| **Total** | **15–19 days** | **23–26 days** |

The v1 figure of 10–14 days understated bootstrap cost, allocated nothing
to MSBuild compatibility work, and did not account for Marketplace review
turnaround.

If Phase 0 Q2 comes back negative and solution membership must be
managed, add a further 3–4 days to either column.

A usable internal build should still be achievable around day 6–8.

---

## 31. Recommended First Milestone

Do not start with discovery, settings or polished UI.

After Phase 0, the first milestone should prove one complete operation:

    Open Rider

        ↓

    Right-click PackageReference

        ↓

    "Use Local Project..."

        ↓

    Select .csproj

        ↓

    PackageReference replaced

        ↓

    Rider recognises ProjectReference

        ↓

    Go-to-definition enters local source

        ↓

    "Restore Package Reference"

        ↓

    Original dependency restored

The go-to-definition step is only a valid criterion if Phase 0 Q2
confirmed it. If it did not, the milestone must include solution
membership.

If this works reliably, the largest technical uncertainties have been eliminated.

Everything after that primarily improves the developer experience.

---

## 32. Future Roadmap

Once v1 is established, useful candidates include:

### Multi-targeted reference handling

Replace every matching `PackageReference` across conditional `ItemGroup`s
in one transaction, removing the most common v1 refusal.

### Bulk switching

    Use all available local projects

Useful when an application consumes many internal packages.

### Automatic matching

Immediately recognise local checkouts without requiring mappings.

### Git integration

Warn before committing a `.csproj` containing active local references.

### Version awareness

Compare:

    NuGet: Company.Messaging 2.4.1

    Local:
      PackageVersion = 2.5.0-preview

and surface the difference.

### Clone Repository

Associate package IDs with Git repositories and offer:

    Local source not found.

    Clone Company.Messaging?

### Solution/workspace awareness

Prefer projects already open in Rider.

### DNT compatibility

Optionally import existing `switcher.json` configurations for developers already using DNT.

This would provide a straightforward migration path without making DNT configuration part of the plugin's normal workflow.

---

## 33. Success Criteria

v1 is successful if a developer can install the plugin and, without changing repository configuration:

1. Configure where local source repositories live.
2. Open an ordinary .NET solution.
3. Select a NuGet dependency.
4. Switch it to its local project.
5. Build/debug/navigate into that project normally.
6. Restore the original NuGet reference.
7. Repeat the operation without selecting the project again.

And, equally importantly:

8. Receive a clear refusal, with no file modified, whenever the project structure is one the plugin cannot handle correctly.

The core design principle should remain:

> **Reference switching should feel like an IDE operation, not a build-system workflow.**

DNT demonstrates that PackageReference/ProjectReference switching is useful. This project should focus on making that workflow effectively invisible to the developer — and on being visibly conservative in the cases where invisibility would be wrong.
