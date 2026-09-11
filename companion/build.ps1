# Thar Deck companion app: no-Gradle build.
#
# Produces a signed debug APK with only the Android SDK build-tools and a JDK,
# no Gradle and no network. Mirrors the pipeline proven on this machine:
#   aapt2 compile -> aapt2 link -> javac -> d8 -> add dex -> zipalign -> apksigner
#
# Requires: build-tools 36.0.0, platform android-36, a JDK on PATH, and the
# standard debug keystore at ~/.android/debug.keystore.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Install   (also adb install -r)

param([switch]$Install)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $here

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$bt  = Join-Path $sdk "build-tools\36.0.0"
$plat = Join-Path $sdk "platforms\android-36\android.jar"
$aapt2 = Join-Path $bt "aapt2.exe"
$d8 = Join-Path $bt "d8.bat"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$keystore = Join-Path $env:USERPROFILE ".android\debug.keystore"

foreach ($p in @($aapt2,$d8,$zipalign,$apksigner,$plat,$keystore)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}

$main = Join-Path $here "app\src\main"
$manifest = Join-Path $main "AndroidManifest.xml"
$resDir = Join-Path $main "res"
$javaDir = Join-Path $main "java"

$out = Join-Path $here "build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
$null = New-Item -ItemType Directory -Path $out
$genDir = Join-Path $out "gen"
$objDir = Join-Path $out "obj"
$flat = Join-Path $out "flat"
$null = New-Item -ItemType Directory -Path $genDir,$objDir,$flat

Write-Host "1/6 aapt2 compile resources"
& $aapt2 compile --dir $resDir -o (Join-Path $flat "res.zip")
if ($LASTEXITCODE) { throw "aapt2 compile failed" }

Write-Host "2/6 aapt2 link"
$baseApk = Join-Path $out "base.apk"
& $aapt2 link `
    -o $baseApk `
    -I $plat `
    --manifest $manifest `
    --java $genDir `
    --min-sdk-version 31 `
    --target-sdk-version 34 `
    --version-code 1 --version-name "1.0" `
    (Join-Path $flat "res.zip")
if ($LASTEXITCODE) { throw "aapt2 link failed" }

Write-Host "3/6 javac"
$srcs = @()
$srcs += (Get-ChildItem -Path $javaDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -Path $genDir  -Recurse -Filter R.java   | ForEach-Object { $_.FullName })
$argfile = Join-Path $out "javac.rsp"
$noBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($argfile, ($srcs | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), $noBom)
& javac -source 17 -target 17 -encoding UTF-8 -classpath $plat -d $objDir "@$argfile"
if ($LASTEXITCODE) { throw "javac failed" }

Write-Host "4/6 d8 -> classes.dex"
# d8's @argfile parser keeps quotes literally, which breaks on the space in the
# path. Jar the classes into one input instead, passed as a single direct arg.
$inJar = Join-Path $out "classes-input.jar"
& jar cf $inJar -C $objDir .
if ($LASTEXITCODE) { throw "jar of classes failed" }
& $d8 --min-api 31 --lib $plat --output $out $inJar
if ($LASTEXITCODE) { throw "d8 failed" }

Write-Host "5/6 add dex + zipalign"
$unsigned = Join-Path $out "app-unsigned.apk"
Copy-Item $baseApk $unsigned -Force
Push-Location $out
& jar uf $unsigned classes.dex
if ($LASTEXITCODE) { Pop-Location; throw "adding dex failed" }
Pop-Location
$aligned = Join-Path $out "app-aligned.apk"
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE) { throw "zipalign failed" }

Write-Host "6/6 apksigner"
$final = Join-Path $out "thardeck.apk"
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $final $aligned
if ($LASTEXITCODE) { throw "apksigner failed" }
& $apksigner verify $final | Out-Null

Write-Host ""
Write-Host ("BUILT  " + $final)
Write-Host ("size   " + [math]::Round((Get-Item $final).Length/1KB) + " KB")

if ($Install) {
    Write-Host "adb install -r"
    & adb install -r $final
}
