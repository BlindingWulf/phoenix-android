param(
    [string]$Ndk = $(if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { Join-Path $PSScriptRoot "..\..\android-stage10a\.tools\android-sdk\ndk\27.3.13750724" }),
    [string]$Java25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot",
    [string[]]$Abis = @("arm64-v8a", "x86_64")
)
$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$runtime = Join-Path $workspace "android-stage10a/runtime"
$generated = Join-Path $runtime "generated"
& python (Join-Path $PSScriptRoot "package_runtime.py") --ndk $Ndk --output $generated --abis @Abis
if ($LASTEXITCODE -ne 0) { throw "Runtime packaging failed" }
$compiler = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
foreach ($abi in $Abis) {
    $target = if ($abi -eq "arm64-v8a") { "aarch64-linux-android26" } else { "x86_64-linux-android26" }
    $output = Join-Path $generated "jniLibs/$abi/libphoenixjvm.so"
    & $compiler "--target=$target" -fPIE -pie -O2 -Wall -Wextra -Werror (Join-Path $runtime "phoenix_jvm.c") -ldl "-Wl,--export-dynamic" "-Wl,-z,max-page-size=16384" -o $output
    if ($LASTEXITCODE -ne 0) { throw "Native launcher build failed: $abi" }
    Get-FileHash -LiteralPath $output -Algorithm SHA256
}
$classes = Join-Path $runtime "build/probe-classes"
New-Item -ItemType Directory -Force -Path $classes, (Join-Path $generated "assets/runtime") | Out-Null
$classRoot = (Resolve-Path -LiteralPath $classes).Path
if (-not $classRoot.StartsWith($runtime + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Class cleanup escaped runtime directory" }
Get-ChildItem -LiteralPath $classRoot -Recurse -Filter '*.class' | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $runtime "java") -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $Java25 "bin/javac.exe") --release 25 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Java25 probe compilation failed" }
& (Join-Path $Java25 "bin/jar.exe") --create --date=2026-10-08T00:00:00Z --file (Join-Path $generated "assets/runtime/phoenix-runtime.jar") -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Java25 probe packaging failed" }
$identity = [ordered]@{ schema = 1; ndk = "27.3.13750724"; launcherSource = "runtime/phoenix_jvm.c"; nativeHosts = @{}; runtimeJarSha256 = (Get-FileHash -LiteralPath (Join-Path $generated "assets/runtime/phoenix-runtime.jar") -Algorithm SHA256).Hash.ToLower() }
foreach ($abi in $Abis) { $identity.nativeHosts[$abi] = (Get-FileHash -LiteralPath (Join-Path $generated "jniLibs/$abi/libphoenixjvm.so") -Algorithm SHA256).Hash.ToLower() }
$identity | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $generated "assets/runtime/host-manifest.json")
