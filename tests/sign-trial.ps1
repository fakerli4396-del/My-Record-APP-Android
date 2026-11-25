param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = Split-Path -Parent $PSScriptRoot
$profile = Join-Path $root '.signing\trial'
$key = Join-Path $profile 'trial.p12'
$passFile = Join-Path $profile 'password.dpapi'
$keytool = Join-Path $Jdk 'bin\keytool.exe'
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
$signer = Join-Path $buildTools 'apksigner.bat'
$aligner = Join-Path $buildTools 'zipalign.exe'
$destination = [IO.Path]::GetFullPath($OutputApk)
$parent = Split-Path -Parent $destination

if (!(Test-Path -LiteralPath $InputApk -PathType Leaf)) { throw "APK not found: $InputApk" }
if (!(Test-Path -LiteralPath $keytool -PathType Leaf)) { throw "JDK keytool not found: $keytool" }
New-Item -ItemType Directory -Path $profile,$parent -Force | Out-Null
$fresh = !(Test-Path -LiteralPath $key)
if ($fresh -and (Test-Path -LiteralPath $passFile)) { throw 'Trial key is missing for the stored credential' }
if (!$fresh -and !(Test-Path -LiteralPath $passFile)) { throw 'Trial key credential is missing' }
$aligned = Join-Path $parent ("aligned-" + [guid]::NewGuid().ToString() + ".apk")
try {
    if ($fresh) {
        $env:MY_RECORD_TRIAL_STORE_PASS = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $secured = ConvertTo-SecureString $env:MY_RECORD_TRIAL_STORE_PASS -AsPlainText -Force
        ($secured | ConvertFrom-SecureString) | Set-Content -LiteralPath $passFile -NoNewline -Encoding ascii
        $keyArguments = @('-genkeypair', '-keystore', $key, '-storetype', 'PKCS12', '-storepass:env',
            'MY_RECORD_TRIAL_STORE_PASS', '-keypass:env', 'MY_RECORD_TRIAL_STORE_PASS', '-alias', 'myrecord-trial',
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-dname', 'CN=My Record Local Trial')
        & $keytool @keyArguments
    } else {
        $secured = Get-Content -Raw -LiteralPath $passFile | ConvertTo-SecureString
        $env:MY_RECORD_TRIAL_STORE_PASS = [Net.NetworkCredential]::new('', $secured).Password
    }
    & $aligner -f -P 16 4 $InputApk $aligned
    $signArguments = @('sign', '--ks', $key, '--ks-key-alias', 'myrecord-trial',
        '--ks-pass', 'env:MY_RECORD_TRIAL_STORE_PASS', '--key-pass', 'env:MY_RECORD_TRIAL_STORE_PASS',
        '--v4-signing-enabled', 'false', '--out', $destination, $aligned)
    & $signer @signArguments
    & $aligner -c -P 16 4 $destination
    & $signer verify --verbose --print-certs $destination
    Get-FileHash -Algorithm SHA256 -LiteralPath $destination
} finally {
    Remove-Item Env:MY_RECORD_TRIAL_STORE_PASS -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $aligned -PathType Leaf) { Remove-Item -LiteralPath $aligned }
}
