param(
    [switch]$SourceStable,
    [string]$Serial = 'emulator-5580',
    [string]$AvdName = 'sleepdesk_audio_v2_api30',
    [string]$Toolchain = 'C:/Users/unknown/.cache/sleep-desk-toolchains'
)
$ErrorActionPreference = 'Stop'
if (!$SourceStable) { throw 'Run only after the source owner confirms the final source is stable: -SourceStable' }
if ($Serial -ne 'emulator-5580' -or $AvdName -ne 'sleepdesk_audio_v2_api30') {
    throw 'Only the dedicated validation emulator is permitted.'
}
$repo = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = "$Toolchain/jdk/jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$Toolchain/android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$Toolchain/gradle-home"
$env:ANDROID_SERIAL = $Serial
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$actual = & $adb -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or $actual[0].Trim() -ne $AvdName) { throw 'AVD identity mismatch or unavailable.' }
$qemu = & $adb -s $Serial shell getprop ro.kernel.qemu
$boot = & $adb -s $Serial shell getprop sys.boot_completed
if ($qemu.Trim() -ne '1' -or $boot.Trim() -ne '1') { throw 'Dedicated emulator is not booted.' }
if (!(Test-Path 'S:/settings.gradle.kts') -or
    (Get-FileHash 'S:/settings.gradle.kts').Hash -ne (Get-FileHash "$repo/settings.gradle.kts").Hash) {
    throw 'S: must map to this repository before validation.'
}
$run = Join-Path (Split-Path $repo -Parent) ("private-analysis/android-validation-v2/" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Invoke-Logged([string]$Name, [string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments 2>&1 | Tee-Object -FilePath "$run/$Name.txt"
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE; inspect $run/$Name.txt" }
}
function Assert-DedicatedDeviceList([string[]]$Lines, [string]$ExpectedSerial) {
    $rows = @($Lines | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($rows.Count -eq 0 -or $rows[0] -ne 'List of devices attached') {
        throw 'Cannot verify adb device inventory; refusing connected tests.'
    }
    $devices = @($rows | Select-Object -Skip 1)
    # Fail closed for extra devices in any state, duplicate rows or unexpected output.
    if ($devices.Count -ne 1 -or
        $devices[0] -notmatch ('^' + [regex]::Escape($ExpectedSerial) + '\s+device$')) {
        throw 'Connected tests require only the dedicated online emulator; disconnect all other devices, including unauthorized/offline devices.'
    }
}
Push-Location 'S:/'
try {
    Invoke-Logged 'device' $adb @('-s', $Serial, 'shell', 'getprop')
    Invoke-Logged 'build' "$Toolchain/gradle-8.7/bin/gradle.bat" @(
        '--no-daemon', '--console=plain', 'testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest'
    )
    $apk = "$repo/app/build/outputs/apk/debug/app-debug.apk"
    Invoke-Logged 'apk-signature' "$env:ANDROID_HOME/build-tools/34.0.0/apksigner.bat" @(
        'verify', '--verbose', '--print-certs', $apk
    )
    Get-FileHash $apk -Algorithm SHA256 | Format-List | Out-File "$run/apk-sha256.txt"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $models = @($zip.Entries | Where-Object { $_.FullName -match '^assets/.*\.tflite$' })
        if ($models.Count -eq 0) { throw 'No TFLite model assets in APK.' }
        $hashes = foreach ($entry in $models) {
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $stream.Dispose(); $sha.Dispose() }
            $source = Join-Path "$repo/app/src/main" $entry.FullName
            if (!(Test-Path $source)) { throw "Missing source asset: $source" }
            $sourceHash = (Get-FileHash $source -Algorithm SHA256).Hash
            if ($hash -ne $sourceHash) { throw "Packaged model hash differs: $($entry.FullName)" }
            [pscustomobject]@{ Asset=$entry.FullName; Bytes=$entry.Length; SHA256=$hash; SourceSHA256=$sourceHash }
        }
        $hashes | ConvertTo-Json -Depth 3 | Out-File "$run/model-hashes.json"
    } finally { $zip.Dispose() }
    $deviceList = @(& $adb devices)
    $deviceListExitCode = $LASTEXITCODE
    $deviceList | Out-File "$run/pre-instrumentation-devices.txt"
    if ($deviceListExitCode -ne 0) { throw 'adb devices failed; refusing connected tests.' }
    Assert-DedicatedDeviceList -Lines $deviceList -ExpectedSerial $Serial
    Invoke-Logged 'instrumentation' "$Toolchain/gradle-8.7/bin/gradle.bat" @(
        '--no-daemon', '--console=plain', '-Pandroid.testInstrumentationRunnerArguments.privateReplay=false',
        'connectedDebugAndroidTest'
    )
    Invoke-Logged 'screenshot-capture' $adb @('-s', $Serial, 'shell', 'screencap', '-p', '/sdcard/sleepdesk-verification.png')
    Invoke-Logged 'screenshot-pull' $adb @('-s', $Serial, 'pull', '/sdcard/sleepdesk-verification.png', "$run/screen.png")
} catch {
    $_ | Out-String | Out-File "$run/failure.txt"
    throw
} finally {
    foreach ($relative in @('reports/tests', 'reports/androidTests', 'outputs/androidTest-results', 'test-results')) {
        $source = Join-Path "$repo/app/build" $relative
        if (Test-Path $source) {
            $target = Join-Path $run $relative
            New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $source -Destination $target -Recurse
        }
    }
    Pop-Location
    Write-Host "Validation evidence: $run"
}
