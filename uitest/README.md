# Windows VM GUI acceptance

The GUI suite drives the public KaryoForge distribution with IDE Starter and
Driver pinned to IC-252.28539.97. Kotlin is restricted to `src/uiTest`; its test
runtime is separate from the Java product plugin. No GUI task runs an IDE on
the development desktop. Database, notebook, genomic and private setup dialogs
are outside this suite because the public distribution does not provide them.

## Build and run

`make gui-bundle` compiles and bundles the tests without opening any windows.
JDK 21, Python and the verified Gradle wrapper are required on the controller.
`make test-gui` requires explicit VM connection settings:

```powershell
$env:KARYOFORGE_UI_HOST = "test-vm-hostname"
$env:KARYOFORGE_UI_USER = "test-account"
$env:KARYOFORGE_UI_KEY = "C:/path/to/test-key"
$env:KARYOFORGE_UI_PYTHON = "C:/path/on/vm/python.exe"
make test-gui
```

Store real connection values outside the repository. The controller also accepts
`python tools/ui_vm.py --help`, `--select` with comma-separated public GUI class
names, `--timeout`, and explicit SSH/SCP executable paths. `--skip-build` reuses
existing inputs; normally the command builds the bundle and portable standalone
IDE before transfer. Large runtime/distribution archives are cached by SHA-256
on the VM; changing test code uses a separate payload archive.

The target must be a separate Windows 10/11 x64 VM with OpenSSH Server, JDK 21,
CPython 3.10+ and the test account logged into an unlocked desktop. An SSH shell
alone has no interactive desktop. The runner uses a unique limited scheduled
task in that account's session. Windows 7 is not accepted. The VM model and
computer identity are checked before starting GUI processes.

## Isolation and results

Each run owns a new `C:/KaryoForgeUi/runs/<id>` directory, fresh sample projects,
config/cache/log paths, AppData and temporary directories. Terminal/native console
preferences changed by tests belong to those profiles. The VM's saved global
Python, shell environment and the development machine's installed IDE are not
changed. Cache extraction verifies checksums and entry boundaries before writing;
existing unverified caches are never overwritten. No runner recursively removes
an existing workspace or kills processes by an IDE executable name.

Cleanup records the runner and descendant process identities with creation times
and executable paths. Only identities belonging to this run can be stopped; a
reused PID is ignored. Run directories and cached inputs remain for inspection.
Any manual cleanup must target the explicitly owned test directories.

JUnit XML, stdout/stderr, IDE logs, screenshots and thread-dump text are retrieved
under ignored `build/ui-test-results/<id>`. Settings, plugins, caches and heap
dumps are excluded from collected IDE artifacts. Zero discovered tests, skipped
tests, nonzero exits, timeouts and missing XML all fail acceptance. Compilation
and controller unit tests do not establish that a GUI scenario passed on a VM.

`DistributionUiTest` opens a real project, checks the About identity and rejects
`consentMap` failures in the owned IDE logs. Starter retains its normal failure
diagnostics. This checks the staged IDE; it does not claim installer-wizard or
pixel-by-pixel splash acceptance.

## Python project and environment scenarios

`PythonProjectUiTest` exercises the native Python project wizard, explicit
File/Open of an existing project and isolation between two Python modules.
`PythonEnvironmentUiTest` covers creating/selecting environments, rejected
overwrite, dialog cancellation, SDK restoration after an IDE restart, native
completion and typing indentation. Probe scripts are launched from the editor
and must create fresh results with the expected `sys.executable`.

The environment dialog exposes accessible names for its base executable and
destination fields. Selectors use those public UI names, native dialog titles
and standard Driver fixtures, with no production-only test hooks.

These scenarios have been compiled on the controller. Their VM execution is
pending a supported, accessible Windows test guest; Windows 7 is unsuitable.

## Dependency scenarios and offline inputs

`PythonDependenciesUiTest` installs and upgrades a small owned wheel through
requirements, opens Python Packages, removes that fixture through the native
action, checks visible pip failure details and cancels an owned slow build.
Its Python child must exit after Stop, and the module's interpreter must remain
selected after success, failure and cancellation.

`prepareUiFixtures` downloads the versions in `tools/python-test-requirements.txt`
from official PyPI into ignored build output and generates two small fixture
wheels. GUI installs use `--no-index` and that transferred wheelhouse. Fixture
preparation never installs packages into the controller's Python. The generated
manifest records input file hashes; no wheel binaries are committed.

## Python project installation

`PythonProjectInstallUiTest` installs the owned standard project through the
native file chooser and public installation dialog. A source edit must be
visible after an editable install and remain invisible after an ordinary wheel
install. The scenario selects a declared extra, keeps build isolation enabled
and verifies that the selected interpreter survives both operations.

A separate scenario selects tool-only TOML and checks that the visible error
rejects it without changing the file, installing the fixture or replacing the
module interpreter. These cases use the same offline backend as native tests.
