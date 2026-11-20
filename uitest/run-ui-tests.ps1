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
try {
    $system = Get-CimInstance Win32_ComputerSystem
    if ($env:COMPUTERNAME -eq $request.controller -or $system.Model -notmatch 'Virtual|VMware|KVM|QEMU|HVM') {
        throw 'GUI tests may run only on a separate Windows VM'
    }
    if ((Get-Process -Id $PID).SessionId -eq 0) { throw 'The test account needs a logged-in interactive desktop' }
    if ([version](Get-CimInstance Win32_OperatingSystem).Version -lt [version]'10.0') { throw 'Windows 10 or newer is required' }
    if (-not (Test-Path -LiteralPath (Join-Path $request.ide 'bin/karyoforge.exe'))) { throw 'Missing public IDE launcher' }
    $jdk = Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'Install JDK 21 on the VM before GUI acceptance' }
    $java = Join-Path $jdk.FullName 'bin/java.exe'
    $python = $request.python
    if (-not $python) {
        $python = (& py -3 -I -c 'import sys;print(sys.executable)').Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Choose the VM base Python with KARYOFORGE_UI_PYTHON' }
    }
    if (-not [IO.Path]::IsPathRooted($python) -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw 'The VM Python must be an existing absolute executable path'
    }
    $version = & $python -I -c 'import platform,sys;assert platform.python_implementation()=="CPython";assert sys.version_info>=(3,10);print(sys.version)'
    if ($LASTEXITCODE -ne 0) { throw 'CPython 3.10 or newer is required on the VM' }
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'roaming'),(Join-Path $root 'local'),(Join-Path $root 'temp') | Out-Null
    # These affect only this runner and its descendants, never the VM account's saved environment.
    $env:APPDATA = Join-Path $root 'roaming'
    $env:LOCALAPPDATA = Join-Path $root 'local'
    $env:TEMP = Join-Path $root 'temp'
    $env:TMP = $env:TEMP
    $starter = Join-Path $root 'starter'
    New-Item -ItemType Directory -Force -Path $starter | Out-Null
    $classpath = (Join-Path $request.payload 'classes') + ';' + (Join-Path $request.runtime '*')
    $arguments = @('-Xmx768m', '-Djava.awt.headless=false', '-Didea.disable.collect.statistics=true',
        '-Didea.suppress.statistics.report=true', '-Didea.headless.enable.statistics=false',
        '-Dkaryoforge.ui.vmVerified=true', "-Dkaryoforge.ui.root=$root", "-Dkaryoforge.ui.ide=$($request.ide)",
        "-Dkaryoforge.ui.python=$python", "-Dkaryoforge.ui.fixtures=$(Join-Path $request.payload 'fixtures')",
        '-cp', $classpath, 'org.junit.platform.console.ConsoleLauncher', 'execute',
        '--reports-dir', (Join-Path $results 'junit'), '--details=tree', '--disable-banner', '--fail-if-no-tests',
        '--config', 'junit.jupiter.execution.timeout.default=30 m',
        '--config', 'junit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD')
    if ($request.select.Count) {
        foreach ($test in $request.select) {
            if ($test -notmatch '^org\.openkaryon\.karyoforge\.uitest\.[A-Za-z0-9_]+$') { throw 'Invalid GUI class selector' }
            $arguments += @('--select-class', $test)
        }
    } else { $arguments += @('--scan-classpath', (Join-Path $request.payload 'classes')) }
    # java.exe argument files preserve spaces without shell interpolation or Start-Process string joining.
    $escaped = $arguments | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' }
    $argumentFile = Join-Path $root 'java.args'
    [IO.File]::WriteAllLines($argumentFile, $escaped, (New-Object Text.UTF8Encoding($false)))
    $process = Start-Process -FilePath $java -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $starter `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $results 'console.log') `
        -RedirectStandardError (Join-Path $results 'stderr.log')
    @{pid=$process.Id; startedTicks=$process.StartTime.ToUniversalTime().Ticks; executable=$process.Path; runId=$request.runId} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'runner-process.json')
    @{computer=$env:COMPUTERNAME; model=$system.Model; python=$python; pythonVersion="$version"; ide=$request.ide; runId=$request.runId} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'environment.json')
    $owned = @{}
    $deadline = (Get-Date).AddMinutes($request.timeoutMinutes)
    do {
        $queue = New-Object Collections.Generic.Queue[int]
        $queue.Enqueue($process.Id)
        while ($queue.Count) {
            $parent = $queue.Dequeue()
            foreach ($child in Get-CimInstance Win32_Process -Filter "ParentProcessId=$parent") {
                $candidate = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
                if ($candidate -and $candidate.StartTime -ge $process.StartTime -and $candidate.Path) {
                    $owned["$($candidate.Id)"] = @{pid=$candidate.Id; executable=$candidate.Path;
                        startedTicks=$candidate.StartTime.ToUniversalTime().Ticks; runId=$request.runId}
                    $queue.Enqueue($candidate.Id)
                }
            }
        }
        @($owned.Values) | ConvertTo-Json -Depth 4 |
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'owned-descendants.json')
        if ($process.WaitForExit(1000)) { break }
    } while ((Get-Date) -lt $deadline)
    if (-not $process.HasExited) { throw 'GUI suite exceeded the explicit timeout' }
    $process.Refresh()
    $code = $process.ExitCode
} catch {
    $_ | Out-String | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'runner-error.txt')
} finally {
    if ($process) {
        & (Join-Path $PSScriptRoot 'stop-owned-run.ps1') -RunRoot $root
    }
    Set-Content -Encoding ASCII -LiteralPath (Join-Path $results 'exit-code.txt') -Value $code
}

