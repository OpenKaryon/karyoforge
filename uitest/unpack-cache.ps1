param([Parameter(Mandatory)][string]$Archive, [Parameter(Mandatory)][string]$Target,
      [Parameter(Mandatory)][string]$Expected, [Parameter(Mandatory)][string]$Root)
$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
$targetPath = [IO.Path]::GetFullPath($Target)
if (-not $targetPath.StartsWith($rootPath + '\cache\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Cache target is outside the dedicated VM workspace'
}
if ((Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
    throw 'Transferred archive checksum differs from the controller'
}
if (Test-Path -LiteralPath $targetPath) { throw 'Refusing to overwrite an existing unverified cache' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
    foreach ($entry in $zip.Entries) {
        $name = $entry.FullName.Replace('/', '\')
        if ([IO.Path]::IsPathRooted($name) -or $name.Contains(':') -or (($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960) {
            throw 'Archive contains an absolute path, alternate stream or symbolic link'
        }
        $resolved = [IO.Path]::GetFullPath((Join-Path $targetPath $name))
        if (-not $resolved.StartsWith($targetPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Archive member escapes the owned cache'
        }
    }
} finally { $zip.Dispose() }
New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory($Archive, $targetPath)
@{sha256=$Expected; owner='org.openkaryon.karyoforge.uitest'} | ConvertTo-Json |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $targetPath '.karyoforge-ui-cache.json')

