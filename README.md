# Estapar Parking Backend

Backend do desafio técnico Estapar Backend Developer Test V1.5. O projeto possui o modelo inicial de persistência e sincroniza automaticamente a configuração de setores e vagas fornecida pelo simulador Estapar.

## Tecnologias

- Java 21
- Spring Boot 4.1.3
- Spring MVC / REST
- Spring Data JPA e Bean Validation
- MySQL 8.4
- Flyway
- Spring Boot Actuator
- Maven Wrapper e JUnit 5
- Docker Compose

## Pré-requisitos

- JDK 21 disponível no `PATH`
- Docker Desktop com Docker Compose
- Portas locais `3003` e `3307` livres
- Docker disponível para executar o MySQL, o simulador e os testes de integração

Não é necessário instalar o Maven globalmente, pois o projeto inclui o Maven Wrapper.

## Configuração local

As configurações possuem defaults adequados ao ambiente local. Para personalizá-las, copie o exemplo e altere apenas o arquivo `.env`, que não é versionado:

```powershell
Copy-Item .env.example .env
```

Ao executar a aplicação diretamente no Windows, as variáveis `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` podem ser definidas no PowerShell. Sem elas, a aplicação usa o MySQL do Compose em `localhost:3307`.

A integração consulta `http://localhost:3000/garage` por padrão. A URL e os limites de comunicação podem ser alterados pelas variáveis:

- `GARAGE_SIMULATOR_URL`
- `GARAGE_SIMULATOR_CONNECTION_TIMEOUT`
- `GARAGE_SIMULATOR_READ_TIMEOUT`
- `GARAGE_SIMULATOR_RETRY_MAX_ATTEMPTS`
- `GARAGE_SIMULATOR_RETRY_BACKOFF`
- `GARAGE_SIMULATOR_ENABLED`

## Subir o MySQL

```powershell
docker compose up -d
docker compose ps
```

O banco `estapar_parking` ficará disponível em `localhost:3307`. Para encerrar o container sem remover os dados:

```powershell
docker compose down
```

## Executar a aplicação no Windows

Com o MySQL e o simulador saudáveis neste ambiente local, no qual a porta `3000` pertence ao Grafana:

```powershell
$env:GARAGE_SIMULATOR_URL = "http://localhost:3001"
.\mvnw.cmd spring-boot:run
```

A aplicação ficará disponível em `http://localhost:3003`.

Se a porta `3000` estiver ocupada, publique a porta interna `3000` do simulador em outra porta e ajuste a URL. Por exemplo:

```powershell
docker run -d --name estapar-garage-simulator-3001 `
  -p 3001:3000 `
  -e EXTERNAL_API_URL=http://host.docker.internal:3003/webhook `
  cfontes0estapar/garage-sim:1.0.0
$env:GARAGE_SIMULATOR_URL = "http://localhost:3001"
.\mvnw.cmd spring-boot:run
```

## Sincronização inicial

Após o servidor HTTP ficar disponível, a aplicação executa o seguinte fluxo:

1. consulta `GET /garage` sem manter uma transação de banco aberta;
2. desserializa e valida o contrato completo;
3. obtém um lock na linha global `garage_state`;
4. insere ou atualiza setores e vagas em uma única transação;
5. marca a inicialização como pronta somente depois do commit.

A sincronização é idempotente: uma segunda carga idêntica não cria duplicidades nem atualiza registros sem mudanças. Vagas novas recebem status `AVAILABLE`; vagas existentes preservam seu status. Alterações de setor ou coordenadas de uma vaga `OCCUPIED` são rejeitadas e toda a transação sofre rollback. Setores e vagas omitidos pelo simulador não são removidos.

Falhas transitórias de conexão e respostas HTTP 5xx recebem um número limitado de tentativas com backoff. Erros de contrato ou conflitos de dados não são repetidos. Se a carga não terminar com sucesso, o health check retorna `OUT_OF_SERVICE` e a aplicação não é considerada pronta para processar eventos.

## Executar os testes

```powershell
.\mvnw.cmd clean verify
```

Os testes de persistência usam um MySQL 8.4 isolado com Testcontainers e nunca apontam para o banco local. O simulador real não é iniciado pelos testes automatizados.

## Health check

Com a aplicação em execução:

```powershell
Invoke-RestMethod http://localhost:3003/actuator/health
Invoke-RestMethod http://localhost:3003/actuator/health/readiness
```

O retorno esperado quando a aplicação, o banco e a sincronização inicial estiverem disponíveis é:

```json
{"status":"UP"}
```

## Verificar os registros no MySQL

```powershell
docker exec estapar-parking-mysql mysql -uestapar -pestapar estapar_parking -e `
  "SELECT id, code, base_price, max_capacity FROM sector; SELECT id, sector_id, latitude, longitude, status FROM parking_spot ORDER BY id;"
```

Para conferir relacionamentos e duplicidades:

```powershell
docker exec estapar-parking-mysql mysql -uestapar -pestapar estapar_parking -e `
  "SELECT s.code, COUNT(p.id) spots FROM sector s LEFT JOIN parking_spot p ON p.sector_id = s.id GROUP BY s.id, s.code; SELECT latitude, longitude, COUNT(*) total FROM parking_spot GROUP BY latitude, longitude HAVING total > 1;"
```

## Webhook de eventos

O endpoint `POST http://localhost:3003/webhook` recebe exclusivamente `Content-Type: application/json`. O campo `event_type` seleciona um dos três contratos.

ENTRY:

```json
{
  "license_plate": "ZUL0001",
  "entry_time": "2025-01-01T12:00:00.000Z",
  "event_type": "ENTRY"
}
```

PARKED:

```json
{
  "license_plate": "ZUL0001",
  "lat": -23.561684,
  "lng": -46.655981,
  "event_type": "PARKED"
}
```

EXIT:

```json
{
  "license_plate": "ZUL0001",
  "exit_time": "2025-01-01T13:30:00.000Z",
  "event_type": "EXIT"
}
```

Os timestamps usam ISO-8601 UTC e são convertidos para `Instant`. As coordenadas usam `BigDecimal`, preservando a precisão recebida.

### Códigos HTTP

- `200`: evento validado e efetivamente processado;
- `400`: JSON malformado, tipo desconhecido ou campo inválido;
- `415`: `Content-Type` diferente de `application/json`;
- `503`: configuração da garagem ainda não pronta ou processador indisponível;
- `500`: falha interna inesperada.

As respostas de erro possuem os campos JSON `code` e `message`, sem stack trace.

### Testar no PowerShell

```powershell
$body = @{
  license_plate = "ZUL0001"
  entry_time = "2025-01-01T12:00:00.000Z"
  event_type = "ENTRY"
} | ConvertTo-Json

Invoke-WebRequest `
  -Method Post `
  -Uri http://localhost:3003/webhook `
  -ContentType "application/json" `
  -Body $body `
  -SkipHttpErrorCheck
```

### Limitação temporária

Nesta etapa, o roteamento e a validação estão implementados, mas as operações de banco para `ENTRY`, `PARKED` e `EXIT` ainda não existem. A implementação provisória retorna `503` para eventos válidos; ela não cria sessões nem altera vagas. O caminho `200` é exercitado apenas em testes com um processador mockado.

A aplicação deve usar `GARAGE_SIMULATOR_URL=http://localhost:3001` para consultar a garagem. A imagem do simulador atualmente tenta entregar o webhook em `localhost:3003` a partir do próprio container e pode não respeitar a variável externa configurada. Em Docker Desktop com rede bridge, isso não aponta automaticamente para o host; consulte os logs do container e não trate falhas de entrega como processamento bem-sucedido.

O próximo passo é substituir o processador provisório pelas regras transacionais de entrada, estacionamento e saída do Dia 2.

## Modelo de persistência

O esquema possui setores, vagas, sessões de estacionamento e uma linha de controle global. Horários são representados por `Instant` na aplicação e persistidos em UTC como `DATETIME(6)`.

Uma sessão `ENTERED` pode existir sem vaga ou setor. A associação física ocorre posteriormente no evento `PARKED`; a capacidade declarada de um setor é definida por `max_capacity` e não pela quantidade de vagas cadastradas.

Para impedir concorrência indevida, `parking_session` utiliza duas colunas virtuais geradas pelo MySQL:

- `active_license_plate` contém a placa apenas para sessões `ENTERED` ou `PARKED` e possui índice único;
- `active_parking_spot_id` contém a vaga apenas para sessões `PARKED` e também possui índice único.

Sessões `EXITED` produzem `NULL` nessas colunas. Como índices únicos do MySQL permitem múltiplos valores `NULL`, o histórico é preservado sem impedir novas sessões para a mesma placa ou vaga.
