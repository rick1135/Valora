# Valora - Investment Portfolio API

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-brightgreen?style=for-the-badge&logo=spring)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=for-the-badge&logo=postgresql)
![Redis](https://img.shields.io/badge/Redis-7-red?style=for-the-badge&logo=redis)
![Docker](https://img.shields.io/badge/Docker-Ready-blue?style=for-the-badge&logo=docker)

**Valora** é uma API RESTful focada no controle de patrimônio financeiro. O sistema permite o registro de transações (compra e venda), cálculo automático de preço médio e consolidação
de carteira em tempo real.

## Status do Projeto

O projeto está sendo desenvolvido em fases:

- [x] **Fase 1:** Fundação, Autenticação JWT e Segurança;
- [x] **Fase 2:** Dicionário de Ativos e Paginação;
- [x] **Fase 3:** Motor de Transações, Posição Consolidada e Cálculo de Preço Médio;
- [x] **Fase 4:** Integração com APIs externas (Cotações em Tempo Real) e Cálculo de Rentabilidade;
- [ ] **Fase 5:** Dashboards e Relatórios.

---

## Funcionalidades

### Já implementadas
* **Autenticação e Autorização:**
    * Login e Registro de usuários com JWT (JSON Web Tokens).
    * Senhas criptografadas utilizando `BCryptPasswordEncoder`.
    * Filtro de segurança customizado interceptando requisições privadas.
* **Gestão de ativos:**
    * Cadastro de ativos (Ações, FIIs, ETFs, Cripto, Renda Fixa).
    * Busca inteligente por Ticker com `ILIKE` para autocompletar no frontend.
* **Motor financeiro (transações e posição):**
    * Registro de operações de **COMPRA (BUY)** e **VENDA (SELL)**.
    * **Cálculo Automático de Preço Médio** e consolidação de carteira em tempo real.
    * **Cálculo de rentabilidade** da carteira com base no custo total vs. cotação atual.
* **Cotações e cache:**
    * Integração com a BRAPI via cliente HTTP (`RestClient`).
    * Cache de cotações com Redis e `@Cacheable` (TTL configurado).
* **Arquitetura:**
    * **Tratamento de concorrência (race conditions):** Uso do `@Version` para impedir que duas ordens simultâneas corrompam a quantidade ou o preço médio.
    * **Tratamento Global de Exceções:** Respostas de erro padronizadas (RFC 7807) utilizando `@RestControllerAdvice`.

### Próximos Passos
* Evoluir endpoint de carteira com agregações por classe de ativo e indicadores consolidados.
* Adicionar métricas e observabilidade para latência de consulta de cotação e taxa de acerto de cache.
* Iniciar a Fase 5 com dashboards e relatórios.

---

## Tecnologias e Stack

* **Linguagem:** Java 21
* **Framework:** Spring Boot 4.x (Web MVC, Data JPA, Security, Validation)
* **Banco de Dados:** PostgreSQL 16
* **Cache:** Redis 7
* **Migrações:** Flyway
* **Segurança:** Auth0 Java-JWT
* **Testes:** JUnit 5, Mockito, MockMvc e Testcontainers com PostgreSQL
* **Infraestrutura:** Docker & Docker Compose
* **Utilitários:** Lombok

---

## Pré-requisitos

Para rodar o projeto localmente, você precisará ter instalado:
* [JDK 21+](https://adoptium.net/)
* [Docker](https://www.docker.com/) e Docker Compose
* [Maven](https://maven.apache.org/) (Opcional, o projeto usa o Maven Wrapper `mvnw`)

---

## Como Executar o Projeto

1. **Clone o repositório:**
   ```bash
   git clone https://github.com/rick1135/valora.git
   cd valora
   ```

2. **Configure as variáveis de ambiente:**
   ```bash
   cp .env.example .env
   ```
   Ajuste `JWT_SECRET` (obrigatório em `prod` e no Docker Compose) e, opcionalmente, `BRAPI_TOKEN` para cotações em tempo real.

3. **Suba a infraestrutura (PostgreSQL 16 + Redis 7):**
   ```bash
   docker compose up -d postgres redis
   ```
   Por padrão, o `docker-compose.yml` expõe as portas apenas em loopback (`127.0.0.1`), pois PostgreSQL e Redis não possuem proteção de rede configurada para desenvolvimento.
   O Flyway executa automaticamente as migrações (`V1`–`V14`) na inicialização da aplicação, criando o schema, benchmarks iniciais e um catálogo inicial de ativos (`PETR4`, `VALE3`, `ITUB4`, `WEGE3`, `HGLG11`, `MXRF11`, `BOVA11`, `IVVB11`, `BTC` e `ETH`).

4. **Inicie a aplicação localmente (perfil `dev`):**
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   A aplicação estará disponível em `http://localhost:8080` e o Swagger UI em `http://localhost:8080/swagger-ui.html`.

5. **Execução completa via Docker Compose (perfil `prod`):**
   Para subir toda a stack (`postgres`, `redis` e `app`) com as portas vinculadas a `127.0.0.1` e `BRAPI_TOKEN` opcional:
   ```bash
   JWT_SECRET=sua-chave-secreta docker compose up -d --build
   ```

---

## Testes e Smoke Test

* **Testes automatizados (`mvnw`):**
  Requer Docker em execução para os testes de integração com Testcontainers:
  ```bash
  ./mvnw -B test
  ```

* **Smoke test de instalação limpa:**
  Requer `docker` (com plugin `docker compose`), `curl` e `python3`:
  ```bash
  bash scripts/smoke-clean-install.sh
  ```
  O script sobe uma stack isolada em projeto e volumes descartáveis (sem alterar containers ou dados existentes), valida o profile `prod`, migrações Flyway, Redis, cadastro/login, catálogo inicial (`PETR4`), rotação/revogação de `refreshToken` e isolamento HTTP entre usuários.

---

## Comportamento de Cotações (Brapi vs. Fallback) e Autenticação

* **Brapi sem token vs. fallback de cotação indisponível:**
  * Se `BRAPI_TOKEN` estiver vazio, o `QuoteService` não faz chamadas à API da Brapi e considera as cotações como indisponíveis.
  * O fallback só atua quando a cotação está indisponível (seja por ausência de token ou por falha/retorno sem preço válido da Brapi):
    * Em `GET /portfolios/{portfolioId}/summary`, posições sem cotação disponível utilizam o `averagePrice` (preço médio) como fallback no cálculo do resumo financeiro.
    * Em `GET /portfolios/{portfolioId}/positions`, `currentPrice` é retornado como `0` e `currentTotalValue` / `profitability` permanecem `null`.
    * Em `GET /portfolios/{portfolioId}/rebalance`, novos ativos sem cotação recebem `currentPrice = 0` e `marketPriceReliable = false`.

* **Login, refresh e logout:**
  * `POST /auth/login` (e `POST /auth/register`) emite um `token` de acesso JWT stateless e um `refreshToken` persistido no Redis.
  * `POST /auth/refresh` rotaciona o `refreshToken` (consome o anterior e emite um novo par).
  * `POST /auth/logout` revoga o `refreshToken` no Redis (`204 No Content`), impedindo novas renovações, **sem invalidar o `token` de acesso JWT** já emitido (que permanece válido até expirar).

---

## Variáveis de Ambiente

| Variável | Padrão (`dev` / Compose) | Descrição |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/valora` | URL JDBC do PostgreSQL (obrigatória em `prod`) |
| `SPRING_DATASOURCE_USERNAME` | `valora` | Usuário do banco de dados (obrigatória em `prod`) |
| `SPRING_DATASOURCE_PASSWORD` | `valora` | Senha do banco de dados (obrigatória em `prod`) |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Host do Redis (obrigatória em `prod`) |
| `SPRING_DATA_REDIS_PORT` | `6379` | Porta do Redis |
| `SPRING_DATA_REDIS_PASSWORD` | *(vazio)* | Senha do Redis (opcional) |
| `JWT_SECRET` | `troque-esta-chave` (`dev`) / obrigatória (`prod` e Compose) | Chave de assinatura dos tokens JWT |
| `BRAPI_TOKEN` | *(vazio)* | Token da API Brapi para cotações/proventos; opcional (se vazio, não consulta a Brapi e o resumo usa fallback quando a cotação está indisponível) |
| `PROVENTS_SYNC_CRON` | `0 15 8 * * *` | Expressão cron para sincronização diária de proventos |
| `PROVENTS_SYNC_ASSET_LOOKBACK_DAYS` | `365` | Janela em dias para busca de ativos na sincronização de proventos |
| `PROVENTS_SYNC_JCP_RATE_IS_GROSS` | `true` | Define se a taxa de JCP da origem é bruta (`true`) ou líquida (`false`) |

---

## Principais Endpoints da API

* **Autenticação (público):**
    * `POST /auth/register` — Registrar novo usuário (`USER`) e retornar `token` + `refreshToken`
    * `POST /auth/login` — Autenticar com e-mail e senha e obter `token` + `refreshToken`
    * `POST /auth/refresh` — Renovar `token` e `refreshToken`
    * `POST /auth/logout` — Revogar `refreshToken` no Redis (`204 No Content`)
* **Ativos:**
    * `GET /assets?page=0&size=20&sort=ticker&direction=asc` — Listar ativos paginados (autenticado)
    * `GET /assets/search?ticker={ticker}` — Buscar ativos por ticker com `ILIKE` (autenticado)
    * `POST /assets` — Cadastrar novo ativo (requer perfil `ADMIN`)
* **Portfólios (autenticado):**
    * `POST /portfolios` — Criar carteira
    * `GET /portfolios` — Listar carteiras do usuário
    * `GET /portfolios/{portfolioId}` — Detalhar carteira
    * `PUT /portfolios/{portfolioId}` — Atualizar carteira
    * `DELETE /portfolios/{portfolioId}` — Remover carteira
    * `GET /portfolios/{portfolioId}/positions` — Listar posições consolidadas da carteira
    * `GET /portfolios/{portfolioId}/summary` — Resumo financeiro, alocação e rentabilidade da carteira
    * `PUT /portfolios/{portfolioId}/targets` — Salvar metas de alocação da carteira
    * `GET /portfolios/{portfolioId}/rebalance?contributionAmount={valor}` — Simular sugestões de aporte
* **Transações (autenticado):**
    * `POST /transactions` — Registrar compra (`BUY`) ou venda (`SELL`)
    * `GET /transactions?portfolioId={id}` — Histórico paginado com filtros opcionais (`ticker`, `type`, `startDate`, `endDate`)
* **Proventos:**
    * `GET /provents/me?portfolioId={id}` — Listar proventos provisionados do usuário (autenticado, paginado)
    * `POST /provents` — Cadastrar evento de provento (`DIVIDEND`/`JCP`) e provisionar por data COM (requer perfil `ADMIN`)
    * `POST /provents/sync` — Sincronizar dividendos/JCP da Brapi para ativos em carteira (requer perfil `ADMIN`)
* **Documentação OpenAPI (público):**
    * `GET /swagger-ui.html` — Interface Swagger UI
    * `GET /v3/api-docs` — Especificação OpenAPI JSON
