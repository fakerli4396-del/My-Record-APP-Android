param(
    [Parameter(Mandatory)][string]$OlderApk,
    [Parameter(Mandatory)][string]$NewerApk,
    [Parameter(Mandatory)][string]$TestApk,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Serial = 'emulator-5584',
    [switch]$ResumeAfterUpgrade
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$app = 'com.myrecord.program'
$runner = "$app.test/androidx.test.runner.AndroidJUnitRunner"
if ($Serial -notmatch '^emulator-\d+$' -or
    !((& $adb -s $Serial emu avd name | Select-Object -First 1).StartsWith('MyRecord_Trial'))) {
    throw 'A fresh dedicated trial emulator is required'
}
& $adb -s $Serial shell cmd connectivity airplane-mode enable
& $adb -s $Serial shell svc wifi disable
if (!$ResumeAfterUpgrade) {
    $existing = & $adb -s $Serial shell pm list packages $app
    if ($existing -match "^package:$app$") { throw 'Trial app is already present. Use a fresh acceptance emulator.' }
    & $adb -s $Serial install $OlderApk
    & $adb -s $Serial install -t $TestApk
    $old = & $adb -s $Serial shell dumpsys package $app
    if (!($old -match 'versionCode=1\b')) { throw 'Expected trial version 1' }
    $seed = & $adb -s $Serial shell am instrument -w -e class "$app.UpgradeFixtureTest" -e upgradePhase seed $runner
    $seed
    if (!($seed -match 'OK \(1 test\)')) { throw 'Trial fixture could not be seeded' }
    & $adb -s $Serial install -r $NewerApk
}
$updated = & $adb -s $Serial shell dumpsys package $app
if (!($updated -match 'versionCode=2\b')) { throw 'Expected trial version 2' }
$verify = & $adb -s $Serial shell am instrument -w -e class "$app.UpgradeFixtureTest" -e upgradePhase inspect $runner
$verify
if (!($verify -match 'OK \(1 test\)')) { throw 'Existing trial data could not be read after update' }
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $Serial shell wm dismiss-keyguard
& $adb -s $Serial shell am start -W -n "$app/.MainActivity"
& $adb -s $Serial shell am send-trim-memory $app RUNNING_CRITICAL
& $adb -s $Serial shell am force-stop $app
$verify = & $adb -s $Serial shell am instrument -w -e class "$app.UpgradeFixtureTest" -e upgradePhase inspect $runner
$verify
if (!($verify -match 'OK \(1 test\)')) { throw 'Existing data could not be read after memory trim' }
"PASS same-certificate upgrade from versionCode 1 to 2, airplane mode, ledger and image preserved"
