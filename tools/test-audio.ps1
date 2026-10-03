param(
    [string]$ToolchainRoot = "$env:USERPROFILE/.cache/sleep-desk-toolchains"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$java = Join-Path $ToolchainRoot 'jdk/jdk-17.0.20.1+1/bin/java.exe'
$libs = Join-Path $ToolchainRoot 'kotlin'
$out = Join-Path $root 'build/audio-jvm-tests'
if (!(Test-Path $java)) { throw "Portable Java 17 not found: $java" }
$jars = @(Get-ChildItem -LiteralPath $libs -Filter '*.jar')
if ($jars.Count -lt 9) { throw "Kotlin/JUnit toolchain is incomplete: $libs" }
$classpath = ($jars.FullName -join [IO.Path]::PathSeparator)
New-Item -ItemType Directory -Force $out | Out-Null
$base = Join-Path $root 'app/src/main/java/com/i3u8/sleepdesk'
$sources = @(
    "$base/audio/AudioFeatures.kt"
    "$base/audio/RuleClassifier.kt"
    "$base/audio/NightModels.kt"
    "$base/audio/CandidateDetector.kt"
    "$base/audio/PcmRingBuffer.kt"
    "$base/audio/ContextualAudioBuffer.kt"
    "$base/audio/EventDetectionPipeline.kt"
    "$base/audio/SoundClassifier.kt"
    "$base/audio/TemporalSoundFusion.kt"
    "$base/audio/SoundEventProcessor.kt"
    "$base/audio/BackgroundAudioQueue.kt"
    "$base/audio/EventUpdatePublisher.kt"
    "$base/data/Models.kt"
    "$base/data/SegmentBuilder.kt"
    "$base/data/NightSegment.kt"
    "$base/ui/NightTimelineHeuristics.kt"
)
$tests = @(Get-ChildItem "$root/app/src/test/java/com/i3u8/sleepdesk" -Recurse -Filter '*Test.kt')
$outputJar = Join-Path $out 'tests.jar'
& $java -cp $classpath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
    -d $outputJar @sources @($tests.FullName)
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed: $LASTEXITCODE" }
$testClasses = @($tests | ForEach-Object {
    $package = [regex]::Match((Get-Content -LiteralPath $_.FullName -Raw), '(?m)^package\s+([\w.]+)').Groups[1].Value
    "$package.$($_.BaseName)"
})
& $java -cp "$outputJar$([IO.Path]::PathSeparator)$classpath" org.junit.runner.JUnitCore @testClasses
if ($LASTEXITCODE -ne 0) { throw "JUnit tests failed: $LASTEXITCODE" }
