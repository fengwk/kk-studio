& {
function Set-KkPrivateAcl([string]$Path, [switch]$Directory) {
  $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $acl = if ($Directory) { [Security.AccessControl.DirectorySecurity]::new() } else { [Security.AccessControl.FileSecurity]::new() }
  $acl.SetOwner($owner)
  $acl.SetAccessRuleProtection($true, $false)
  $inheritance = if ($Directory) { 'ContainerInherit, ObjectInherit' } else { 'None' }
  $rule = [Security.AccessControl.FileSystemAccessRule]::new($owner, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.InheritanceFlags]$inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
  $acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}
Set-PSDebug -Off
$ErrorActionPreference = 'Stop'
$stage = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString('N'))
$savedProxy = [Net.WebRequest]::DefaultWebProxy
$savedEnvironment = @{}
Get-ChildItem Env: | Where-Object { $_.Name -match '^(http|https|all|no)_proxy$|^KK_DAEMON_INSTALL_PROXY_ENV$' } | ForEach-Object {
  $savedEnvironment[$_.Name] = $_.Value
  Remove-Item -LiteralPath ("Env:" + $_.Name)
}
try {
  New-Item -ItemType Directory -Path $stage | Out-Null
  Set-KkPrivateAcl -Path $stage -Directory
%s
  $env:HTTP_PROXY = %s
  $env:HTTPS_PROXY = $env:HTTP_PROXY
  $env:NO_PROXY = %s
  $env:KK_DAEMON_INSTALL_PROXY_ENV = '1'
  $direct = %s
  $proxyArguments = @{}
  if ($direct) {
    [Net.WebRequest]::DefaultWebProxy = $null
    if ($PSVersionTable.PSVersion.Major -ge 6) { $proxyArguments.NoProxy = $true }
  } else {
    $proxyArguments.Proxy = $env:HTTPS_PROXY
  }
  $installer = Join-Path $stage 'install.ps1'
  Invoke-WebRequest -UseBasicParsing -Uri %s -OutFile $installer @proxyArguments
  & powershell -NoProfile -ExecutionPolicy Bypass -File $installer %s%s
  if ($LASTEXITCODE -ne 0) { throw 'Installer failed' }
}
catch {
  # Fixed message: never surface a staging exception that could quote credential-bearing source.
  throw 'Daemon command failed; review installer output and host prerequisites.'
}
finally {
  Get-ChildItem Env: | Where-Object { $_.Name -match '^(http|https|all|no)_proxy$|^KK_DAEMON_INSTALL_PROXY_ENV$' } | Remove-Item
  foreach ($entry in $savedEnvironment.GetEnumerator()) { Set-Item -LiteralPath ("Env:" + $entry.Key) -Value $entry.Value }
  [Net.WebRequest]::DefaultWebProxy = $savedProxy
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
}
