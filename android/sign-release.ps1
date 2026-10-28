# Signs an APK with the Homebase release key and its debug -> release rotation lineage.
# v3 only (minSdk 28): only the release key is needed, and the lineage lets it upgrade debug-signed installs.
# Signing materials live outside the repo, in $env:HOMEBASE_SIGNING_DIR (default ~\.homebase-signing).
param(
    [string]$Apk = "$PSScriptRoot\app\build\outputs\apk\release\app-release-unsigned.apk",
    [string]$Out = "$PSScriptRoot\app\build\outputs\apk\release\homebase-release.apk"
)
# Not 'Stop': Windows PowerShell treats apksigner's Java warnings on stderr as errors; exit codes are checked instead.
$dir = if ($env:HOMEBASE_SIGNING_DIR) { $env:HOMEBASE_SIGNING_DIR } else { "$env:USERPROFILE\.homebase-signing" }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$apksigner = (Get-ChildItem "$sdk\build-tools\*\apksigner.bat" | Sort-Object { [version]$_.Directory.Name } | Select-Object -Last 1).FullName

# The password is DPAPI-encrypted for this Windows user and only reaches apksigner through its environment.
$secure = Get-Content "$dir\homebase-release.pass.dpapi" -ErrorAction Stop | ConvertTo-SecureString -ErrorAction Stop
$env:HB_KS_PASS = [Net.NetworkCredential]::new('', $secure).Password
try {
    & $apksigner sign --ks "$dir\homebase-release.p12" --ks-key-alias homebase-release --ks-pass env:HB_KS_PASS `
        --lineage "$dir\debug-to-release.lineage" --rotation-min-sdk-version 28 `
        --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true `
        --out $Out $Apk 2>$null
    if ($LASTEXITCODE) { throw "apksigner sign failed ($LASTEXITCODE)" }
} finally {
    Remove-Item Env:HB_KS_PASS -ErrorAction SilentlyContinue
}
& $apksigner verify --min-sdk-version 28 --print-certs -v $Out 2>$null | Select-String 'Verified using|Signer #1 certificate (DN|SHA-256)|Number of signers'
if ($LASTEXITCODE) { throw "apksigner verify failed ($LASTEXITCODE)" }
