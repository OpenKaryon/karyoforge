# Product branding

## Final artwork

The repository contains OpenKaryon's final KaryoForge application artwork in
`installer/branding`. These are runtime assets, supplied independently of design
source files, visual mockups, generator scripts, font binaries and conversations.
The build does not need a design checkout, image generator or network service.

| Asset | Role |
| --- | --- |
| `karyoforge.svg` | Application SVG, logical size 64 by 64 |
| `karyoforge_16.svg` | Small application SVG, logical size 16 by 16 |
| `karyoforge.ico` | Windows application artwork, multiple embedded sizes |
| `karyoforge_logo.png` | Splash image, 760 by 440 |
| `karyoforge_logo@2x.png` | High DPI splash image, 1520 by 880 |

The two SVGs share a 64 by 64 view box. Their intrinsic sizes differ so the small
icon occupies the correct UI space instead of expanding to the large icon size.
`META-INF/pluginIcon.svg` reuses the same artwork at the platform's plugin-icon
size of 40 by 40. Splash typography is already rendered into the PNGs; the product
does not install fonts.

## Resource overlay

`make branding` builds `build/branding/karyoforge-branding-0.1.0-SNAPSHOT.jar`.
The resource-only JAR contains the five assets, notices, a SHA-256 asset manifest
and generated `idea/IdeaApplicationInfo.xml`. It is separate from the installable
KaryoForge plugin ZIP: a normal plugin installation does not replace its host
IDE's application identity.

`tools/branding.py` reads metadata from the pinned Community SDK's `lib/app.jar`.
It verifies the product code and exact build, preserves upstream version,
essential plugins and other platform metadata, and replaces only the product
name, vendor, motto and image references. It resolves build placeholders using
the pinned build number and upstream metadata entry's timestamp. The platform
compatibility identity remains IC-252.28539.97.

The task reads the SDK and writes only below `build/branding`. It does not edit
cached JetBrains JARs. Inputs are an explicit five-file allowlist, with SVG
geometry, PNG dimensions and ICO header checks. No design directory is copied
into the overlay. The output manifest fingerprints the final asset bytes.

`make run` puts the branding JAR first on the development IDE's classpath and
enables resource-based application information. It disables manifest classpath
reset so bootstrap preserves that order. The development sandbox remains separate
from normal IDE settings and plugins. The same approved artwork appears as the
KaryoForge plugin icon in the plugin package.

Standalone Windows packaging applies the final ICO and product resource strings
to the staged `bin/karyoforge.exe`, preserving the SDK cache. It bundles the same
branding resource overlay. See [Windows distribution](WINDOWS_DISTRIBUTION.md).
The Gradle development launch continues to use the cached native launcher.

## Validation

`make test-branding` starts a fresh headless JVM using the overlay before the
SDK libraries. The probe reads native `ApplicationNamesInfo` and application
metadata, checks KaryoForge/OpenKaryon identity and the unchanged platform build,
and renders the small SVG through IntelliJ's SVG loader at 16 and 32 pixels.
It decodes both splash images and verifies that their resources came from the
branding JAR. It opens no IDE window and makes no interactive GUI acceptance claim.

Tool tests additionally check rejection of another platform build, preservation
of essential upstream metadata and an unchanged source SDK JAR. `make check`
includes these tests. `make branding` and `make test-branding` use the Python
executable running `tools/tasks.py`; direct Gradle invocations can select it with
`-PpythonCommand=/path/to/python`.

See [developer commands](DEVELOPMENT.md) and [executed validation](VALIDATION.md).
