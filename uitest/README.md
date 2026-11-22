# Windows VM GUI acceptance

The GUI suite drives the public KaryoForge distribution with IDE Starter and
Driver pinned to IC-252.28539.97. Kotlin is restricted to `src/uiTest`; its test
runtime is separate from the Java product plugin. The bundle includes the
pinned SDK's Performance Testing plugin as test-only data. Each owned profile
installs that plugin to provide Driver's server bridge; the public IDE archives
do not include it. No GUI task runs an IDE on
the development desktop. Database, genomic and private setup dialogs are outside this suite because the
public distribution does not provide them. Notebook scenarios cover the public
local Python implementation.

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
names or `org.openkaryon.karyoforge.uitest.ClassName#methodName` for a targeted
rerun, `--timeout`, and explicit SSH/SCP executable paths. Selectors are validated
before connecting to the VM. JUnit logs individual outcomes as scenarios finish.
SSH transport requires OpenSSH Server. `--skip-build` reuses
existing inputs; normally the command builds the bundle and portable standalone
IDE before transfer. Large runtime/distribution archives are cached by SHA-256
on the VM; changing test code uses a separate payload archive.

The target must be a separate Windows 10/11 x64 VM with the test account logged
into an unlocked desktop. The full suite and notebook selectors require Windows
x64 CPython 3.14 for their locked wheelhouse. Explicit Python-only selectors can
still use the supported CPython 3.10+ base runtime. An SSH shell
alone has no interactive desktop. The runner uses a unique limited scheduled
task in that account's session. Windows 7 is not accepted. The VM model and
computer identity are checked before starting GUI processes. Finish Windows
first-login setup and close system prompts before running acceptance. Driver
and its event bus communicate within the guest; external network firewall
permission is not required.

## Isolation and results

Each run owns a new `C:/KaryoForgeUi/runs/<id>` directory, fresh sample projects,
config/cache/log paths, AppData and temporary directories. Terminal/native console
preferences changed by tests belong to those profiles. The VM's saved global
Python, shell environment and the development machine's installed IDE are not
changed. Before launch, the runner checks that the account's input desktop is
unlocked and that Windows reports an active unlocked session through WTS.
The session check also runs during acceptance: a lock or disconnection stops
the owned run instead of sending input behind LockApp. A desktop named Default
alone does not establish that the lock-screen overlay is absent. The runner
holds a thread-scoped Windows execution-state request to prevent
automatic sleep and display shutdown during acceptance, then releases it on
completion. Saved power plans and sign-in policies are unchanged. Windows login
and initial account setup must be completed manually before running the suite.
Cache extraction verifies checksums and entry boundaries before writing;
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
A scheduled task that exits without the runner's completion marker is recorded
as a startup failure, including its native task result, and diagnostics are
collected immediately.

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

Readiness checks observe the selected module's native SDK home/version and
project indexing state. Package operations must expose a new native process
with the expected title and reach termination; a previous completed operation
cannot satisfy the wait. The matching native background task must also finish
its package refresh before the next action. Background repository refreshes may
continue after an interpreter becomes usable and do not substitute for these
checks. Script execution, imports and debugger assertions still verify the resulting behavior.

Generated probe files are resolved relative to their actual module content root
after GUI Synchronize, including projects with multiple modules and Windows paths
containing spaces. Completion opens through the registered `CodeCompletion`
action. Its assertions read exact native lookup names because
the visible renderer splits highlighted prefixes into separate text fragments.
Selection and code entry continue through the GUI. Wait descriptions and
completion messages are included in the runner log for failure diagnosis.
Editor typing and shell command entry use the guest GUI clipboard so input does
not depend on the account's keyboard layout or Windows Robot punctuation support.
Real Enter key events still exercise Python indentation and terminal command
execution. Environment creation waits for both metadata and the Windows
executable before observing native SDK assignment.

Acceptance requires completed GUI execution on the supported guest; compilation
alone does not establish that these workflows pass.

## Dependency scenarios and offline inputs

`PythonDependenciesUiTest` installs and upgrades a small owned wheel through
requirements, opens Python Packages, removes that fixture through the native
action, checks visible pip failure details and cancels an owned slow build.
Its Python child must exit after Stop, and the module's interpreter must remain
selected after success, failure and cancellation.

Requirements confirmations are checked against the native text document so
visual line wrapping cannot split the selected Windows path. A pip failure must
show its exit status in the dialog and its actual diagnostic in the Run console.
Cancellation uses the operation's accessible Stop button. Package removal selects
the native Python Packages tree through its accessible cell labels so the
assertion and Delete action refer to the actual installed fixture row.

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

## VMware Tools transport and small guests

VMware Workstation guests with running VMware Tools can use guest operations
instead of SSH. Set `KARYOFORGE_UI_TRANSPORT=vmware`, `KARYOFORGE_UI_VMX` to the
existing VM configuration and `KARYOFORGE_UI_USER` to the exact guest account.
Provide `KARYOFORGE_UI_GUEST_PASSWORD` in the controller process environment;
encrypted VMs additionally need `KARYOFORGE_UI_VM_PASSWORD`. Windows Hello PINs
do not authenticate guest operations. Credentials are never written into run
requests or artifacts, and transport errors redact password values.

The same separate-VM and interactive-session checks apply to both transports.
Guest operations only replace remote commands and file transfer; scheduled
tasks still launch GUI acceptance inside the logged-in test account's desktop.
`--vmrun` overrides the default Workstation executable location. Guest file
operations use native Windows path separators. Archives have stable ZIP metadata,
so refreshing input timestamps does not invalidate verified runtime caches;
changing file contents still produces a new archive identity.

The Starter adapter launches the public `bin/karyoforge.exe` directly and uses
the branded `KARYOFORGE_VM_OPTIONS` and `KARYOFORGE_JDK` environment variables.
Patched VM options belong to the run directory; distribution binaries and saved
user configuration are not renamed or rewritten. The Windows runner retains
the native Java process handle until its exit status is read. Missing exit
codes fail acceptance instead of producing an empty completion marker.

The runner uses Java bundled with the tested IDE, avoiding a separate guest JDK
installation. Tests execute sequentially with a 512 MiB runner heap and a 1024
MiB IDE heap by default. `--ide-memory` can select 768–4096 MiB. Environment
artifacts record these limits, guest RAM and virtual CPU count so a successful
run can be evaluated against its actual resources. Four GiB guests still need
space for Windows, native IDE allocations and Python subprocesses. Cold SDK
generation can justify an extended full-suite timeout such as
`python tools/ui_vm.py --timeout 240`; each IDE scenario retains its own bound.

## Python test runners and debugger

`PythonExecutionUiTest` selects unittest or pytest in native settings and runs
an individual method, its class, its file, its folder and the last configuration
again through editor/project-view shortcuts. Fresh append-only fixture output
checks which tests actually ran. The native results panel must be visible, and
its test model must contain exactly the expected number of passed leaf tests.
The Testing panel's runner is selected by its exact name.
pytest comes from the transferred pinned wheelhouse.

The debugger scenario toggles a line breakpoint in the editor and invokes the
registered `DebugClass` context action independently of the keyboard map. It
checks the suspended local variable and evaluates it through Evaluate
Expression. The result file must remain absent until Resume. Each scenario
also verifies the selected module interpreter through an actual script run.

## Python consoles and Windows shells

`PythonSessionsUiTest` runs separately with cmd and PowerShell selected through
the fresh profile's Terminal settings. Each real shell must resolve `python`
to the selected environment, start in the module content directory and expose
the matching `VIRTUAL_ENV`. After the module SDK changes, the existing terminal
must retain its interpreter and a new terminal must activate the new one.

Native Python consoles evaluate probes at their prompts. Each probe explicitly
focuses the input editor and moves its caret to the end before pressing Enter.
Readiness requires a new live native console process, its visible tool window,
and the history/prompt
editors before input. The native execute handler must be enabled and able to
execute; the SDK fixture's panel-count check alone precedes the RPC connection. The new console must use the new SDK while the original
console process remains alive with its old executable and creation identity.
Terminal readiness reads the native screen buffer, preserving text independently of per-glyph painting;
rendered line wraps are ignored only for this readiness check. Script probes
still assert exact executable, working directory and virtual-environment paths.
Session teardown waits for the owned native Python interpreter updates to
finish before disposing the project, including background skeleton generation.
PowerShell uses a process-scoped execution-policy argument in this test profile; no saved Windows policy or global shell setting changes.

## Process cleanup acceptance

Windows controller tests launch disposable hidden sleepers to exercise the
actual PowerShell cleanup script without opening an IDE. They verify that
creation-time and executable mismatches survive, cross-run ownership is rejected,
multiple recorded descendants can be stopped after the runner exits, and
unrelated processes remain alive. Windows PowerShell JSON arrays are explicitly
enumerated before each process identity is checked. Termination targets each
recorded process separately, without traversing a parent-PID tree. An actual
unrecorded child fixture must survive when its recorded parent is stopped. These checks validate process ownership independently
of the guest's graphical session.

The runner reads each descendant's start time and executable once before
recording its identity. Terminated or inaccessible children are ignored.
Native creation times must match the observed CIM creation time within its
microsecond precision, rejecting a PID replaced after the CIM snapshot.

## Notebook file scenarios

The notebook fixture bundle is separate from ordinary Python project fixtures.
The guest verifies its manifest and all 28 locked wheel hashes before creating
an owned per-run notebook virtual environment. Pip uses only those offline
wheels with required hashes; the VM base Python and its global packages are
unchanged. Runtime versions and setup output accompany the run's results.
No notebook test depends on a copied host virtual environment, global kernelspec
or a Jupyter server.

`NotebookFilesUiTest` creates a notebook through the native save chooser and
checks cancellation. It also opens an owned notebook with saved outputs and code that
would create a marker if executed. It checks passive output display, paging,
unchanged JSON and absence of that marker. A second scenario repairs malformed
source through the native Text editor, saves it and returns to the refreshed
Notebook view. These cases require the same separate unlocked Windows VM as
the Python suite. Build/compile success alone is not notebook GUI acceptance.

`NotebookCellEditingUiTest` types into a native Code cell and exercises Save,
keyboard Undo/Redo and actual notebook bytes. Its controls scenario rejects a
conversion with saved results, clears outputs explicitly, changes cell type,
adds/moves/deletes a Raw cell, restores it with native Undo and checks locked
controls in a separate notebook. These tests use synthetic owned notebooks.

`NotebookRuntimeUiTest` checks passive dependency state, a module environment
missing notebook packages, explicit ready-Python selection, unchanged module SDKs
and persisted independent workspace choices after reopening. Its execution case
checks ordered Run All, skipped non-Code cells, Stop, a retained execution
counter, a fresh Restart namespace and explicit Shutdown.

`NotebookInputOutputsUiTest` submits ordinary input and masked getpass through
the nonmodal UI, verifies no response echo/persistence and displays local PNG,
plain text and retained passive HTML. A real execution counter proves keyboard
Undo/Redo restores saved output without executing code. Input cancellation
prevents a later queued marker from running, including after editor reopening.
All input values are synthetic test data, and every project/runtime/profile is
owned by the run.
