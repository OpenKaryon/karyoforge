param([Parameter(Mandatory)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RunRoot)
if (-not (Test-Path -LiteralPath (Join-Path $root '.karyoforge-ui-run.json'))) { throw 'Missing VM run marker' }
$results = Join-Path $root 'results'
New-Item -ItemType Directory -Force -Path $results | Out-Null
$tests = Join-Path $root 'starter/out/ide-tests/tests'
if (Test-Path -LiteralPath $tests) {
    foreach ($file in Get-ChildItem -LiteralPath $tests -Recurse -File) {
        $relative = $file.FullName.Substring($tests.Length).TrimStart('\', '/')
        if ($relative -match '(^|[\\/])(config|system|plugins|temp)([\\/]|$)') { continue }
        if ($file.Extension -notin '.log','.png','.txt','.html') { continue }
        $target = Join-Path (Join-Path $results 'ide') $relative
        $resolved = [IO.Path]::GetFullPath($target)
        if (-not $resolved.StartsWith($results + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Artifact escapes run results' }
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($resolved)) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $resolved
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = Join-Path $root 'results.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
[IO.Compression.ZipFile]::CreateFromDirectory($results, $archive)

