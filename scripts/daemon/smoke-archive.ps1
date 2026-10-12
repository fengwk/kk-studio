# Host smoke for one staged Windows daemon archive.
# Extracts the archive and runs its bundled node.exe. It does not download, publish,
# or register a scheduled task. A non-Windows host or another platform fails.
param(
    [Parameter(Position = 0)]
    [string]$Archive
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Error "archive smoke failed: $Message"
    exit 1
}

if ($Archive -eq '-h' -or $Archive -eq '--help' -or $Archive -eq '') {
    Write-Output 'Usage: powershell -File scripts/daemon/smoke-archive.ps1 <archive.tar.gz>'
    if ($Archive -eq '') { exit 2 }
    exit 0
}
if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) {
    Fail "archive is not a readable file"
}
if ($env:OS -ne 'Windows_NT') {
    Fail 'this Windows archive smoke only runs on Windows'
}

$name = Split-Path -Leaf $Archive
if ($name -notmatch '^kk-studio-daemon-v[0-9A-Za-z._-]+-windows-amd64\.tar\.gz$') {
    Fail "unsupported archive name; this smoke only runs windows-amd64: $name"
}
$arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
if ($arch -ne 'X64') {
    Fail "archive targets windows-amd64 but this host is $arch"
}

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("kk-studio-daemon-smoke-" + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $root | Out-Null
$previousPath = $env:PATH
try {
    tar -xzf $Archive -C $root
    if ($LASTEXITCODE -ne 0) { Fail 'cannot extract the archive' }
    $node = Join-Path $root 'runtime\code-server\lib\node.exe'
    $entry = Join-Path $root 'runtime\code-server\out\node\entry.js'
    $main = Join-Path $root 'main.js'
    foreach ($required in @($node, $entry, $main)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            Fail "archive is missing $required"
        }
    }
    $native = Get-ChildItem -LiteralPath (Join-Path $root 'runtime\code-server') -Recurse -Filter '*.node' -File
    $native = @($native | Where-Object { $_.FullName -notmatch 'darwin-|linux-|win32-arm64|win32-ia32' })
    if ($native.Count -eq 0) { Fail 'code-server native modules are missing' }
    $loaded = & $node -e "const {createRequire}=require('node:module'); const {join}=require('node:path'); const requireNative=createRequire(join(process.argv[1],'package.json')); for (const modulePath of process.argv.slice(2)) requireNative(modulePath); process.stdout.write('code-server native modules ok ' + process.argv.slice(2).length)" -- (Join-Path $root 'runtime\code-server') @($native.FullName)
    if ($LASTEXITCODE -ne 0 -or $loaded -notmatch '^code-server native modules ok ') {
        Fail "frozen code-server native modules failed to load: $loaded"
    }
    $runtimeVersion = & $node --version
    if ($LASTEXITCODE -ne 0 -or $runtimeVersion -notmatch '^v24\.') {
        Fail 'frozen runtime must be Node 24.x'
    }
    $package = Get-Content -LiteralPath (Join-Path $root 'package.json') -Raw | ConvertFrom-Json
    $manifest = Get-Content -LiteralPath (Join-Path $root 'runtime-manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.daemonVersion -ne $package.version -or $null -eq $manifest.codeServer.'windows-amd64') {
        Fail 'runtime manifest must match the package version and host platform'
    }
    # All daemon checks below use the bundled runtime without an installed Java/Node on PATH.
    $env:PATH = Join-Path $root 'empty-path'
    New-Item -ItemType Directory -Path $env:PATH | Out-Null
    $version = & $node $main --version
    if ($LASTEXITCODE -ne 0 -or $version -ne "kk-studio-daemon $($package.version)") {
        Fail "--version failed: $version"
    }
    $help = & $node $main --help
    if ($LASTEXITCODE -ne 0 -or ($help -join "`n") -notmatch '--check-config') {
        Fail '--help did not expose the daemon CLI contract'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'node_modules\@koromix\koffi-win32-x64'))) {
        Fail 'the Windows koffi prebuilt is missing'
    }
    # Native creation verifies owner-only ACLs; subsequent overwrites preserve those ACLs.
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $probe = Join-Path $root 'native-probe.mjs'
    [System.IO.File]::WriteAllText($probe, @'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
const [root] = process.argv.slice(2)
const native = await import(pathToFileURL(join(root, 'native', 'index.js')).href)
const configRoot = join(root, 'config')
await native.ensurePrivateDirectory(configRoot)
await native.writePrivateFile(join(configRoot, 'daemon.token'), 'smoke-token')
await native.writePrivateFile(join(configRoot, 'daemon.json'), '{"studioUrl":"http://127.0.0.1:59999"}')
const directory = join(root, 'private-data')
await native.ensurePrivateDirectory(directory)
const lock = await native.acquireDirectoryLock(directory)
try {
  let refused = false
  try {
    const second = await native.acquireDirectoryLock(directory)
    await second.close()
  } catch {
    refused = true
  }
  if (!refused) throw new Error('a second directory lock unexpectedly succeeded')
} finally {
  await lock.close()
}
process.stdout.write('native directory lock ok')
'@, $utf8)
    $nativeResult = & $node $probe $root
    if ($LASTEXITCODE -ne 0 -or $nativeResult -ne 'native directory lock ok') {
        Fail 'Windows directory lock probe failed'
    }
    $configRoot = Join-Path $root 'config'
    $config = Join-Path $configRoot 'daemon.json'
    $valid = & $node $main --check-config $config
    if ($LASTEXITCODE -ne 0 -or $valid -ne 'Daemon configuration is valid') {
        Fail '--check-config rejected the valid configuration'
    }
    foreach ($document in @('{"studioUrl":"not-a-url"}', '{"daemon":{"studioUrl":"http://127.0.0.1:59999"}}')) {
        [System.IO.File]::WriteAllText($config, $document, $utf8)
        # PowerShell 5.1 represents redirected native stderr as non-terminating errors.
        $ErrorActionPreference = 'Continue'
        try {
            $invalid = & $node $main --check-config $config 2>&1
            $invalidStatus = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = 'Stop'
        }
        if ($invalidStatus -eq 0 -or ($invalid -join "`n") -notmatch 'Invalid daemon configuration:') {
            Fail '--check-config did not reject invalid configuration'
        }
    }
    Write-Output "archive smoke ok: $name ($version)"
} finally {
    $env:PATH = $previousPath
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
