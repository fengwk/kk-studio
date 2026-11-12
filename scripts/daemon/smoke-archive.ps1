[CmdletBinding()]
param([Parameter(Mandatory = $true)][string] $Archive)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) { throw "archive not found: $Archive" }
$name = [IO.Path]::GetFileName($Archive)
if ($name -notmatch '^kk-studio-daemon-v[0-9A-Za-z._+-]+-windows-amd64\.tar\.gz$') { throw "unexpected archive name: $name" }
$tar = Get-Command tar.exe -CommandType Application -ErrorAction Stop
$temp = Join-Path ([IO.Path]::GetTempPath()) ("kk-studio-daemon-smoke-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    & $tar.Source -xzf $Archive -C $temp
    if ($LASTEXITCODE -ne 0) { throw 'cannot extract daemon archive' }
    $binary = Join-Path $temp 'kk-studio-daemon.exe'
    foreach ($required in @($binary, (Join-Path $temp 'runtime\code-server\out\node\entry.js'), (Join-Path $temp 'ide-extension\extension.cjs'), (Join-Path $temp 'runtime-manifest.json'), (Join-Path $temp 'LICENSE'), (Join-Path $temp 'THIRD_PARTY_NOTICES'))) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "archive entry missing: $required" }
    }
    foreach ($forbidden in @('main.js', 'package.json', 'node_modules', 'native\lock-helper.js', 'delay.js', 'rg')) {
        if (Test-Path -LiteralPath (Join-Path $temp $forbidden)) { throw "Go archive unexpectedly contains $forbidden" }
    }
    $version = & $binary --version
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^kk-studio-daemon [0-9A-Za-z._+-]+$') { throw 'daemon --version failed' }
    Write-Output "archive smoke ok: $name ($version)"
}
finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
