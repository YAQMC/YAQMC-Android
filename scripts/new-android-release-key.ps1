[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Destination)

# This helper creates a new identity for an independent app. Existing
# YAQMC-Android releases must continue to use their current signing key.
$ErrorActionPreference = 'Stop'
$destinationPath = [IO.Path]::GetFullPath($Destination)
$repositoryPath = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if ($destinationPath.StartsWith($repositoryPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $destinationPath -eq $repositoryPath) {
  throw 'Signing material must be stored outside the repository.'
}
if (Test-Path -LiteralPath $destinationPath) { throw 'Destination already exists; refusing to overwrite signing material.' }
$keytool = (Get-Command keytool -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $destinationPath | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $destinationPath /inheritance:r /grant:r "*${identity}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict signing directory permissions.' }
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$keyAlias = 'yaqmc-release'
$keystore = Join-Path $destinationPath 'yaqmc-android-release.p12'
$certificate = Join-Path $destinationPath 'yaqmc-android-release.cer'
$previousPassword = $env:YAQMC_KEYGEN_PASSWORD
try {
  $env:YAQMC_KEYGEN_PASSWORD = $password
  & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -storepass:env YAQMC_KEYGEN_PASSWORD -keypass:env YAQMC_KEYGEN_PASSWORD -alias $keyAlias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=YAQMC Android Release, O=YAQMC' -noprompt
  if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
  & $keytool -exportcert -keystore $keystore -storepass:env YAQMC_KEYGEN_PASSWORD -alias $keyAlias -file $certificate
  if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed.' }
  & $keytool -list -keystore $keystore -storepass:env YAQMC_KEYGEN_PASSWORD -alias $keyAlias | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Keystore verification failed.' }
  $digest = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
  $encodedKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
  $secretValues = [ordered]@{
    ANDROID_RELEASE_KEYSTORE_BASE64 = $encodedKey
    ANDROID_RELEASE_KEY_ALIAS = $keyAlias
    ANDROID_RELEASE_STORE_PASSWORD = $password
    ANDROID_RELEASE_KEY_PASSWORD = $password
    ANDROID_RELEASE_CERT_SHA256 = $digest
  }
  foreach ($entry in $secretValues.GetEnumerator()) {
    [IO.File]::WriteAllText((Join-Path $destinationPath ($entry.Key + '.txt')), $entry.Value)
  }
  $instructions = @'
Android signing material for a new app identity — PRIVATE

This is a newly generated signing identity for an independent app.
Existing YAQMC-Android releases must continue to use their current signing key.
The current project release workflow is documented in docs/development.md.
Keep the ANDROID_RELEASE_*.txt files private; do not commit or publish them.

The .p12 file contains the private signing key. The .cer file is public.
Back up this entire directory to an encrypted offline location before deleting any copy.
Passwords in the .txt files are plaintext; keep this directory private.
For future releases of the independent app, keep using this same key. Existing
installations signed with an old key cannot be updated in place without a compatible signing lineage.
Do not uninstall an existing app without first preserving any data you need.
No secrets have been uploaded automatically.
'@
  [IO.File]::WriteAllText((Join-Path $destinationPath 'INSTRUCTIONS.txt'), $instructions)
  Write-Output "Generated and verified signing material: $destinationPath"
  Write-Output "Certificate SHA-256 (public): $digest"
} finally {
  $env:YAQMC_KEYGEN_PASSWORD = $previousPassword
  $password = $null
  $encodedKey = $null
  $secretValues = $null
}
