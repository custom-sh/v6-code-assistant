# V6 Code Assistant Plugin

An IntelliJ IDEA plugin providing code assistant tools based on V6 project standards.

## Features

### Project Management
- Visual configuration panel with one-click project template initialization
- Platform version upgrade engine: major version upgrades (full replacement with reserved-directory restoration) and patch version upgrades
- Build project dialog: version number automatically read from Server and incremented by default, then forcibly unified across all three ends (including unchecked modules)
- Parallel make build in an independent window (does not occupy IDEA memory, avoiding freezes and crashes), with real-time progress bar showing ✓/✗ marks and percentage
- make.bat copy injected with SUCCESS/FAILURE result markers, unaffected by `pause` and exit codes; window closes automatically on build success
- Artifacts packaged as zip per module (Server→lib.zip, Web→web.zip, PDA→pda.zip); the notification provides an "Open Output Directory" link
- Temporary files are automatically cleaned up when the build ends (success/failure/cancel)
- Field value constant sync (ColumnType): full sync, selected-text incremental sync, and custom field sync to frontend JS
- Check flag sync (CheckType): full sync and selected-text incremental sync to frontend JS

### Others
- Multi-end project configuration support (Server, Web, PDA)
- Chinese/English internationalization
- API Key stored encrypted (using the OS-native keychain)
- Right-click menu: one-click launch of Web/PDA dev servers

## Build

```bash
./gradlew buildPlugin
```

## Run Sandbox IDE

```bash
./gradlew runIde
```

## Development Environment

- JDK 17+
- IntelliJ IDEA 2023.1+
- Kotlin 1.9.22
