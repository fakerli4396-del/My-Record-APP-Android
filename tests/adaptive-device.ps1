param([string]$Sdk = $env:ANDROID_HOME, [string]$Serial = 'emulator-5580')

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$root = Split-Path -Parent $PSScriptRoot
$app = 'com.myrecord.program'
$runner = "$app.test/androidx.test.runner.AndroidJUnitRunner"
if ($Serial -notmatch '^emulator-\d+$' -or
    !((& $adb -s $Serial emu avd name | Select-Object -First 1).StartsWith('MyRecord_'))) {
    throw 'A dedicated emulator is required'
}
& $adb -s $Serial install -r (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')
& $adb -s $Serial install -r (Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
$originalFont = (& $adb -s $Serial shell settings get system font_scale).Trim()
try {
    & $adb -s $Serial shell wm density 480
    foreach ($case in @(@{Size='960x2220';Name='320dp';Font='1.3'},
                        @{Size='1230x2550';Name='410dp';Font='1.0'},
                        @{Size='2100x1440';Name='700dp';Font='1.0'})) {
        & $adb -s $Serial shell wm size $case.Size
        & $adb -s $Serial shell settings put system font_scale $case.Font
        & $adb -s $Serial shell am force-stop $app
        $output = & $adb -s $Serial shell am instrument -w -e class "$app.AdaptiveUiTest" $runner
        $output
        if (!($output -match 'OK \(2 tests\)')) { throw "Alignment acceptance failed at $($case.Name)" }
        "PASS $($case.Name) at font scale $($case.Font)"
    }
} finally {
    & $adb -s $Serial shell wm size reset
    & $adb -s $Serial shell wm density reset
    if ($originalFont -ne 'null') { & $adb -s $Serial shell settings put system font_scale $originalFont }
    else { & $adb -s $Serial shell settings delete system font_scale }
}
