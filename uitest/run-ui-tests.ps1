param([Parameter(Mandatory)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RunRoot)
$marker = Join-Path $root '.karyoforge-ui-run.json'
if (-not (Test-Path -LiteralPath $marker)) { throw 'Missing VM run ownership marker' }
$request = Get-Content -Raw -LiteralPath $marker | ConvertFrom-Json
$results = Join-Path $root 'results'
New-Item -ItemType Directory -Force -Path $results | Out-Null
$code = 1
$process = $null
$powerRequestActive = $false
try {
    $system = Get-CimInstance Win32_ComputerSystem
    if ($env:COMPUTERNAME -eq $request.controller -or $system.Model -notmatch 'Virtual|VMware|KVM|QEMU|HVM') {
        throw 'GUI tests may run only on a separate Windows VM'
    }
    if ((Get-Process -Id $PID).SessionId -eq 0) { throw 'The test account needs a logged-in interactive desktop' }
    if ([version](Get-CimInstance Win32_OperatingSystem).Version -lt [version]'10.0') { throw 'Windows 10 or newer is required' }
    if (-not (Test-Path -LiteralPath (Join-Path $request.ide 'bin/karyoforge.exe'))) { throw 'Missing public IDE launcher' }
    $java = Join-Path $request.ide 'jbr/bin/java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Missing bundled Java runtime in the public distribution' }
    & $java --version | Out-String | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'java-version.txt')
    if ($LASTEXITCODE -ne 0) { throw 'The bundled Java runtime could not start' }
    $python = $request.python
    if (-not $python) {
        $python = (& py -3 -I -c 'import sys;print(sys.executable)').Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Choose the VM base Python with KARYOFORGE_UI_PYTHON' }
    }
    if (-not [IO.Path]::IsPathRooted($python) -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw 'The VM Python must be an existing absolute executable path'
    }
    $version = & $python -I -c "import platform,sys;assert platform.python_implementation()=='CPython';assert sys.version_info>=(3,10);print(sys.version)"
    if ($LASTEXITCODE -ne 0) { throw 'CPython 3.10 or newer is required on the VM' }
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'roaming'),(Join-Path $root 'local'),(Join-Path $root 'temp') | Out-Null
    # These affect only this runner and its descendants, never the VM account's saved environment.
    $env:APPDATA = Join-Path $root 'roaming'
    $env:LOCALAPPDATA = Join-Path $root 'local'
    $env:TEMP = Join-Path $root 'temp'
    $env:TMP = $env:TEMP
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class KaryoForgeUiPower {
    [DllImport("kernel32.dll")]
    public static extern uint SetThreadExecutionState(uint flags);
    [DllImport("user32.dll", SetLastError=true)]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder value, int bytes, out int needed);
    [DllImport("user32.dll")]
    private static extern bool CloseDesktop(IntPtr handle);
    [DllImport("wtsapi32.dll", EntryPoint="WTSQuerySessionInformationW", SetLastError=true)]
    private static extern bool QuerySession(IntPtr server, int session, int infoClass, out IntPtr data, out int bytes);
    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr data);
    private static bool HasActiveUnlockedSession() {
        int session;
        using (var current = System.Diagnostics.Process.GetCurrentProcess()) { session = current.SessionId; }
        IntPtr data;
        int bytes;
        if (!QuerySession(IntPtr.Zero, session, 25, out data, out bytes) || data == IntPtr.Zero) return false;
        try {
            // WTSINFOEXW uses an eight-byte-aligned union. Read only its level-one prefix.
            // SessionFlags: 0 = locked, 1 = unlocked on the supported Windows 10/11 guests.
            // https://learn.microsoft.com/windows/win32/api/wtsapi32/ns-wtsapi32-wtsinfoex_level1_w
            return bytes >= 20 && Marshal.ReadInt32(data, 0) == 1
                && Marshal.ReadInt32(data, 8) == session
                && Marshal.ReadInt32(data, 12) == 0
                && Marshal.ReadInt32(data, 16) == 1;
        } finally { WTSFreeMemory(data); }
    }
    public static bool HasUnlockedDesktop() {
        // LockApp can cover Default without switching to the secure Winlogon desktop.
        if (!HasActiveUnlockedSession()) return false;
        var desktop = OpenInputDesktop(0, false, 1); // DESKTOP_READOBJECTS
        if (desktop == IntPtr.Zero) return false;
        try {
            var name = new StringBuilder(256);
            int needed;
            return GetUserObjectInformation(desktop, 2, name, name.Capacity * 2, out needed)
                && String.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
        } finally { CloseDesktop(desktop); }
    }
}
'@
    if (-not [KaryoForgeUiPower]::HasUnlockedDesktop()) {
        throw 'Unlock the Windows test account desktop before GUI acceptance'
    }
    if ([KaryoForgeUiPower]::SetThreadExecutionState([uint32]2147483651) -eq 0) {
        throw 'Could not keep the test guest awake for GUI acceptance'
    }
    $powerRequestActive = $true
    $notebookFixtures = Join-Path $request.payload 'notebook-fixtures'
    $notebookPython = ''
    $includeNotebook = -not $request.select.Count -or @($request.select | Where-Object { $_ -cmatch '^org\.openkaryon\.karyoforge\.uitest\.Notebook' }).Count -gt 0
    if ($includeNotebook -and (Test-Path -LiteralPath (Join-Path $notebookFixtures 'manifest.json'))) {
        $notebookRuntime = Join-Path $root 'notebook-runtime'
        & $python -I (Join-Path $notebookFixtures 'prepare_runtime.py') --fixtures $notebookFixtures --python-executable $python --runtime $notebookRuntime *>&1 |
            Out-String | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'notebook-runtime-setup.log')
        if ($LASTEXITCODE -ne 0) { throw 'The owned offline notebook runtime could not be prepared; review notebook-runtime-setup.log' }
        $notebookPython = Join-Path $notebookRuntime 'venv/Scripts/python.exe'
        Copy-Item -LiteralPath (Join-Path $notebookRuntime 'runtime.json') -Destination (Join-Path $results 'notebook-runtime.json')
    }
    $starter = Join-Path $root 'starter'
    New-Item -ItemType Directory -Force -Path $starter | Out-Null
    $classpath = (Join-Path $request.payload 'classes') + ';' + (Join-Path $request.runtime '*')
    $ideMemory = if ($request.ideMemoryMb) { [int]$request.ideMemoryMb } else { 1024 }
    if ($ideMemory -lt 768 -or $ideMemory -gt 4096) { throw 'Invalid IDE heap limit' }
    $arguments = @('-Xmx512m', "-Dkaryoforge.ui.ideMemoryMb=$ideMemory", '-Djava.awt.headless=false', '-Didea.disable.collect.statistics=true',
        '-Didea.suppress.statistics.report=true', '-Didea.headless.enable.statistics=false',
        '-Dkaryoforge.ui.vmVerified=true', "-Dkaryoforge.ui.root=$root", "-Dkaryoforge.ui.ide=$($request.ide)",
        "-Dkaryoforge.ui.python=$python", "-Dkaryoforge.ui.notebookPython=$notebookPython", "-Dkaryoforge.ui.fixtures=$(Join-Path $request.payload 'fixtures')",
        "-Dkaryoforge.ui.testPlugins=$(Join-Path $request.payload 'test-plugins')",
        '-cp', $classpath, 'org.junit.platform.console.ConsoleLauncher', 'execute',
        '--reports-dir', (Join-Path $results 'junit'), '--details=flat', '--disable-banner', '--fail-if-no-tests',
        '--config', 'junit.jupiter.execution.parallel.enabled=false',
        '--config', 'junit.jupiter.execution.timeout.default=30 m',
        '--config', 'junit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD')
    if ($request.select.Count) {
        foreach ($test in $request.select) {
            if ($test -cnotmatch '^org\.openkaryon\.karyoforge\.uitest\.[A-Za-z_][A-Za-z0-9_]*(#[A-Za-z_][A-Za-z0-9_]*)?$') { throw 'Invalid GUI selector' }
            $selectionFlag = if ($test.Contains('#')) { '--select-method' } else { '--select-class' }
            $arguments += @($selectionFlag, $test)
        }
    } else { $arguments += @('--scan-classpath', (Join-Path $request.payload 'classes')) }
    # java.exe argument files preserve spaces without shell interpolation or Start-Process string joining.
    $escaped = $arguments | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' }
    $argumentFile = Join-Path $root 'java.args'
    [IO.File]::WriteAllLines($argumentFile, $escaped, (New-Object Text.UTF8Encoding($false)))
    $process = Start-Process -FilePath $java -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $starter `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $results 'console.log') `
        -RedirectStandardError (Join-Path $results 'stderr.log')
    # Windows PowerShell can otherwise release the native handle before ExitCode is read.
    $null = $process.Handle
    $runnerStarted = $process.StartTime.ToUniversalTime()
    @{pid=$process.Id; startedTicks=$runnerStarted.Ticks; executable=$process.Path; runId=$request.runId} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'runner-process.json')
    @{computer=$env:COMPUTERNAME; model=$system.Model; python=$python; pythonVersion="$version"; ide=$request.ide; runId=$request.runId;
        notebookPython=$notebookPython; ideMemoryMb=$ideMemory; runnerMemoryMb=512; physicalMemoryBytes=$system.TotalPhysicalMemory; logicalProcessors=$system.NumberOfLogicalProcessors} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'environment.json')
    $owned = @{}
    $deadline = (Get-Date).AddMinutes($request.timeoutMinutes)
    do {
        if (-not [KaryoForgeUiPower]::HasUnlockedDesktop()) {
            throw 'The Windows test session locked or became inactive during GUI acceptance; unlock the desktop before restarting'
        }
        $queue = New-Object Collections.Generic.Queue[int]
        $queue.Enqueue($process.Id)
        while ($queue.Count) {
            $parent = $queue.Dequeue()
            foreach ($child in Get-CimInstance Win32_Process -Filter "ParentProcessId=$parent") {
                $candidate = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
                if (-not $candidate) { continue }
                try {
                    # Read each identity value once: a short-lived child can exit between getters.
                    $started = $candidate.StartTime
                    $executable = $candidate.Path
                    if ($started -and $executable -and -not $candidate.HasExited -and $started.ToUniversalTime() -ge $runnerStarted) {
                        $startedTicks = $started.ToUniversalTime().Ticks
                        # A PID reused after the CIM snapshot is not the observed child.
                        if (-not $child.CreationDate -or [Math]::Abs($child.CreationDate.ToUniversalTime().Ticks - $startedTicks) -gt 9) { continue }
                        $owned["$($candidate.Id)"] = @{pid=$candidate.Id; executable=$executable;
                            startedTicks=$startedTicks; runId=$request.runId}
                        $queue.Enqueue($candidate.Id)
                    }
                } catch [System.InvalidOperationException] {
                    # The child exited before its identity could be captured; do not record it.
                } catch [System.ComponentModel.Win32Exception] {
                    # An exited or inaccessible process cannot provide an owned identity.
                } finally {
                    $candidate.Dispose()
                }
            }
        }
        @($owned.Values) | ConvertTo-Json -Depth 4 |
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'owned-descendants.json')
        if ($process.WaitForExit(1000)) { break }
    } while ((Get-Date) -lt $deadline)
    if (-not $process.HasExited) { throw 'GUI suite exceeded the explicit timeout' }
    $process.WaitForExit()
    $code = $process.ExitCode
    if ($null -eq $code) { throw 'The GUI runner exited without a readable process exit code' }
} catch {
    $_ | Out-String | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'runner-error.txt')
} finally {
    if ($null -eq $code) { $code = 1 }
    if ($process) {
        try {
            & (Join-Path $PSScriptRoot 'stop-owned-run.ps1') -RunRoot $root
        } catch {
            $code = 1
            $_ | Out-String | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'cleanup-error.txt')
        }
    }
    Set-Content -Encoding ASCII -LiteralPath (Join-Path $results 'exit-code.txt') -Value $code
    if ($powerRequestActive) {
        [KaryoForgeUiPower]::SetThreadExecutionState([uint32]2147483648) | Out-Null
    }
}

