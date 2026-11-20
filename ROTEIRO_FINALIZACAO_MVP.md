# Valora | Roteiro de Finalização do MVP

**Base do roteiro:** análise realizada em 18/11/2026 sobre o branch `main` (commit `1e5dfb5` + correções dos 6 pontos técnicos complementares).  
**Objetivo:** transformar o projeto em um MVP funcional ponta a ponta no navegador, com cálculos financeiros consistentes e verificação automatizada em ambiente limpo.

> [!TIP]
> **Como usar este documento:** execute uma etapa por vez, marque os itens concluídos (`[x]`) e, ao final de cada etapa, peça para revisar o que foi implementado usando os critérios de aceite listados abaixo.

---

## Baseline atual (o que já foi corrigido e funciona)

Antes de iniciar a Etapa 1, os seguintes bloqueios técnicos já foram resolvidos no repositório:

- [x] **Inicialização do Spring Boot 4.0.3 / Jackson 3:** `SecurityConfigurations` passou a injetar `tools.jackson.databind.ObjectMapper` e o `RedisConfig` migrou para `RedisSerializer.json()` / `RedisSerializer.string()`. O JAR sobe com JDK 21 sem erros de contexto.
- [x] **Rotas e dependências órfãs ajustadas:**
  - `POST /auth/logout` exposto em `AuthController` (revogando o refresh token no Redis).
  - `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` e `/auth/logout` liberados em `SecurityConfigurations`.
  - `PUT /portfolios/{portfolioId}/targets` e `GET /portfolios/{portfolioId}/rebalance` expostos em `PortfolioController`.
  - Dependência não utilizada `spring-boot-starter-mail` removida do `pom.xml`.
- [x] **Cálculo por categoria sem soma de quantidades heterogêneas:** `PortfolioPerformanceCalculator` e `SmartRebalancingService` não dividem mais o valor da categoria pela soma de quantidades de ativos distintos.
- [x] **Padronização de data e fuso (`America/Sao_Paulo`):** `MarketCalendar` (`now()`, `today()`, `marketDate()`) padronizado em `FixedIncomeYieldCalculator`, `AssetService`, `PortfolioService`, `BenchmarkSyncService` e `TransactionService`.
- [x] **Varredura Spring Data Redis desativada:** `spring.data.redis.repositories.enabled: false` em `application.yml` e `application-test.yml`.
- [x] **Navegação HTML padronizada:** `dashboard.html`, `rentabilidade.html`, `proventos.html` e `metas.html` compartilham as mesmas 6 abas e botões de ação.
- [x] **Suíte unitária e web validada:** 157 testes em 27 classes passando sem falhas (restam 16 testes de integração/segurança em 6 classes que dependem do Docker/Testcontainers ativo).

---

## Etapa 1 — Bootstrap de Dados e Correções Rápidas de Backend

**Foco:** garantir que um banco recém-criado tenha ativos disponíveis, que a consulta de metas exista na API e que os três desvios pontuais de cálculo/integração estejam corrigidos.

### Checklist de implementação

- [ ] **1.1. Seed inicial de ativos (`Flyway`)**
  - **Problema atual:** as migrações `V1`–`V13` criam a tabela `assets` vazia, `POST /assets` exige perfil `ADMIN` (`SecurityConfigurations.java#L81`) e o cadastro público cria apenas `USER` (`UserService.java#L41`). Um usuário novo não encontra nenhum ativo para lançar compra.
  - **O que fazer:** criar `src/main/resources/db/migration/V14__seed_initial_assets.sql` populando um catálogo inicial representativo (`ACOES`, `FII`, `ETF`, `CRIPTO`), usando `ON CONFLICT (ticker_normalized) DO NOTHING` para não conflitar com bases já populadas.
  - *(Opcional complementar)* Documentar no `readme.md` ou criar uma instrução de seed para usuário `ADMIN` local de desenvolvimento.

- [ ] **1.2. Endpoint de leitura das metas da carteira**
  - **Problema atual:** `PortfolioController.java#L93-L113` já expõe `PUT /portfolios/{portfolioId}/targets` e `GET /portfolios/{portfolioId}/rebalance`, mas ainda não há um `GET` para o frontend carregar as metas percentuais já cadastradas.
  - **O que fazer:**
    - Criar DTO de resposta (ex.: `PortfolioTargetResponseDTO`) e método `getTargets(User user, UUID portfolioId)` em `SmartRebalancingService.java`.
    - Expor `GET /portfolios/{portfolioId}/targets` em `PortfolioController.java`.

- [ ] **1.3. Correção da URL da API SGS do Banco Central (CDI e IPCA)**
  - **Arquivo:** `src/main/java/com/rick1135/Valora/client/BcbClient.java#L13-L25`
  - **Problema atual:** `BASE_URL` + URI monta `.../bcdata.sgs/{seriesCode}/dados` (com barra `/`), que retorna HTTP 404 no BCB.
  - **O que fazer:** alterar para `https://api.bcb.gov.br/dados/serie/bcdata.sgs.{seriesCode}/dados?formato=json&dataInicial={startDate}&dataFinal={endDate}` (ponto antes do código da série) e adicionar teste unitário com `MockRestServiceServer` validando a URL gerada.
  - **Bônus recomendado:** em `BenchmarkSyncService.java` / `BenchmarkPerformanceCalculator.java`, indicar explicitamente quando a série estiver ausente ou incompleta para o período consultado (evitando confundir ausência de dados com rendimento `0%`).

- [ ] **1.4. Correção de JCP bruto vs. imposto retido vs. líquido**
  - **Arquivos:** `src/main/java/com/rick1135/Valora/service/ProventSyncService.java#L96-L116`, `ProventSyncService.java#L194-L199` e `src/main/java/com/rick1135/Valora/service/ProventService.java#L108-L120`
  - **Problema atual:** `ProventSyncService` multiplica a taxa por `0.85` antes de criar o `Provent`, e `ProventService` grava esse valor já reduzido em `grossAmount`, fixa `withholdingTaxAmount = 0` e copia para `netAmount`.
  - **O que fazer:**
    - Preservar o valor unitário original no cálculo do bruto (`grossAmount = quantity * grossAmountPerShare`).
    - Calcular `withholdingTaxAmount = grossAmount * 0.15` quando `type == JCP` e a taxa de origem for bruta (`originRateBasis == GROSS`), ou `0` nos demais casos.
    - Gravar `netAmount = grossAmount - withholdingTaxAmount`.
    - Atualizar os testes de `ProventServiceTest` e `ProventSyncServiceTest`.

- [ ] **1.5. Unificação da Renda Fixa entre `/positions` e `/summary` + limite de vencimento**
  - **Arquivos:** `src/main/java/com/rick1135/Valora/service/PositionService.java#L37-L63`, `src/main/java/com/rick1135/Valora/service/FixedIncomeYieldCalculator.java#L28-L60` e `src/main/java/com/rick1135/Valora/service/PortfolioPerformanceCalculator.java#L152-L163`
  - **Problema atual:** `PositionService` busca preço de títulos de renda fixa na Brapi (que não retorna cotação, deixando `currentTotalValue` e `profitability` nulos na listagem detalhada enquanto o `/summary` estima rendimento). Além disso, `FixedIncomeYieldCalculator` não limita o cálculo à `expirationDate` do ativo e trata posição com `purchaseDate == null` retornando `0` (enquanto o resumo marca `marketPriceReliable = true`).
  - **O que fazer:**
    - Fazer `PositionService` avaliar ativos `RENDA_FIXA` usando `FixedIncomeYieldCalculator` (em vez de chamar a Brapi para eles).
    - Passar `expirationDate` para `FixedIncomeYieldCalculator` (limitando `effectiveDate = min(today, expirationDate)` para não continuar rendendo após o vencimento).
    - Definir comportamento seguro quando `purchaseDate == null` em posições antigas (ex.: usar custo médio como fallback e marcar como não confiável se não houver data).
    - Deixar explícita no `readme.md` / Swagger a regra de renda fixa suportada no MVP (ou implementar a variação por indexador `PRE` / `CDI` / `SELIC` / `IPCA` caso deseje já na v1).

### Critérios de aceite da Etapa 1
1. Banco novo após `V14` possui ativos cadastrados e visíveis em `GET /assets` e `GET /assets/search`.
2. `GET /portfolios/{id}/targets` retorna as metas salvas via `PUT /portfolios/{id}/targets`.
3. `BcbClient` gera a URL `bcdata.sgs.12/dados` e `bcdata.sgs.433/dados` e passa em teste automatizado.
4. Um JCP bruto de R$ 1,00 sobre 100 ações gera `grossAmount = 100.00`, `withholdingTaxAmount = 15.00` e `netAmount = 85.00`.
5. `GET /portfolios/{id}/positions` e `GET /portfolios/{id}/summary` exibem o mesmo valor atual para uma posição de Renda Fixa.

---

## Etapa 2 — Consistência Contábil: Lucro Realizado e Ordem Cronológica

**Foco:** garantir que vendas com lucro/prejuízo e lançamentos com datas retroativas não distorçam saldo, preço médio, lucro total ou proventos.

### Checklist de implementação

- [ ] **2.1. Incluir ganho/perda realizado na apuração de lucro da carteira**
  - **Arquivos:** `src/main/java/com/rick1135/Valora/service/PortfolioService.java#L102`, `src/main/java/com/rick1135/Valora/service/PortfolioPerformanceCalculator.java#L45-L53`, `src/main/java/com/rick1135/Valora/dto/response/PortfolioSummaryDTO.java`
  - **Problema atual:** `PortfolioService` consulta apenas posições com `quantity > 0` e calcula `absoluteProfit = totalPatrimony + totalProvents - totalInvested`. Quem compra por R$ 100 e vende tudo por R$ 120 termina com lucro exibido de R$ 0.
  - **O que fazer:**
    - Definir na posição ou em um calculador de extrato o **resultado realizado acumulado** (`realizedProfit`) a partir do histórico de compras e vendas da carteira.
    - Expor no `PortfolioSummaryDTO.ProfitabilityDTO` (ou somar ao `absoluteProfit` distinguindo `unrealizedProfit` e `realizedProfit`) para que lucro em aberto, lucro realizado e proventos pagos fiquem rastreáveis e auditáveis.

- [ ] **2.2. Garantir consistência cronológica para lançamentos retroativos (`transactionDate`)**
  - **Arquivos:** `src/main/java/com/rick1135/Valora/service/TransactionService.java#L37-L130` e `src/main/java/com/rick1135/Valora/repository/TransactionRepository.java`
  - **Problema atual:** `TransactionDTO` aceita datas passadas (`@PastOrPresent`), mas `TransactionService` valida saldo e preço médio apenas contra o estado atual da `Position`, permitindo vender antes de comprar na linha do tempo e mantendo a `purchaseDate` fixa na ordem de inserção.
  - **O que fazer:**
    - Ao registrar uma transação com data anterior à última transação daquele `(portfolio, asset)` (ou de forma padrão para o ativo na carteira), ordenar todas as transações por `(transactionDate ASC, id ASC)` e reprocessar a linha do tempo para:
      1. Recusar a operação se o saldo ficar negativo em **qualquer** ponto da cronologia (`InsufficientPositionException`).
      2. Recalcular corretamente `quantity`, `averagePrice`, `purchaseDate` (da última abertura de posição) e `realizedProfit`.

- [ ] **2.3. Idempotência e política de proventos retroativos**
  - **Arquivos:** `src/main/java/com/rick1135/Valora/service/TransactionService.java`, `src/main/java/com/rick1135/Valora/service/ProventService.java`
  - **O que fazer:**
    - Avaliar e implementar (ou documentar explicitamente como política do MVP) o comportamento quando uma compra retroativa é lançada com `transactionDate` anterior à `comDate` de um provento já existente para o ativo (reprovisionar ou atualizar `quantityOnComDate`).
    - *(Recomendado para evitar duplo clique no frontend)* Adicionar suporte opcional a `idempotencyKey` ou validação contra reenvio duplicado idêntico em `POST /transactions`.

- [ ] **2.4. Testes de regressão contábil**
  - Cobrir em `TransactionServiceTest`, `PortfolioPerformanceCalculatorTest` e `TransactionAndPositionIntegrationTest`:
    - Compra seguida de venda parcial (lucro realizado parcial + custo remanescente).
    - Compra seguida de venda total (lucro realizado preservado no resumo mesmo com posição zerada).
    - Lançamento retroativo de venda antes da primeira compra (deve falhar com `400 Bad Request`).
    - Lançamento retroativo de compra entre uma compra e uma venda já existentes (deve recalcular preço médio corretamente).

### Critérios de aceite da Etapa 2
1. Comprar 10 unidades a R$ 10,00 e vender todas as 10 a R$ 12,00 exibe lucro realizado de **R$ 20,00** no resumo da carteira, mesmo com saldo atual zero.
2. Registrar uma compra em 10/06 e depois tentar registrar uma venda com data de 05/06 é bloqueado por saldo insuficiente na data.
3. Os valores de `/transactions`, `/positions` e `/summary` fecham centavo a centavo para a mesma sequência de operações.

---

## Etapa 3 — Integração do Frontend com a API Real

**Foco:** eliminar os dados estáticos (`R$ 84.230,00`) e completar o fluxo real do usuário no navegador do login/cadastro até o gerenciamento completo da carteira.

### Checklist de implementação

- [ ] **3.1. Cliente HTTP autenticado e gestão de sessão (`src/main/resources/static/api.js` + `auth.js`)**
  - **O que fazer:**
    - Criar um módulo compartilhado (ex.: `api.js`) carregado pelas páginas internas (`dashboard.html`, `proventos.html`, `rentabilidade.html`, `metas.html`) que:
      - Leia `valora.accessToken` e `valora.refreshToken` do `localStorage`.
      - Redirecione para `/login.html` se não houver sessão válida.
      - Envie `Authorization: Bearer <token>` em todas as chamadas.
      - Intercepte respostas `401`, execute `POST /auth/refresh` automaticamente (com trava para evitar múltiplas chamadas simultâneas), atualize os tokens e repita a requisição; se o refresh falhar, limpe o `localStorage` e redirecione para `/login.html`.
    - Implementar seletor de carteira no topo (carregando `GET /portfolios` e persistindo o `portfolioId` ativo — padrão `Principal`, permitindo criar/renomear via `POST/PUT /portfolios`).
    - Adicionar botão **Sair** no cabeçalho chamando `POST /auth/logout` com o `refreshToken`, limpando o `localStorage` e voltando para `/login.html`.
    - **Segurança DOM:** substituir templates com `innerHTML` nos arquivos `.js` que recebem dados da API por construção segura com `document.createElement` e `textContent` (prevenção de XSS).

- [ ] **3.2. Fluxo de Novo Lançamento e Extrato de Operações**
  - **O que fazer:**
    - Ligar o botão **`+ Novo lançamento`** (presente nas 4 páginas internas) a um modal funcional contendo:
      - Campo de busca de ativo com autocomplete via `GET /assets/search?ticker=...`.
      - Seleção de tipo (`BUY` / `SELL`), quantidade, preço unitário e data da operação.
      - Envio para `POST /transactions` com o `portfolioId` ativo, exibindo erros amigáveis vindos do `GlobalExceptionHandler` (`message` do RFC/JSON de erro) e recarregando os dados da tela ao concluir.
    - Conectar a aba **`Lançamentos`** (ou seção dedicada/página `lancamentos.html`) ao endpoint `GET /transactions?portfolioId=...` com filtros por ticker, tipo e período + paginação.
    - Conectar a aba **`Ativos`** (ou modal/página `ativos.html`) a `GET /assets` com paginação e busca por ticker.

- [ ] **3.3. Dashboard (`dashboard.html` / `dashboard.js`)**
  - Remover os arrays estáticos (`metrics`, `allocation`, `assets` com `R$ 84.230,00`).
  - Consumir `GET /portfolios/{portfolioId}/summary` e `GET /portfolios/{portfolioId}/positions` para preencher:
    - Os 4 cards de métricas (`Patrimônio total`, `Lucro total`, `Proventos`, `Rentabilidade`).
    - A lista/gráfico de alocação por classe (`allocations`).
    - A tabela **Meus ativos** com quantidade, preço médio, preço atual, rentabilidade e `% carteira`.
    - Indicar visualmente quando `fallbackQuoteUsed === true` (ativos usando preço médio por falta de cotação) e exibir estado vazio acolhedor para contas novas sem operações (`Patrimônio R$ 0,00` + botão para registrar a primeira compra).

- [ ] **3.4. Proventos (`proventos.html` / `proventos.js`)**
  - Remover os dados fixos e conectar a `GET /provents/me?portfolioId=...` (paginado).
  - Calcular e exibir os totais reais de proventos recebidos (`PAID`) e a receber (`PENDING`), distribuição por ativo e tabela detalhada (Data Com, Data Pgto, Quantidade, Bruto, IR, Líquido, Status).

- [ ] **3.5. Rentabilidade (`rentabilidade.html` / `rentabilidade.js`)**
  - Remover os dados fixos e conectar a `GET /portfolios/{portfolioId}/summary`.
  - Exibir o lucro e percentual real da carteira e a comparação real com os benchmarks retornados em `profitability.benchmarkComparisons` (`CDI`, `IPCA`, `Ibovespa`).

- [ ] **3.6. Metas e Rebalanceamento (`metas.html` / `metas.js`)**
  - **Importante:** o mockup atual de `metas.html` mostra metas em reais com prazo (ex.: "Reserva de emergência R$ 12.000"), enquanto o backend implementa **alocação percentual por categoria/ativo + cálculo de aporte** (`PortfolioTarget` e `SmartRebalancingService`).
  - **O que fazer no MVP:** adaptar `metas.html` / `metas.js` para refletir o modelo real do backend:
    - Formulário para definir os `%` ideais por categoria (`ACOES`, `FII`, `ETF`, `CRIPTO`, `RENDA_FIXA`) ou por ativo específico (somando ~100%), salvando via `PUT /portfolios/{portfolioId}/targets` e carregando via `GET /portfolios/{portfolioId}/targets`.
    - Simulador de aporte: campo para informar quanto deseja investir e botão que consulta `GET /portfolios/{portfolioId}/rebalance?contributionAmount=...`, exibindo quanto alocar em cada categoria/ativo, quantidade sugerida e troco residual.

### Critérios de aceite da Etapa 3
1. Criar conta em `/cadastro.html` -> cair no `/dashboard.html` vendo **R$ 0,00** e estado vazio (nada de R$ 84.230,00).
2. Clicar em `+ Novo lançamento`, buscar um ativo (ex.: `PETR4`), lançar compra de `10` unidades a `R$ 10,00` -> o dashboard passa a mostrar custo de `R$ 100,00`, a posição aparece na tabela e permanece após `F5`.
3. Uma segunda conta criada enxerga apenas a própria carteira e os próprios dados.
4. Clicar em `Sair` revoga o refresh token e bloqueia o retorno às páginas internas sem novo login.
5. `node --check` passa em todos os arquivos `.js`.

---

## Etapa 4 — Corte de Escopo Visual, Testes Completos com Docker e Documentação

**Foco:** limpar elementos ilusórios na UI, validar toda a suíte (incluindo PostgreSQL/Flyway e Redis reais) e atualizar a documentação de execução.

### Checklist de implementação

- [ ] **4.1. Alinhamento da interface ao escopo real do MVP**
  - Em `login.html`: remover ou ocultar `"Continuar com Google"` (`login.html#L56`) e `"Esqueci minha senha"` (`login.html#L77`) caso não haja OAuth2 nem fluxo de e-mail implementado.
  - Em `cadastro.html`: remover o campo `"Perfil de investidor"` (`cadastro.html#L58-L62`) ou enviá-lo e persisti-lo no backend (hoje o `RegisterDTO` ignora esse campo).
  - Nas páginas internas (`dashboard.html`, `proventos.html`, `rentabilidade.html`, `metas.html`): remover ou desabilitar com aviso de "Em breve (v2)" o botão `"Integração B3"` e gráficos mensais que ainda não possuam série histórica no backend.
  - Em `index.html`: deixar claro que o botão `"Ver demonstração"` abre um modo demonstrativo (ex.: `/dashboard.html?demo=true`) separado da sessão autenticada real.

- [ ] **4.2. Endurecimento mínimo de configuração e resiliência HTTP**
  - Configurar timeouts explícitos de conexão e leitura em `RestClientConfig.java` para `BrapiClient` e `BcbClient`.
  - Verificar variáveis obrigatórias no `.env.example` e testar `docker compose up -d` subindo PostgreSQL 16, Redis 7 e a aplicação em perfil `prod`.

- [ ] **4.3. Validação completa com Docker / Testcontainers**
  - Com o Docker em execução, rodar a suíte **completa** sem exclusões:
    ```powershell
    .\mvnw.cmd -B test
    ```
  - Confirmar **100% de aprovação** (zero erros e zero falhas), garantindo que os 16 testes atualmente dependentes de Docker (`AssetControllerSecurityTest`, `ProventControllerSecurityTest`, `AssetRepositoryIntegrationTest`, `PortfolioTargetIntegrationTest`, `ProventProvisionRepositoryIntegrationTest`, `TransactionAndPositionIntegrationTest`) executem `V1`–`V14` e passem.

- [ ] **4.4. Atualização do `readme.md`**
  - Atualizar a lista de endpoints reais (`/auth/refresh`, `/auth/logout`, `/portfolios/{id}/positions`, `/portfolios/{id}/summary`, `/portfolios/{id}/targets`, `/portfolios/{id}/rebalance`, `/transactions`, `/provents/me`).
  - Documentar passo a passo de configuração do `.env`, geração do token da Brapi, subida via Docker Compose e acesso ao Swagger UI (`/swagger-ui.html`).

---

## Guia para revisão posterior

Quando terminar qualquer etapa (ou todo o roteiro) e quiser que eu analise o que foi feito, basta pedir:
> *"Analise a Etapa X do `ROTEIRO_FINALIZACAO_MVP.md`"*

Na revisão, vou verificar:
1. Consistência dos cálculos e regras financeiras (`BigDecimal`, escalas, impostos, lucro realizado e datas em `America/Sao_Paulo`).
2. Segurança (ownership de `portfolioId`, validação de entrada, ausência de XSS no frontend e renovação/revogação de tokens).
3. Execução real dos testes unitários, de integração (com Docker, se ativo) e sintaxe JavaScript.
