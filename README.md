# Estapar Parking Backend

Bootstrap do backend para o desafio técnico Estapar Backend Developer Test V1.5. Nesta primeira etapa, o projeto contém somente a infraestrutura inicial; endpoints de negócio, entidades JPA e migrations de tabelas ficam fora do escopo.

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

Não é necessário instalar o Maven globalmente, pois o projeto inclui o Maven Wrapper.

## Configuração local

As configurações possuem defaults adequados ao ambiente local. Para personalizá-las, copie o exemplo e altere apenas o arquivo `.env`, que não é versionado:

```powershell
Copy-Item .env.example .env
```

Ao executar a aplicação diretamente no Windows, as variáveis `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` podem ser definidas no PowerShell. Sem elas, a aplicação usa o MySQL do Compose em `localhost:3307`.

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

Com o MySQL saudável:

```powershell
.\mvnw.cmd spring-boot:run
```

A aplicação ficará disponível em `http://localhost:3003`.

## Executar os testes

```powershell
.\mvnw.cmd clean verify
```

O teste inicial é unitário e não depende do MySQL.

## Health check

Com a aplicação em execução:

```powershell
Invoke-RestMethod http://localhost:3003/actuator/health
```

O retorno esperado quando a aplicação e o banco estiverem disponíveis é:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

## Escopo implementado

O projeto inclui o bootstrap da aplicação e o modelo inicial de persistência. Regras de negócio e endpoints de garagem, webhook, faturamento e receita permanecem fora do escopo.

## Modelo de persistência

O esquema possui setores, vagas, sessões de estacionamento e uma linha de controle global. Horários são representados por `Instant` na aplicação e persistidos em UTC como `DATETIME(6)`.

Uma sessão `ENTERED` pode existir sem vaga ou setor. A associação física ocorre posteriormente no evento `PARKED`; a capacidade declarada de um setor é definida por `max_capacity` e não pela quantidade de vagas cadastradas.

Para impedir concorrência indevida, `parking_session` utiliza duas colunas virtuais geradas pelo MySQL:

- `active_license_plate` contém a placa apenas para sessões `ENTERED` ou `PARKED` e possui índice único;
- `active_parking_spot_id` contém a vaga apenas para sessões `PARKED` e também possui índice único.

Sessões `EXITED` produzem `NULL` nessas colunas. Como índices únicos do MySQL permitem múltiplos valores `NULL`, o histórico é preservado sem impedir novas sessões para a mesma placa ou vaga.
