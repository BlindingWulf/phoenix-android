$ErrorActionPreference = 'Stop'
$stageRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $stageRoot 'telemetry-agent/build'
New-Item -ItemType Directory -Force "$target/classes", "$stageRoot/app/src/main/assets" | Out-Null
& javac --release 17 -d "$target/classes" "$stageRoot/telemetry-agent/src/org/phoenix/telemetry/MetricsAgent.java"
if ($LASTEXITCODE -ne 0) { throw 'Telemetry agent compilation failed' }
Set-Content -Encoding ASCII "$target/MANIFEST.MF" "Premain-Class: org.phoenix.telemetry.MetricsAgent`n"
& jar --create --file "$stageRoot/app/src/main/assets/phoenix-metrics.jar" --manifest "$target/MANIFEST.MF" -C "$target/classes" .
if ($LASTEXITCODE -ne 0) { throw 'Telemetry agent packaging failed' }
