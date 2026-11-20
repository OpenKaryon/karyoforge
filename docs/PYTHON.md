# Python development

## Editor and runtime ownership

KaryoForge uses JetBrains Python Community support (`PythonCore`), pinned to
252.28539.97 with IntelliJ Community IC-252.28539.97. Python parsing, highlighting,
completion, navigation, formatting, inspections, run configurations, test consoles
and debugging belong to that provider. KaryoForge adds explicit module-level
interpreter setup. It does not implement another parser or test protocol.

Open a Python file belonging to a project module. If that module has no Python
interpreter, the editor offers **Choose interpreter...**. The same native chooser
is available under **Tools > KaryoForge > Choose Python Interpreter...** and through
Find Action while a module-owned Python file is active. Select an existing
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
