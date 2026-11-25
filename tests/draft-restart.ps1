param([string]$Sdk = $env:ANDROID_HOME, [string]$Serial = 'emulator-5580')

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$root = Split-Path -Parent $PSScriptRoot
$app = 'com.myrecord.program'
$runner = "$app.test/androidx.test.runner.AndroidJUnitRunner"
$stamp = [guid]::NewGuid().ToString()
if ($Serial -notmatch '^emulator-\d+$' -or
    !((& $adb -s $Serial emu avd name | Select-Object -First 1).StartsWith('MyRecord_'))) {
    throw 'Draft restart test requires an isolated emulator'
}
& $adb -s $Serial install -r (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')
& $adb -s $Serial install -r (Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
foreach ($phase in @('write', 'read')) {
    $output = & $adb -s $Serial shell am instrument -w -e class "$app.DraftProcessTest" -e draftRun $stamp -e draftPhase $phase $runner
    $output
    if (!($output -match 'OK \(1 test\)')) { throw "Draft $phase test failed" }
    if ($phase -eq 'write') { & $adb -s $Serial shell am force-stop $app }
}
