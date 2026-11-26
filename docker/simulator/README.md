# Simulator webhook sidecar

The challenge simulator sends webhooks to the fixed URL `http://localhost:3003/webhook`. In a bridge-network container, `localhost` is the container network namespace rather than the Windows host. The `EXTERNAL_API_URL` environment variable is present in the container but is not used by image `cfontes0estapar/garage-sim:1.0.0` for webhook delivery.

The companion Nginx container joins the simulator network namespace with `--network container:<simulator>`. It listens on port `3003` only inside that shared namespace and proxies `/webhook` through the IPv4 Docker host gateway to `http://host.docker.internal:3003/webhook`. The startup script assigns the gateway the private alias `estapar-host-gateway`; this avoids Docker Desktop preferring an unreachable IPv6 address in this shared-network mode. Port 3003 is not published by the sidecar. Nginx preserves the request method, JSON body, relevant headers and the backend response status; `proxy_intercept_errors off` prevents 4xx and 5xx responses from being converted to success.

## Safe E2E environment

From Windows PowerShell 5.1 at the repository root:

```powershell
.\scripts\start-simulator.ps1

$env:DB_URL = "jdbc:mysql://localhost:3308/estapar_parking_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USERNAME = "estapar_e2e"
$env:DB_PASSWORD = "estapar_e2e"
$env:GARAGE_SIMULATOR_URL = "http://localhost:3001"
$env:REVENUE_ZONE_ID = "America/Sao_Paulo"
.\mvnw.cmd spring-boot:run
```

The startup script creates only three labelled, disposable containers:

- `estapar-e2e-mysql`, published on `127.0.0.1:3308`;
- `estapar-e2e-simulator`, published on `127.0.0.1:3001`;
- `estapar-e2e-webhook-proxy`, sharing the simulator network namespace and publishing no port.

It does not call `/garage`, because that request starts automatic event generation. Spring Boot calls it during `ApplicationReadyEvent`, synchronizes the garage and marks readiness before accepting parking events. The simulator's first automatic event is normally emitted on its next five-second cycle.

Useful checks after Spring Boot is ready:

```powershell
Invoke-RestMethod http://localhost:3003/actuator/health/readiness
docker logs estapar-e2e-webhook-proxy
docker logs estapar-e2e-simulator
docker exec estapar-e2e-webhook-proxy wget -qO- http://estapar-host-gateway:3003/actuator/health/readiness
```

Stop only these resources with:

```powershell
.\scripts\stop-simulator.ps1
```

The stop script verifies the ownership label before stopping a container. Because the containers use `--rm`, their isolated database is removed when stopped. It never removes the development MySQL volume or resources from another project.

The official simulator emits timestamp strings without a zone designator while its clock runs in UTC. The webhook DTOs interpret that simulator form as UTC and continue accepting ISO-8601 instants with `Z` or an explicit offset. Invalid timestamps are still rejected with HTTP 400.

The real E2E run on 2026-11-25 observed automatic ENTRY, PARKED and EXIT requests, HTTP 200 for valid events, a propagated HTTP 400 for a controlled invalid body, and HTTP 502 after the backend was deliberately stopped. The final isolated snapshot had 20 sessions (7 EXITED and 13 PARKED), 13 occupied spots, and no integrity violations. The generated stays were below the 30-minute free tolerance, so persisted revenue was BRL 0.00 for both sectors.
