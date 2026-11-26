Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ownershipLabel = "true"
$labelName = "br.com.estapar.parking.e2e"
$containers = @(
    "estapar-e2e-webhook-proxy",
    "estapar-e2e-simulator",
    "estapar-e2e-mysql"
)

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Required command was not found: docker"
}

foreach ($container in $containers) {
    $existingContainer = (& docker ps -a --filter "name=^/$container$" --format "{{.Names}}")
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect existing Docker container names."
    }
    if ($existingContainer -ne $container) {
        Write-Host "Container is already absent: $container"
        continue
    }

    $labelsJson = (& docker inspect --format "{{json .Config.Labels}}" $container)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect ownership label for container: $container"
    }
    $labels = $labelsJson | ConvertFrom-Json
    $labelProperty = $labels.PSObject.Properties[$labelName]
    $actualLabel = if ($null -eq $labelProperty) { $null } else { $labelProperty.Value }
    if ($actualLabel -ne $ownershipLabel) {
        Write-Warning "Skipping container without the expected ownership label: $container"
        continue
    }

    & docker stop $container
    if ($LASTEXITCODE -ne 0) {
        throw "Could not stop container: $container"
    }
}
