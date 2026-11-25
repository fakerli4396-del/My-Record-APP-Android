param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Serial = 'emulator-5580',
    [string]$AppApk = 'app\build\outputs\apk\debug\app-debug.apk',
    [string]$InstrumentationApk = 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
)

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
& $adb -s $Serial install -r $AppApk
& $adb -s $Serial install -t -r $InstrumentationApk
& $adb -s $Serial shell cmd connectivity airplane-mode enable
& $adb -s $Serial shell svc wifi disable
$seed = & $adb -s $Serial shell am instrument -w -e class "$app.PerformanceLedgerTest" -e performanceMode seed $runner
$seed
if (!($seed -match 'OK \(1 test\)')) { throw 'Unable to install the large-ledger acceptance fixture' }
$save = & $adb -s $Serial shell am instrument -w -e class "$app.PerformanceLedgerTest" $runner
$save
if (!($save -match 'OK \(1 test\)')) { throw 'Large-ledger save performance did not pass' }
& $adb -s $Serial logcat -d -s MyRecordPerf:I | Select-String 'MY_RECORD_SAVE_P95_MS=' | Select-Object -Last 1
$series = @(foreach ($index in 1..10) {
    & $adb -s $Serial shell am force-stop $app
    & $adb -s $Serial logcat -c
    $process = & $adb -s $Serial shell am start -W -n "$app/.MainActivity"
    if (!($process -match 'Status: ok')) { throw "Cold startup $index failed" }
    $record = $null
    $deadline = (Get-Date).AddSeconds(12)
    do {
        $record = & $adb -s $Serial logcat -d -s ActivityTaskManager:I |
            Select-String -SimpleMatch "Fully drawn $app/" | Select-Object -Last 1
        if (!$record) { Start-Sleep -Milliseconds 125 }
    } until ($record -or (Get-Date) -gt $deadline)
    if (!$record -or $record.Line -notmatch '\+(?:(?<seconds>\d+)s)?(?:(?<milliseconds>\d+)ms)?\s*$') {
        throw "No fully-drawn timestamp for launch $index"
    }
    $elapsed = 1000 * [int]$Matches.seconds + [int]$Matches.milliseconds
    if ($elapsed -le 0) { throw "Invalid fully-drawn timestamp for launch $index" }
    $elapsed
})
"Fully drawn startup times (ms): $($series -join ', ')"
$series | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath (Join-Path $root 'build\acceptance\cold-start-ms.json')
if (($series | Measure-Object -Maximum).Maximum -gt 2000) { throw 'Cold startup exceeded the 2 s target on this emulator' }
