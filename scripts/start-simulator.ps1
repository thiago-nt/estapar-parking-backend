param(
    [ValidateRange(1, 65535)]
    [int]$SimulatorPort = 3001,

    [ValidateRange(1, 65535)]
    [int]$MySqlPort = 3308
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mysqlContainer = "estapar-e2e-mysql"
$simulatorContainer = "estapar-e2e-simulator"
$proxyContainer = "estapar-e2e-webhook-proxy"
$backendPort = 3003
$ownershipLabel = "br.com.estapar.parking.e2e=true"
$createdContainers = New-Object System.Collections.Generic.List[string]
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$nginxConfig = (Resolve-Path (Join-Path $repositoryRoot "docker\simulator\nginx.conf")).Path

function Invoke-Docker {
    param([string[]]$DockerArguments)

    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($DockerArguments -join ' ')"
    }
}

function Assert-CommandAvailable {
    param([string]$Command)

    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        throw "Required command was not found: $Command"
    }
}

function Assert-PortAvailable {
    param([int]$Port)

    $activeListeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    $listener = $activeListeners |
        Where-Object { $_.Port -eq $Port } |
        Select-Object -First 1
    if ($null -ne $listener) {
        throw "TCP port $Port is already in use. No existing service was changed."
    }
}

function Assert-ContainerNameAvailable {
    param([string]$ContainerName)

    $existingContainer = (& docker ps -a --filter "name=^/$ContainerName$" --format "{{.Names}}")
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect existing Docker container names."
    }
    if ($existingContainer -eq $ContainerName) {
        throw "Container name already exists: $ContainerName. No existing container was changed."
    }
}

function Stop-CreatedContainers {
    for ($index = $createdContainers.Count - 1; $index -ge 0; $index--) {
        & docker stop $createdContainers[$index] *> $null
    }
}

Assert-CommandAvailable "docker"
Invoke-Docker @("info", "--format", "{{.ServerVersion}}")
Assert-PortAvailable $SimulatorPort
Assert-PortAvailable $MySqlPort
Assert-PortAvailable $backendPort
Assert-ContainerNameAvailable $mysqlContainer
Assert-ContainerNameAvailable $simulatorContainer
Assert-ContainerNameAvailable $proxyContainer

try {
    Invoke-Docker @(
        "run", "--rm", "-d",
        "--name", $mysqlContainer,
        "--label", $ownershipLabel,
        "-e", "MYSQL_DATABASE=estapar_parking_e2e",
        "-e", "MYSQL_USER=estapar_e2e",
        "-e", "MYSQL_PASSWORD=estapar_e2e",
        "-e", "MYSQL_ROOT_PASSWORD=estapar_e2e_root",
        "-p", "127.0.0.1:${MySqlPort}:3306",
        "--health-cmd", "mysqladmin ping -h localhost -uroot -pestapar_e2e_root --silent",
        "--health-interval", "2s",
        "--health-timeout", "2s",
        "--health-retries", "30",
        "mysql:8.4"
    )
    $createdContainers.Add($mysqlContainer)

    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        $health = (& docker inspect --format "{{.State.Health.Status}}" $mysqlContainer 2>$null)
        if ($LASTEXITCODE -ne 0) {
            throw "Could not inspect the E2E MySQL container health."
        }
        if ($health -eq "healthy") {
            break
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "E2E MySQL did not become healthy within 90 seconds."
        }
        Start-Sleep -Seconds 2
    } while ($true)

    Invoke-Docker @(
        "run", "--rm", "-d",
        "--name", $simulatorContainer,
        "--label", $ownershipLabel,
        "--add-host", "estapar-host-gateway:host-gateway",
        "-p", "127.0.0.1:${SimulatorPort}:3000",
        "cfontes0estapar/garage-sim:1.0.0"
    )
    $createdContainers.Add($simulatorContainer)

    Invoke-Docker @(
        "run", "--rm", "-d",
        "--name", $proxyContainer,
        "--label", $ownershipLabel,
        "--network", "container:$simulatorContainer",
        "-v", "${nginxConfig}:/etc/nginx/nginx.conf:ro",
        "nginx:1.27-alpine"
    )
    $createdContainers.Add($proxyContainer)

    Invoke-Docker @("exec", $proxyContainer, "nginx", "-t")
    Invoke-Docker @("exec", $proxyContainer, "getent", "hosts", "estapar-host-gateway")

    Write-Host "E2E infrastructure is ready."
    Write-Host "Simulator: http://localhost:$SimulatorPort"
    Write-Host "MySQL:    jdbc:mysql://localhost:$MySqlPort/estapar_parking_e2e"
    Write-Host "Start Spring Boot with the isolated DB_URL and GARAGE_SIMULATOR_URL shown in docker/simulator/README.md."
} catch {
    Stop-CreatedContainers
    throw
}
