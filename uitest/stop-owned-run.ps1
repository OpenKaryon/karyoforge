param([Parameter(Mandatory)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RunRoot)
$marker = Join-Path $root '.karyoforge-ui-run.json'
$identity = Join-Path $root 'runner-process.json'
if (-not (Test-Path -LiteralPath $marker) -or -not (Test-Path -LiteralPath $identity)) { return }
$request = Get-Content -Raw -LiteralPath $marker | ConvertFrom-Json
$record = Get-Content -Raw -LiteralPath $identity | ConvertFrom-Json
if ($request.runId -ne $record.runId) { throw 'Owned runner identity differs from this run' }
$records = @($record)
$descendants = Join-Path $root 'owned-descendants.json'
if (Test-Path -LiteralPath $descendants) {
    # Windows PowerShell returns a JSON array as one pipeline object; enumerate its records explicitly.
    $decoded = Get-Content -Raw -LiteralPath $descendants | ConvertFrom-Json
    $records += @($decoded)
}
foreach ($entry in $records) {
    if (-not $entry -or $entry.runId -ne $request.runId) { continue }
    $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    if ($process.Path -ne $entry.executable -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$entry.startedTicks) {
        continue # A reused PID belongs to someone else and is never stopped.
    }
    # Stop each recorded identity separately; never infer descendants from potentially reused parent PIDs.
    & "$env:SystemRoot/System32/taskkill.exe" /PID $entry.pid /F | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $remaining = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($remaining -and -not $remaining.HasExited -and
            $remaining.StartTime.ToUniversalTime().Ticks -eq [long]$entry.startedTicks -and
            -not $remaining.WaitForExit(5000)) {
            throw 'Owned GUI process did not stop'
        }
    }
}

