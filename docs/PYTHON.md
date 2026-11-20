# Python development

## Creating and opening projects

Choose **File > New > Project... > Python** to create a Python project. The Python
entry is provided by the bundled PythonCore plugin and uses its native project
wizard, content-root setup and Python module builder. Choose the project name,
location and local interpreter explicitly. KaryoForge does not maintain a second
project generator or a separate project metadata format.

For a directory with existing sources, choose **File > Open...** and select that
directory. Review the platform's trust prompt and window choice. Opening a
directory does not mean its dependencies have been installed or its interpreter
has been validated. Existing `main.py`, `requirements.txt`, `pyproject.toml` and
environment files remain the project's own files.

After opening, choose **Tools > KaryoForge > Use Project Python Environment...**
to select an existing local environment, **Create Python Environment...** to
create one, or **Choose Python Interpreter...** for another local interpreter.
These actions also work before a Python editor is active. If the project has
several modules, select the owning module explicitly. Configuring a Python module
does not replace another module's SDK or the project's existing SDK.

Use the platform's **Open Recent** entries to reopen an existing project. Module
roots, SDK references and run configurations use the platform's normal `.idea`
and module configuration files. PythonCore owns its wizard options; this does
not make its remote interpreters or additional environment managers part of
KaryoForge's accepted local CPython/venv workflow.

## Editor and runtime ownership

KaryoForge uses JetBrains Python Community support (`PythonCore`), pinned to
252.28539.97 with IntelliJ Community IC-252.28539.97. Python parsing, highlighting,
completion, navigation, formatting, inspections, run configurations, test consoles
and debugging belong to that provider. KaryoForge adds explicit module-level
interpreter setup. It does not implement another parser or test protocol.

Open a Python file belonging to a project module. If that module has no Python
interpreter, the editor offers **Choose interpreter...**. The same native chooser
is available under **Tools > KaryoForge > Choose Python Interpreter...** and through
Find Action. The active file's module is used when available; otherwise the
explicit Python action offers module selection in a multi-module project. Select an existing
interpreter or use the provider's explicitly selected environment setup options.

The choice is assigned to that module. KaryoForge does not replace the project
SDK or another module's SDK, auto-select an interpreter, install Python on startup
or rewrite shell configuration. Cancelling the selection adds no SDK assignment.
SDK definitions and run configurations remain in the platform's native stores.

The editor notification checks stored SDK metadata and local interpreter-file
existence without launching a process. An unavailable path or missing version
keeps a configuration warning visible. A ready status is not a successful runtime
probe. Non-Python files and files without a module receive no setup banner.
Only the missing-interpreter inspection covered by that banner is suppressed;
invalid-interpreter and other Python diagnostics remain active.

## Existing local environments

**Tools > KaryoForge > Use Project Python Environment...** lists local CPython
virtual environments belonging to the selected module or project root. A Python
file without an interpreter also offers **Use project environment...** when one
is found. The choice dialog shows the full interpreter path before applying it.
An empty result offers the native interpreter chooser.

Discovery checks only `.venv`, `venv`, `env` and `.env` directly under the module
content roots and project base directory. A candidate requires `pyvenv.cfg` and
an existing `Scripts/python.exe` on Windows, or `bin/python` on other systems.
A dotenv file named `.env` is not an environment. Discovery does not recursively
scan directories, launch interpreters, create an environment or assign an SDK.

After selection, PythonCore performs SDK setup and reads its version/paths.
KaryoForge reuses an existing local Python SDK with the same normalized invocation
path instead of registering another entry. Windows path comparisons ignore case;
resolving executable symlinks is deliberately avoided so separate virtual
environments retain separate SDK identities. Missing executables, failed setup
and cancellation leave the module's current interpreter unchanged.
Other modules and the project SDK retain their existing settings. Native SDK
persistence remains responsible for the saved interpreter definition.

## Creating a local environment

Use **Tools > KaryoForge > Create Python Environment...** or the missing-interpreter
banner. Choose a local base Python executable and a new absolute environment
directory; the default is `.venv` under the module's first content root. The parent
directory must already exist. Existing targets, including empty directories, are
rejected. The action runs CPython's isolated `venv` module with its bundled pip;
it does not download packages or change the base interpreter.

Creation runs in the background with output in the native Run window. Stop in
Run or cancel the background task to stop that operation's process tree. Failure
or cancellation preserves the module interpreter. A partial environment directory
is kept for inspection and must be removed manually or a different target chosen
before retrying. KaryoForge never recursively deletes an environment directory.

After successful creation, native Python SDK setup registers the environment.
It is assigned only to the chosen module, and only if the user has not changed
that module's interpreter while creation was running. Cancelling SDK setup keeps
the created environment available for explicit later selection. Existing terminal
and console sessions are not restarted.

## Packages and requirements

Open **View > Tool Windows > Python Packages** for the provider's installed
package list, package search, installation, upgrade and removal controls. Check
the interpreter selected in that window before making changes. These controls
belong to PythonCore and retain its repository and error handling UI.

**Tools > KaryoForge > Install Python Requirements...** installs a chosen
requirements file into the selected module's local virtual environment. The
confirmation identifies both full paths. The adapter refuses base Python and
remote SDKs, saves editor changes after confirmation, and uses the requirements
file's parent as its working directory so local paths have an explicit base.
Includes, constraints, markers and hashes are interpreted by pip itself.

Installation runs in the background with output and Stop in Run. It invokes the
selected absolute Python as `-I -m pip`, requires a virtual environment, disables
interactive input and version checks, and does not load pip configuration files
or inherited pip/Python environment overrides. Project requirements may explicitly
declare an index; otherwise pip uses its normal default index. Missing pip is an
error, not an automatic installation into another interpreter.

Another KaryoForge dependency operation cannot run against the same environment
concurrently. A changed module selection before startup aborts the operation;
changing it after startup never retargets an already running install. Failure or
cancellation leaves SDK choices unchanged, but pip may already have changed some
packages. Review output and installed packages before retrying. Refresh Python
Packages if its visible list is stale.

The installed-package refresh uses PythonCore's public compatibility bridge.
That bridge is deprecated in the pinned provider; its newer refresh entry points
are internal APIs and are deliberately not linked by KaryoForge. Strict API
verification remains enabled, and this boundary is covered by real install,
uninstall and failure checks against the exact supported provider build.

## Standard project installation

**Tools > KaryoForge > Install Python Project...** reads a chosen `pyproject.toml`
with the bundled TOML parser. It accepts standard `[project]` metadata or an
explicit `[build-system]` backend with its requirements. Inline/dotted TOML keys
are supported. A file containing only `[tool.*]` configuration is rejected;
project names written inside strings are not metadata. Opening the file does
not install anything or execute build code.

The install dialog identifies the project directory and interpreter, offers
editable installation and lists only declared `[project.optional-dependencies]`
extras. It retains pip's build isolation by default. Disable isolation only when
the required build backend is already installed in that environment. Metadata
is checked again before execution, including whether the selected extras still
exist. Invalid metadata or an unavailable backend produces an explicit error.

The selected module must use a local virtual environment. Pip owns build backend
execution, dependency resolution, standard wheels and editable wheels. A `src`
layout works through that project's backend and installed editable path; changing
source files is visible without reinstalling in editable mode. Ordinary installs
retain the installed copy until another install. KaryoForge does not rewrite
`pyproject.toml`, add another dependency lock format or run a tool-specific manager.

The operation shares the requirements action's output, Stop, environment guards
and native package refresh. Error dialogs include a bounded tail of process
output; the complete output remains in Run. Cancellation is not a package
rollback. Build backends can create their normal artifacts in the source project.

## Module consoles and terminals

**Tools > KaryoForge > Open Module Python Console** starts a new native Python
Console using the selected module's current local SDK. A ready base interpreter
or virtual environment can be used. The SDK is overridden only on that new
runner; saved console interpreter preferences remain unchanged. Native working
directory, startup script and console options still apply. Existing consoles
keep their original interpreter when the module SDK changes.
After native initialization, the action reveals and focuses the Python Console
tool window for the new session.

**Open Module Python Terminal** starts a new native terminal tab in the module's
first local content directory. This action requires a local virtual environment;
it rejects base SDKs instead of silently using an unrelated Python from shell
`PATH`. PythonCore owns activation and respects its **Activate virtualenv** option
in Terminal settings. With activation enabled, new tabs use the module's current
environment. Disabling it is an explicit opt-out. Existing terminals are not
restarted or reactivated, and user shell settings are not rewritten.
For plain Windows cmd shells, a new module tab conditionally queues its
environment's standard `activate.bat` when that environment is not already active.
This also recovers from an empty native activation cache after a helper timeout.
The activation opt-out is respected; custom cmd startup commands retain native
provider behavior.

Missing native SDK metadata is initialized through the provider's standard SDK
loading API before opening a session. The selected interpreter must be visible
to the platform's local VFS. No global `PATH`, PowerShell execution policy or base
Python installation is modified. Module SDK references and project roots use the
platform's normal persistence; reopening a saved project restores its SDK choice.

## Run, test and debug

Use the native **Run > Edit Configurations...** dialog for a Python script path
or module name, interpreter, working directory, arguments and environment values.
The selected interpreter must exist and the requested dependencies must already
be installed, or installed through a separate explicit environment action.

Choose unittest or pytest in the native Python test-runner settings. Use a test
method/function gutter action or its context menu to create a configuration for
that target. The native test tool window owns results, output and rerun actions.
pytest must be installed in the selected runtime; unittest is part of Python.

Add a line breakpoint and choose Debug for a Python configuration. The native
debugger owns suspension, frame variables, expression evaluation, continuation
and stopping the launched process. KaryoForge does not start test discovery or
user code merely because a project or Python file is opened.

## Usage-statistics compatibility

The branded runtime includes the OpenKaryon consent compatibility resource and
disables platform feature-usage collection/reporting. Native Python package
listeners and editor inspections can check permissions without creating user
confirmations or throwing `consentMap` errors. See [branding](BRANDING.md).

## Executed acceptance

Native headless tests check Python PSI/highlighting, cross-file function
resolution, local completion and formatting. A separate multi-module fixture
checks that Python assignment preserves a Java project SDK and another module.
These checks also cover cancellation, rejection of a non-Python SDK, notification
scope and preservation of invalid-SDK diagnostics.

Runtime acceptance requires an explicitly selected Python executable:

```sh
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r tools/python-test-requirements.txt
make test-python PYTHON_EXECUTABLE=".venv/Scripts/python.exe"
```

On Linux/macOS, use `.venv/bin/python` instead. Runtime dependencies are pinned
for repeatable acceptance, not installed by KaryoForge. `make test-ide` excludes
the runtime suite; it does not silently choose the host Python executable.

The runtime suite uses a disposable physical source directory containing spaces
and a controlled child environment. It executes a script and module with an
explicit environment value, produces configurations through real unittest/pytest
gutter contexts, and requires exactly one selected test result. A deliberately
failing neighbouring test must remain unexecuted. Debug acceptance stops at a
real breakpoint, evaluates a value, resumes and checks the child exit/output.
Cleanup owns only processes, consoles, breakpoints and fixtures created by the test.

Windows acceptance was executed with Python 3.14.6 and pytest 9.0.2. Other Python
versions and operating systems require their own runtime checks. This is a
Community-based Python feature set, not a claim of complete PyCharm Professional
parity, scientific notebook support or remote interpreter acceptance.

See [local validation](VALIDATION.md) and [developer commands](DEVELOPMENT.md).
