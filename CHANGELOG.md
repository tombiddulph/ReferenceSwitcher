# Changelog

## 0.1.5

- Preserve the entire project file outside the selected reference element.
- Preserve the exact original `PackageReference` XML during restore.

## 0.1.4

- Select the `PackageReference` under the editor caret when invoking the context action.

## 0.1.3

- Add direct source-root configuration from the Local References dialog.

## 0.1.2

- Normalize generated XML line endings before writing through Rider's document API.
- Log document-write failures instead of allowing an uncaught IDE assertion.

## 0.1.1

- Accept SDK-style project files that include a UTF-8 byte-order mark.
- Log the underlying project-inspection failure for diagnostics.

## 0.1.0

- Initial Rider 2026.1 implementation.
- Package-to-project switching and deterministic restore.
- Analyzer references, discovery, compatibility checks, settings, and active-reference management.
