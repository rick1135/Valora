---
title: Codebase Structure
mapped_at: 2026-05-18
status: current
---

# Structure

## Root

Key root files:

- `pom.xml`: Maven project and dependencies
- `mvnw`, `mvnw.cmd`: Maven wrapper scripts
- `Dockerfile`: application image build
- `docker-compose.yml`: local PostgreSQL, Redis, and app services
- `readme.md`: project overview and endpoint summary
- `.env.example`: local environment template
- `AGENTS.md`: project-specific agent instructions

## Application Source

Main source root:

- `src/main/java/com/rick1135/Valora`

Primary packages:

- `client`: external HTTP clients
- `common`: financial constants
- `config`: Spring beans and infrastructure configuration
- `controller`: REST controllers
- `dto`: request, response, and external API DTOs
- `entity`: JPA entities and enums
- `exception`: domain exceptions and global handler
- `mapper`: MapStruct mappers
- `repository`: Spring Data repositories and projections
- `security`: filter chain and JWT request filter
- `service`: business logic and financial calculations

## Controllers

Controllers are under `src/main/java/com/rick1135/Valora/controller`.

- `AuthController`: login, registration, refresh token
- `AssetController`: asset registration, listing, ticker search
- `TransactionController`: transaction registration and history
- `PortfolioController`: portfolio CRUD, positions, summary
- `ProventController`: provent creation, authenticated portfolio provents, BRAPI sync trigger

Controllers expose DTOs, not entities.

## DTOs

Request DTOs:

- `src/main/java/com/rick1135/Valora/dto/request`

Response DTOs:

- `src/main/java/com/rick1135/Valora/dto/response`

BRAPI DTOs:

- `src/main/java/com/rick1135/Valora/dto/brapi`

Request DTOs use Jakarta validation annotations for required fields, positive financial amounts, date constraints, and size constraints.

## Services

Core service files:

- `UserService`: registration and default portfolio creation
- `TokenService`: access and refresh token handling
- `AssetService`: asset registration and search
- `TransactionService`: buy/sell processing and history filters
- `PortfolioService`: portfolio ownership, CRUD, summary orchestration
- `PositionService`: position list with current price/profitability
- `QuoteService`: quote lookup and Redis caching
- `ProventService`: manual/API provent event creation and provisioning
- `ProventSyncService`: scheduled/manual BRAPI dividend sync
- `ProventStatusTransitionJob`: pending-to-paid status transition
- `PortfolioPerformanceCalculator`: portfolio summary calculations
- `FixedIncomeYieldCalculator`: fixed-income current value estimate
- `MarketCalendar`: market-zone date conversion

## Entities

Main JPA entities:

- `User`
- `Asset`
- `Portfolio`
- `Transaction`
- `Position`
- `Provent`
- `ProventProvision`

Domain enums include:

- `UserRole`
- `AssetCategory`
- `FixedIncomeIndexer`
- `TransactionType`
- `ProventType`
- `ProventStatus`
- `ProventSource`
- `ProventRateBasis`

## Repositories

Repositories are under `src/main/java/com/rick1135/Valora/repository`.

- `UserRepository`
- `AssetRepository`
- `PortfolioRepository`
- `TransactionRepository`
- `PositionRepository`
- `ProventRepository`
- `ProventProvisionRepository`

Projection:

- `repository/projection/UserAssetHoldingProjection.java`

## Resources

Runtime configuration:

- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/main/resources/application-prod.yml`

Database migrations:

- `src/main/resources/db/migration`

Test configuration:

- `src/test/resources/application-test.yml`

## Tests

Tests are under `src/test/java/com/rick1135/Valora`.

Test packages mirror production layers:

- `client`
- `controller`
- `repository`
- `service`
- `support`
- `config`

PostgreSQL integration support:

- `support/AbstractPostgresIntegrationTest.java`

Test Redis override:

- `config/TestRedisConfig.java`
