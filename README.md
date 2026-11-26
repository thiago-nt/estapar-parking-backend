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
- `REVENUE_ZONE_ID` (padrão `America/Sao_Paulo`)

## Subir o MySQL

```powershell
docker compose up -d
docker compose ps
```

O banco `estapar_parking` ficará disponível em `localhost:3307`. Para encerrar o container sem remover os dados:

```powershell
docker compose down
```

## Integração automática com o simulador no Windows

A imagem oficial envia webhooks para a URL fixa `http://localhost:3003/webhook` dentro do próprio container. A variável `EXTERNAL_API_URL` não altera esse destino. Por isso, um sidecar Nginx compartilha o namespace de rede do simulador, atende essa URL local e encaminha a requisição para o backend no Windows. O sidecar não publica a porta 3003, não armazena eventos e propaga os status HTTP reais.

O ambiente E2E é descartável e não usa o MySQL de desenvolvimento:

```text
simulador :3000 ── POST localhost:3003 ──> Nginx sidecar
                                               │
                                               └──> host Docker :3003 ──> Spring Boot
simulador publicado em 127.0.0.1:3001              │
MySQL E2E publicado em 127.0.0.1:3308 <────────────┘
```

Com as portas `3001`, `3003` e `3308` livres, execute no Windows PowerShell 5.1:

```powershell
.\scripts\start-simulator.ps1

$env:DB_URL = "jdbc:mysql://localhost:3308/estapar_parking_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USERNAME = "estapar_e2e"
$env:DB_PASSWORD = "estapar_e2e"
$env:GARAGE_SIMULATOR_URL = "http://localhost:3001"
$env:REVENUE_ZONE_ID = "America/Sao_Paulo"
.\mvnw.cmd spring-boot:run
```

A ordem é: MySQL isolado, simulador, proxy, Spring Boot, sincronização de setores/vagas e readiness. O `GET /garage` feito após `ApplicationReadyEvent` inicia o simulador; a aplicação só marca a garagem como pronta depois do commit da sincronização. Não há sleep artificial nem resposta antecipada aos webhooks.

Em outro PowerShell, acompanhe a execução sem produzir manualmente os eventos:

```powershell
Invoke-RestMethod http://localhost:3003/actuator/health/readiness
docker logs -f estapar-e2e-webhook-proxy
docker logs -f estapar-e2e-simulator
Invoke-RestMethod "http://localhost:3003/revenue?date=2026-11-25&sector=A"
```

Use a data de negócio correspondente ao `exit_time`, no fuso `REVENUE_ZONE_ID`. Para encerrar e remover somente os três containers E2E identificados por label:

```powershell
.\scripts\stop-simulator.ps1
```

Detalhes da topologia e dos comandos estão em [`docker/simulator/README.md`](docker/simulator/README.md).

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

Os testes de persistência usam um MySQL 8.4 isolado com Testcontainers e nunca apontam para o banco local. O simulador real não é iniciado pelos testes automatizados. A suíte cobre o fluxo completo `ENTRY -> PARKED -> EXIT -> REVENUE`, reentregas idempotentes, conflitos de estado e vaga, recuperação de capacidade, rollback, concorrência, preço dinâmico, tarifa horária e limites de data no fuso de negócio.

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
- `404`: vaga não encontrada para as coordenadas informadas;
- `409`: capacidade esgotada, sequência inválida, preço congelado incompleto ou estado inconsistente de sessão/vaga;
- `415`: `Content-Type` diferente de `application/json`;
- `503`: configuração da garagem ainda não pronta;
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
  -Body $body
```

### Processamento transacional de ENTRY

O evento `ENTRY` possui processamento real. Depois de confirmar que a configuração está pronta, a aplicação executa em uma única transação:

1. lock pessimista de escrita na linha `garage_state` de ID `1`;
2. busca da sessão ativa para a placa;
3. verificação de idempotência por placa e `entry_time`;
4. soma da capacidade declarada de todos os setores;
5. contagem de sessões `ENTERED` e `PARKED`;
6. criação de uma sessão `ENTERED`, sem setor ou vaga associados.

A capacidade global é `SUM(sector.max_capacity)`, independentemente da quantidade de registros em `parking_spot`. Veículos `ENTERED` já consomem capacidade mesmo antes de estacionarem. Quando a ocupação alcança a capacidade, novas entradas recebem `409` e nenhuma sessão ou vaga é alterada.

Uma reentrega com a mesma placa e o mesmo `entry_time` retorna `200` sem criar outra sessão, inclusive se a garagem ficou cheia depois da primeira entrega. A mesma placa com outro horário enquanto possui sessão ativa recebe `409`. Depois que a sessão anterior estiver `EXITED`, uma nova entrada é permitida.

O lock `PESSIMISTIC_WRITE` serializa a decisão de capacidade entre requisições e também com a sincronização da garagem. A restrição `uk_parking_session_active_license_plate` permanece como proteção adicional no MySQL.

Como o contrato não possui `event_id`, não é possível diferenciar perfeitamente uma reentrega antiga de uma nova entrada quando a sessão anterior já foi encerrada.

### Processamento transacional de PARKED

O evento `PARKED` realiza a transição `ENTERED -> PARKED` em uma única transação. Depois de confirmar que a configuração está pronta, a aplicação:

1. obtém lock pessimista de escrita na linha `garage_state` de ID `1`;
2. busca a sessão ativa da placa;
3. localiza a vaga pela igualdade exata de `lat` e `lng` em `DECIMAL(10,7)`;
4. valida o estado da sessão, a disponibilidade da vaga e a capacidade do setor;
5. calcula a ocupação do setor após a associação e captura o preço-base e o multiplicador vigentes;
6. altera a sessão para `PARKED` e a vaga para `OCCUPIED`.

Uma reentrega para a mesma vaga retorna `200` sem alterar `parked_at` nem o preço capturado. A tentativa de mover uma sessão já estacionada para outra vaga recebe `409`. Placa sem sessão ativa, sessão encerrada, vaga ocupada ou setor sem capacidade também recebem `409`; coordenadas desconhecidas recebem `404`.

A máquina de estados permite `ENTERED -> PARKED -> EXITED`. `PARKED -> PARKED` só é aceito como repetição idempotente na mesma vaga; sessões `EXITED` não são reabertas.

A capacidade do setor considera apenas sessões `PARKED`. O evento não volta a consumir capacidade global, pois o veículo já foi contabilizado no `ENTRY`. Os campos `base_price_at_parking` e `applied_multiplier` são congelados no primeiro PARKED; `amount` permanece nulo até o processamento do `EXIT`.

Como o simulador não informa o horário do estacionamento, `parked_at` registra, em UTC, o instante em que o evento foi processado. Esse valor é uma estimativa operacional; o tempo de cobrança continuará baseado em `entry_time` e `exit_time`.

O lock global mantém a mesma ordem de sincronização usada pelo `ENTRY` e pela carga da garagem. As restrições únicas do MySQL para placa e vaga ativas permanecem como proteção adicional contra concorrência.

### Preço dinâmico e tarifa horária

O multiplicador considera somente sessões `PARKED` no setor da vaga. A ocupação usada é a quantidade já persistida mais o veículo atual, contado exatamente uma vez antes da alteração das entidades:

| Ocupação após PARKED | Multiplicador |
| --- | ---: |
| menor que 25% | `0.90` |
| de 25% até 50%, inclusive | `1.00` |
| acima de 50% até 75%, inclusive | `1.10` |
| acima de 75% até 100%, inclusive | `1.25` |

Os limites são comparados por aritmética inteira, sem arredondar previamente o percentual. O lock pessimista global serializa PARKED concorrentes, fazendo cada transação observar a ocupação correspondente à sua ordem efetiva. Ocupação de outros setores não participa do cálculo.

O primeiro PARKED captura `sector.base_price` e o multiplicador em `parking_session`. Reentregas idempotentes não recalculam nem alteram preço, multiplicador, `parked_at` ou `amount`, mesmo que o setor seja reconfigurado depois.

O cálculo tarifário usado no `EXIT` segue:

```text
amount = horas_cobráveis × preço-base-capturado × multiplicador-capturado
```

Até 30 minutos, inclusive, o valor é `0.00`. Acima dessa tolerância, toda a duração desde a entrada é cobrada e arredondada para cima em blocos de uma hora; por exemplo, 60 minutos e 1 milissegundo resultam em duas horas. Os cálculos usam `Duration` e `BigDecimal`, com `RoundingMode.HALF_UP` e escala monetária de duas casas somente no resultado final.

Sessões legadas podem possuir `applied_multiplier` nulo. Não existe backfill com a ocupação atual, pois isso reconstruiria um preço historicamente incorreto. O `EXIT` rejeita essas sessões com `409`, preservando a sessão `PARKED` e a vaga `OCCUPIED`, sem cobrar valor arbitrário.

### Processamento transacional de EXIT

O evento `EXIT` realiza a transição `PARKED -> EXITED` e libera a vaga na mesma transação:

1. confirma o readiness e obtém `PESSIMISTIC_WRITE` na linha `garage_state` de ID `1`;
2. procura primeiro uma sessão `EXITED` com a mesma placa e o mesmo `exit_time` em precisão de microssegundos;
3. quando não é uma repetição, busca a sessão ativa e exige o estado `PARKED`;
4. valida `exit_time >= entry_time`, os valores financeiros congelados e a vaga `OCCUPIED` associada;
5. calcula `amount` com `PricingService`, usando somente entrada, saída, preço-base e multiplicador congelados;
6. grava `EXITED`, `exit_time` e `amount`, preservando vaga e setor como histórico;
7. altera a vaga para `AVAILABLE` e confirma todas as mudanças atomicamente.

Uma sessão apenas `ENTERED` recebe `409`: sem PARKED não há vaga, setor e preço congelado confiáveis, portanto o sistema não inventa uma tarifa. O horário do evento pode ser histórico e não é comparado com `parked_at`, que representa apenas o instante de processamento; a validação e a cobrança usam `entry_time` e `exit_time`.

O EXIT duplicado com a mesma placa e horário retorna `200` sem recalcular ou atualizar dados. A busca histórica acontece antes da sessão ativa, então uma repetição antiga não encerra uma nova entrada da mesma placa. Se o horário antigo for anterior à nova entrada e não corresponder a um EXIT registrado, a nova sessão também não é encerrada. Sem `event_id`, eventos antigos não registrados cujo horário seja compatível com uma sessão nova não podem ser distinguidos perfeitamente.

O mesmo lock global serializa EXIT com ENTRY e PARKED. Após o commit de EXIT, a sessão deixa de participar de `COUNT(status IN ('ENTERED', 'PARKED'))`, liberando capacidade para uma nova entrada. EXITs simultâneos para a mesma sessão resultam em uma finalização e uma resposta idempotente. Qualquer falha ao liberar a vaga reverte também status, horário e valor da sessão.

## Consulta de receita

`GET http://localhost:3003/revenue` consolida a receita diária de um setor. Query parameters são a interface HTTP principal: `sector` é obrigatório e `date`, no formato ISO `yyyy-MM-dd`, é opcional. Sem data, a consulta usa o dia atual no fuso configurado por `REVENUE_ZONE_ID`.

```powershell
Invoke-RestMethod "http://localhost:3003/revenue?date=2025-01-01&sector=A"
```

Por compatibilidade com o contrato do desafio, um body JSON também é aceito em `GET`, sem ser obrigatório:

```json
{
  "date": "2025-01-01",
  "sector": "A"
}
```

Quando query e body informam o mesmo campo, os valores precisam ser equivalentes; divergências retornam `400`. A resposta tem o formato:

```json
{
  "amount": 72.90,
  "currency": "BRL",
  "timestamp": "2026-11-25T20:00:00Z"
}
```

A receita é `SUM(parking_session.amount)` somente de sessões `EXITED` do setor, atribuída pela data local de `exit_time`. O valor já congelado e persistido no EXIT é a única fonte financeira: a consulta não recalcula tarifa, multiplicador ou preço atual do setor. Um setor existente sem saídas retorna `0.00`; saídas ainda não confirmadas não ficam visíveis.

Os limites são calculados como `[início do dia, início do próximo dia)` em `REVENUE_ZONE_ID` e convertidos para UTC. No dia atual, o limite superior é o instante do `Clock`; datas futuras retornam zero. Isso evita `DATE(exit_time)` e preserva o uso do índice existente `(sector_id, exit_time)`.

### Códigos HTTP de receita

- `200`: consulta válida, inclusive com receita `0.00`;
- `400`: setor ausente, data inválida, JSON malformado ou conflito entre query e body;
- `404`: setor inexistente;
- `503`: configuração da garagem ainda não pronta;
- `500`: falha interna inesperada.

Não existe tabela ou cache de receita. Pagamentos e novas APIs permanecem fora do escopo desta etapa.

### Resultado E2E com o simulador real

Em 25 de novembro de 2026, o ambiente isolado sincronizou 2 setores e 30 vagas e recebeu automaticamente `ENTRY`, `PARKED` e `EXIT` da imagem `cfontes0estapar/garage-sim:1.0.0`. A amostra final continha 20 sessões: 7 `EXITED` e 13 `PARKED`; havia 13 vagas `OCCUPIED` e 17 `AVAILABLE`. Todos os webhooks válidos observados receberam `200` do backend e o sidecar propagou tanto um `400` controlado quanto `502` quando o backend foi encerrado.

As 7 permanências geradas nessa execução ficaram dentro da tolerância gratuita de 30 minutos. Assim, os valores persistidos e as receitas consultadas foram `A = 0.00 BRL` (3 saídas) e `B = 0.00 BRL` (4 saídas), sem recalcular a regra financeira. As consultas de integridade não encontraram placa ativa duplicada, vaga ativa duplicada, ocupação acima da capacidade, sessão estacionada em vaga livre ou saída sem valor.

O simulador envia `entry_time` e `exit_time` sem offset, embora seu relógio esteja em UTC. O webhook aceita esse formato específico como UTC e continua aceitando o contrato ISO-8601 com `Z` ou offset. Essa compatibilidade fica restrita à desserialização dos horários do webhook; persistência, regra financeira e contrato de resposta não foram alterados.

O simulador não oferece comandos determinísticos para duplicidade, inversão de ordem, lotação ou concorrência. Esses cenários permanecem cobertos pelos testes automatizados transacionais; o E2E real valida entrega HTTP e o fluxo espontâneo, não substitui a suíte. Como os containers usam `--rm`, o banco E2E é intencionalmente efêmero.

## Modelo de persistência

O esquema possui setores, vagas, sessões de estacionamento e uma linha de controle global. Horários são representados por `Instant` na aplicação e persistidos em UTC como `DATETIME(6)`.

Uma sessão `ENTERED` pode existir sem vaga ou setor. A associação física ocorre posteriormente no evento `PARKED`; a capacidade declarada de um setor é definida por `max_capacity` e não pela quantidade de vagas cadastradas.

Para impedir concorrência indevida, `parking_session` utiliza duas colunas virtuais geradas pelo MySQL:

- `active_license_plate` contém a placa apenas para sessões `ENTERED` ou `PARKED` e possui índice único;
- `active_parking_spot_id` contém a vaga apenas para sessões `PARKED` e também possui índice único.

Sessões `EXITED` produzem `NULL` nessas colunas. Como índices únicos do MySQL permitem múltiplos valores `NULL`, o histórico é preservado sem impedir novas sessões para a mesma placa ou vaga.
