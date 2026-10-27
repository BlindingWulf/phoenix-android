param(
    [string]$NdkRoot,
    [switch]$ProbeConnectedDevice
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$toolRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$stageRoot = (Resolve-Path -LiteralPath (Join-Path $toolRoot '..\..')).Path
$revision = '633d650869a9cdb2c906efa9effc58aa8b7725a3'
$repository = 'https://github.com/playit-cloud/playit-agent.git'
$target = 'aarch64-linux-android'
$apiLevel = 24

if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    $NdkRoot = Join-Path $stageRoot '.tools\android-sdk\ndk\27.3.13750724'
}
$NdkRoot = (Resolve-Path -LiteralPath $NdkRoot).Path
$clangBin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$linker = Join-Path $clangBin "aarch64-linux-android$apiLevel-clang.cmd"
$archiver = Join-Path $clangBin 'llvm-ar.exe'
$readelf = Join-Path $clangBin 'llvm-readelf.exe'
$cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
$cargo = Join-Path $cargoBin 'cargo.exe'
$rustup = Join-Path $cargoBin 'rustup.exe'
$rustc = Join-Path $cargoBin 'rustc.exe'

foreach ($requiredPath in @($linker, $archiver, $readelf, $cargo, $rustup, $rustc)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required Playit Android build tool is missing: $requiredPath"
    }
}

$workRoot = [System.IO.Path]::GetTempPath()
$workPath = Join-Path $workRoot ('phoenix-playit-agent-' + [System.Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $stageRoot 'runtime\generated\jniLibs\arm64-v8a\libplayitagent.so'
$metadataPath = Join-Path $toolRoot 'BUILD_METADATA.txt'
$licensePath = Join-Path $toolRoot 'LICENSE-PLAYIT-AGENT.txt'
$remoteProbePath = '/data/local/tmp/phoenix-playitd-probe-' + $PID
$probePushed = $false

try {
    $installedTargets = & $rustup target list --installed
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to query installed Rust targets.'
    }
    if ($installedTargets -notcontains $target) {
        & $rustup target add $target
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to install the Rust target $target."
        }
    }

    $git = (Get-Command git -ErrorAction Stop).Source
    & $git clone --filter=blob:none --no-checkout $repository $workPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to clone the official Playit agent source.'
    }
    & $git -C $workPath fetch --depth=1 origin $revision
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to fetch the pinned Playit agent revision.'
    }
    & $git -C $workPath checkout --detach $revision
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to check out the pinned Playit agent revision.'
    }
    $checkedOutRevision = (& $git -C $workPath rev-parse HEAD).Trim()
    if ($checkedOutRevision -ne $revision) {
        throw "Source revision mismatch: expected $revision, got $checkedOutRevision."
    }

    $ndkProperties = Get-Content -LiteralPath (Join-Path $NdkRoot 'source.properties') -Raw
    if ($ndkProperties -notmatch 'Pkg.Revision\s*=\s*27\.3\.13750724') {
        throw 'This build script is pinned to Android NDK 27.3.13750724.'
    }

    $env:PATH = "$cargoBin;$clangBin;$env:PATH"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
    $env:CC_aarch64_linux_android = $linker
    $env:AR_aarch64_linux_android = $archiver
    $env:CFLAGS_aarch64_linux_android = '-fPIC'
    $env:RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'

    $cargoVersion = (& $cargo --version).Trim()
    $rustcVersion = (& $rustc --version).Trim()
    & $cargo build --locked --release -p playitd --bin playitd --target $target --manifest-path (Join-Path $workPath 'Cargo.toml')
    if ($LASTEXITCODE -ne 0) {
        throw 'Pinned Playit daemon Android build failed; inspect the Cargo diagnostics above.'
    }

    $builtBinary = Join-Path $workPath 'target\aarch64-linux-android\release\playitd'
    if (-not (Test-Path -LiteralPath $builtBinary -PathType Leaf)) {
        throw 'Cargo completed without producing the playitd executable.'
    }
    $elfHeader = (& $readelf -h $builtBinary | Out-String)
    $programHeaders = (& $readelf -l $builtBinary | Out-String)
    if (($elfHeader -notmatch 'Type:\s+DYN') -or
            ($elfHeader -notmatch 'Machine:\s+AArch64') -or
            ($elfHeader -notmatch 'Entry point address:\s+0x[1-9a-fA-F]')) {
        throw 'Built ELF is not an AArch64 position-independent executable.'
    }
    if (($programHeaders -notmatch 'Requesting program interpreter:\s+/system/bin/linker64') -or
            ($programHeaders -notmatch 'LOAD.*0x4000')) {
        throw 'Built ELF lacks Android linker64 or 16 KiB PT_LOAD alignment.'
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $artifact) | Out-Null
    Copy-Item -LiteralPath $builtBinary -Destination $artifact -Force
    Copy-Item -LiteralPath (Join-Path $workPath 'LICENSE.txt') -Destination $licensePath -Force

    $cargoLockHash = (Get-FileHash -LiteralPath (Join-Path $workPath 'Cargo.lock') -Algorithm SHA256).Hash
    $binaryHash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    $licenseHash = (Get-FileHash -LiteralPath $licensePath -Algorithm SHA256).Hash
    $metadata = @(
        'Playit Android native build metadata',
        "Upstream: $repository",
        "Revision: $revision",
        "Cargo.lock SHA-256: $cargoLockHash",
        "Cargo: $cargoVersion",
        "Rustc: $rustcVersion",
        'NDK: 27.3.13750724 (r27d)',
        "Target: $target, Android API $apiLevel",
        "Artifact: runtime/generated/jniLibs/arm64-v8a/libplayitagent.so",
        "Artifact SHA-256: $binaryHash",
        "Upstream LICENSE.txt SHA-256: $licenseHash",
        'Artifact kind: executable AArch64 PIE, packaged with a .so suffix; launch as a process, never with System.loadLibrary.'
    )
    [System.IO.File]::WriteAllText($metadataPath, ($metadata -join "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))

    if ($ProbeConnectedDevice) {
        $adb = Join-Path $stageRoot '.tools\android-sdk\platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
            throw "ADB is unavailable for the requested device probe: $adb"
        }
        & $adb push $artifact $remoteProbePath
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to push the no-credential Playit probe to the connected device.'
        }
        $probePushed = $true
        & $adb shell chmod 700 $remoteProbePath
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to set the Android probe executable permission.'
        }
        & $adb shell $remoteProbePath --help
        if ($LASTEXITCODE -ne 0) {
            throw 'The Android device could not execute the Playit daemon probe.'
        }
    }

    Write-Output "Built and verified $artifact"
    Write-Output "SHA-256: $binaryHash"
}
finally {
    if ($probePushed) {
        $adb = Join-Path $stageRoot '.tools\android-sdk\platform-tools\adb.exe'
        & $adb shell rm -f $remoteProbePath | Out-Null
    }
    $resolvedWorkPath = [System.IO.Path]::GetFullPath($workPath)
    $resolvedWorkRoot = [System.IO.Path]::GetFullPath($workRoot)
    if (($resolvedWorkPath.StartsWith($resolvedWorkRoot, [System.StringComparison]::OrdinalIgnoreCase)) -and ((Split-Path -Leaf $resolvedWorkPath) -like 'phoenix-playit-agent-*') -and (Test-Path -LiteralPath $resolvedWorkPath -PathType Container)) {
        Remove-Item -LiteralPath $resolvedWorkPath -Recurse -Force
    }
}
