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
try {
  New-Item -ItemType Directory -Path $stage | Out-Null
  Set-KkPrivateAcl -Path $stage -Directory
@@STAGING@@
  $installer = Join-Path $stage 'install.ps1'
  Invoke-WebRequest -UseBasicParsing -Uri @@INSTALLER@@ -OutFile $installer
  & powershell -NoProfile -ExecutionPolicy Bypass -File $installer @@ACTION@@@@PARAMETERS@@
  if ($LASTEXITCODE -ne 0) { throw 'Installer failed' }
}
catch {
  # Fixed message: never surface a staging exception that could quote credential-bearing source.
  throw 'Daemon command failed; review installer output and host prerequisites.'
}
finally {
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
}
