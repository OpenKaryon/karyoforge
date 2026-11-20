# Windows distribution

## Build and outputs

Build on Windows x64 with Python 3.10+, GNU Make and JDK 21. Set `JAVA_HOME` to
the JDK directory. A separate system Gradle, NSIS installation or Java runtime
for the installed IDE is not required.

```powershell
make package-windows
```

This runs source checks, builds the public plugin and branding resources, stages
the pinned standalone IDE and compiles a per-user installer. The output is
`build/distributions/KaryoForge-0.1.0-SNAPSHOT-windows-x64-Setup.exe`, with an
adjacent `.sha256` file. Compression can take several minutes on a local build.
No command installs or launches the interactive IDE.

| Command | Result |
| --- | --- |
| `make dist-windows` | Unpacked standalone IDE at `build/windows/app` |
| `make package-windows` | Windows installer executable and SHA-256 |
| `make zip-windows` | Portable ZIP and SHA-256 under `build/distributions` |
| `make verify-dist-windows` | Headless startup of the staged IDE using its bundled Java |

Without Make, use `python tools/tasks.py package-windows` or the corresponding
task name. `PYTHON` selects the build-tool interpreter when invoking Make.
`make build` continues to build the plugin ZIP, independently of product packaging.
Linux installer packaging is not implemented.

The build reuses verified SDK/PythonCore archives from Gradle's selected user
cache. Missing artifacts are downloaded from their pinned upstream URLs.
NSIS 3.13 and rcedit 2.0.0 are downloaded into `build/windows/downloads` and
verified before execution. The compiler is extracted below `build/windows/tools`;
it is not installed into Windows. The first invocation needs network access;
verified cached inputs can be reused offline. No compiler binary is committed.

`installer/windows-artifacts.json` records exact versions, URLs, hashes and the
bundled-plugin allowlist. Gradle exports resolved local paths into ignored
`build/windows/inputs.json`; no developer-specific path belongs in source.
Packaging fails if the artifact pins disagree with the Gradle platform/provider
versions, if a checksum differs or if a required runtime component is missing.

## Included runtime

The distribution contains IntelliJ Community IC-252.28539.97, its bundled
JetBrains Java Runtime, Python Community 252.28539.97, the public KaryoForge
plugin, final application icons and the splash resource overlay. Selected
platform plugins provide the Java/JSON platform baseline, Git, terminal, shell,
Markdown, YAML, TOML, TextMate, EditorConfig, properties and image support.
The precise list is in the artifact manifest.

The complete SDK plugin collection is not copied. Code With Me, shared-index
services, Marketplace licensing, bundled AI models and unrelated notebook/build
tool integrations are outside this distribution's allowlist. PythonCore helpers
are retained; its nested `helpers/testResources` directory is excluded because
it contains provider test fixtures rather than runtime helpers and introduces
unnecessary long installation paths.

The launcher is `bin/karyoforge.exe`. Packaging changes its icon and product
resource strings, product metadata and application resource references. The
platform build remains IC-252.28539.97. SDK JARs and the Gradle SDK cache remain
unchanged. Platform self-updates are disabled for this pinned distribution.

Python itself is not installed by Setup. After launch, open a project, choose
its local Python interpreter or create a virtual environment, then explicitly
install dependencies through Python Packages, requirements or standard project
installation actions. See [Python development](PYTHON.md).

## Installation and profiles

Run the generated Setup executable manually. It installs without administrator
rights, by default into `%LOCALAPPDATA%/Programs/OpenKaryon/KaryoForge`, creates
a Start menu shortcut named `KaryoForge` and registers an uninstaller for the
current user. Starting KaryoForge on the final installer page is an unchecked,
optional action.
The local test installer is unsigned.

The visible product name is `KaryoForge`, without a publisher suffix. OpenKaryon
remains the publisher metadata and secondary credit on the splash image and in
the native About dialog. Display labels do not determine installation paths,
profile locations or registry identity. An older public installation removes
its previous shortcut through its existing uninstaller during replacement.

The public distribution has separate profile locations:

- Settings and user-installed plugins: `%APPDATA%/OpenKaryon/KaryoForge/0.1`.
- Caches and logs: `%LOCALAPPDATA%/OpenKaryon/KaryoForge/0.1`.

The native launcher uses the `KARYOFORGE` environment-variable prefix. It does
not import development-sandbox or private-product settings automatically.
An unrelated nonempty installation folder is rejected. Reinstalling the public
product invokes its existing uninstaller before replacing the payload.
Uninstall uses a generated exact-file list and removes empty payload directories;
it does not recursively delete user-created files, settings, plugins or projects.
Close a running KaryoForge before replacing or uninstalling it.

The portable ZIP extracts to a `KaryoForge` directory and uses the same external
profile locations. It is portable as an application bundle, not as a profile
stored next to the executable. Choose a separate empty folder when installing
Setup alongside an unpacked ZIP.

## Notices and verification

Original SDK `LICENSE.txt`, `NOTICE.txt`, component-license files and Java Runtime
legal notices are preserved. `openkaryon-license` contains KaryoForge's Apache-2.0
license/notices, the NSIS license text and a component/source-artifact manifest.
KaryoForge's source license does not replace dependency licenses.

Tool tests exercise archive traversal/symlink rejection, filtering of provider
test fixtures, pinned input agreement, Gradle archive reuse and the boundary for
replacing an owned build stage. Extraction validates archive paths before writing.
Rebuilding replaces only `build/windows/app` with its public ownership marker.

`make verify-dist-windows` uses fresh disposable config/cache/log and AppData
directories, and the packaged
Java executable to run the platform's headless settings-index command. Acceptance
requires both public KaryoForge and native Python settings in its output. Logs
remain in ignored `build/windows/smoke-*` directories. It does not run Setup,
change Windows installation registration or establish interactive GUI acceptance.
The user performs the installer and editor smoke test separately.

See [developer commands](DEVELOPMENT.md), [branding](BRANDING.md) and
[executed validation](VALIDATION.md).
