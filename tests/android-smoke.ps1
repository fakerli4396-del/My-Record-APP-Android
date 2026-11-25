param(
    [string]$Serial = 'emulator-5580',
    [string]$ExpectedText = '账本月历',
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$EvidenceDirectory = 'build\acceptance\android'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ($Serial -notmatch '^emulator-\d+$') { throw 'Smoke test requires an isolated emulator' }
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$aapt = Join-Path $Sdk 'build-tools\36.0.0\aapt.exe'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$evidence = Join-Path $root $EvidenceDirectory
$app = 'com.myrecord.program'

$model = (& $adb -s $Serial emu avd name | Select-Object -First 1).Trim()
if (!$model.StartsWith('MyRecord_')) { throw "Unexpected test emulator $model" }
if ((& $adb -s $Serial shell getprop ro.build.version.sdk).Trim() -ne '37') { throw 'API 37 is required' }
if ((& $adb -s $Serial shell getprop sys.boot_completed).Trim() -ne '1') { throw 'Emulator must finish booting' }
$permissions = & $aapt dump permissions $apk
if ($permissions -match 'android.permission.INTERNET') { throw 'Offline APK requests INTERNET' }

New-Item -ItemType Directory -Path $evidence -Force | Out-Null
& $adb -s $Serial shell cmd connectivity airplane-mode enable
& $adb -s $Serial shell svc wifi disable
if ((& $adb -s $Serial shell settings get global airplane_mode_on).Trim() -ne '1' -or
    (& $adb -s $Serial shell settings get global wifi_on).Trim() -ne '0') { throw 'Airplane mode or Wi-Fi state is incorrect' }
& $adb -s $Serial install -r $apk
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $Serial shell wm dismiss-keyguard
& $adb -s $Serial shell am force-stop $app
$launch = & $adb -s $Serial shell am start -W -n "$app/.MainActivity"
$launch
if (!($launch -match 'Status: ok')) { throw 'MainActivity did not launch' }

& $adb -s $Serial shell uiautomator dump /data/local/tmp/myrecord-ui.xml
& $adb -s $Serial pull /data/local/tmp/myrecord-ui.xml (Join-Path $evidence 'hierarchy.xml')
$hierarchy = Get-Content -Raw -LiteralPath (Join-Path $evidence 'hierarchy.xml')
if (!$hierarchy.Contains($ExpectedText) -or !$hierarchy.Contains($app)) { throw "Expected app UI not visible: $ExpectedText" }
& $adb -s $Serial shell screencap -p /data/local/tmp/myrecord-start.png
& $adb -s $Serial pull /data/local/tmp/myrecord-start.png (Join-Path $evidence 'offline-start.png')
"PASS Android 17 offline startup on $model"
