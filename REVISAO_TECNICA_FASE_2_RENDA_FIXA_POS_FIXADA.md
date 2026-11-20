# Revisão técnica — Fase 2: renda fixa pós-fixada

- Projeto: Valora — Spring Boot 4.0.3 / Java 21 / PostgreSQL / Flyway.
- Branch: `feat/fase-2-renda-fixa-pos-fixada`.
- Base: `main`.
- Commit revisado: `5c42952`.
- Data: 19/11/2026.

Recomendo corrigir os dois achados de severidade **Alta** antes do merge. Não identifiquei falha crítica.

Comparei `main...feat/fase-2-renda-fixa-pos-fixada`. Executei **122 testes, todos passando**, além de reproduções isoladas dos problemas abaixo. O ambiente disponível usa **Java 25**; não executei os testes em Java 21 nem validei a migração contra PostgreSQL com dados existentes. A revisão não alterou os arquivos de implementação do projeto.

## Alto

### 1. O cálculo de IPCA usa uma convenção que não representa os títulos que declara suportar

**Local:** [PostFixedIncomeYieldCalculator.java](src/main/java/com/rick1135/Valora/service/PostFixedIncomeYieldCalculator.java), linha **127**, especialmente linhas 128–143.

A implementação aplica o IPCA do próprio mês civil e divide o período parcial pelos dias úteis desse mês. Isso não corresponde, por exemplo, à convenção ANBIMA de NTN-B: os períodos são delimitados pelos aniversários no dia 15, com tratamento de dia não útil e uso de índice oficial/projeção conforme a data. O próprio teste `valuatePositionShouldApplyTreasuryIpcaAndSpread` apresenta o resultado como Tesouro IPCA. A [metodologia ANBIMA de NTN-B](https://www.anbima.com.br/data/files/A0/24/27/4F/D18675106582A275862C16A8/Deliberacao_n03_3_.pdf) explicita essas referências.

**Impacto:** o primeiro mês, os períodos seguintes e o vencimento podem usar inflação e denominadores incorretos, mesmo com a série completa. Dois papéis com convenções diferentes recebem o mesmo cálculo.

**Correção:** representar a convenção contratual de atualização, incluindo aniversário e defasagem do índice, e calcular os fatores correspondentes. Enquanto isso, restringir os instrumentos suportados e evitar `reliable=true` para contratos cuja convenção não pode ser determinada. Base 252 para o spread não resolve a convenção de atualização monetária.

### 2. A ausência do IPCA no último mês parcial é aceita até para títulos vencidos há meses

**Local:** [PostFixedIncomeYieldCalculator.java](src/main/java/com/rick1135/Valora/service/PostFixedIncomeYieldCalculator.java), linha **131**.

`fullMonthEnded` considera apenas se o período alcançou o próximo mês. Não verifica se aquele índice já deveria estar publicado. Quando retorna `accumulated`, o chamador transforma o resultado em `reliable=true`.

**Reprodução executada:** principal de R$ 1.000, compra em **03/02/2025**, vencimento em **14/02/2025**, avaliação em **24/04/2025**, spread zero e série vazia → **R$ 1.000, `reliable=true`**. Também ocorre com compra no mês corrente e nenhum benchmark disponível.

**Impacto:** omissão silenciosa de inflação ou deflação; o fallback de todos os lotes não é acionado porque o calculador informa cobertura confiável.

**Correção:** distinguir observação pendente, lacuna histórica e projeção. Índice histórico obrigatório ausente deve retornar `reliable=false`; projeções precisam ser explícitas. A [ANBIMA publica metodologia própria para projeções e sua indisponibilidade](https://www.anbima.com.br/informacoes/vna/metodologia_projecoes.asp); simplesmente omitir a inflação não equivale a aplicar essa metodologia.

## Médio

### 3. O cadastro volta a atribuir significado a uma taxa legada ambígua

**Local:** [AssetService.java](src/main/java/com/rick1135/Valora/service/AssetService.java), linha **110**.

Para CDI/SELIC, `annualRate` preenchida e `rateType` ausente resultam automaticamente em `PERCENTAGE_OF_INDEX`. Para IPCA, o tipo é automaticamente `SPREAD`.

**Reprodução executada:** request legado com CDI, `annualRate=0.15`, sem os campos novos → cadastrado como **0,15% do CDI**. Se o cliente pretendia CDI + 0,15% a.a., a remuneração fica radicalmente diferente.

**Impacto:** ativos antigos já persistidos permanecem protegidos pela migração, mas reimportações e novos cadastros por clientes legados podem transformar a ambiguidade original em uma valoração considerada confiável.

**Correção:** inferir automaticamente apenas PRE. Para pós-fixados sem semântica contratual garantida, exigir o tipo ou preservar a ambiguidade com fallback. Se a inferência for requisito de compatibilidade, formalizar e testar qual significado cada cliente legado fornece.

### 4. Combinações contraditórias são persistidas e parte da remuneração é ignorada

**Local:** [AssetService.java](src/main/java/com/rick1135/Valora/service/AssetService.java), linha **135**; efeito em [PostFixedIncomeYieldCalculator.java](src/main/java/com/rick1135/Valora/service/PostFixedIncomeYieldCalculator.java), linha **86**.

`PERCENTAGE_OF_INDEX` não valida `spreadRate`. Confirmei que `(CDI, PERCENTAGE_OF_INDEX, annualRate=110, spreadRate=6.5)` é aceito e devolvido pela API, enquanto o cálculo remunera apenas 110% CDI.

Em `SPREAD`, duas taxas divergentes também são aceitas: `spreadRate` prevalece, mas `annualRate` continua armazenada e exposta.

**Impacto:** a API confirma dados de remuneração que o motor não utiliza, permitindo erros silenciosos de cadastro.

**Correção:** definir combinações exclusivas. Percentual deve rejeitar spread não zero; spread deve ter um campo autoritativo. Aceitar `annualRate` como alias legado exige normalização e rejeição de conflitos.

## Baixo/Sugestão

### 5. A tolerância a D-1 entrega um valor defasado sem informar a data efetivamente valorada

**Local:** [PostFixedIncomeYieldCalculator.java](src/main/java/com/rick1135/Valora/service/PostFixedIncomeYieldCalculator.java), linha **99**.

O `continue` omite tanto o indexador quanto o spread daquele dia e mantém `reliable=true`. Isso é compatível com tolerar publicação pendente, mas o resultado representa uma data anterior ao intervalo solicitado.

**Correção recomendada:** incluir `valuedThrough`/estado de publicação no resultado. Se a regra exigir valor até hoje, tratar como incompleto. Manter o teste que impede tolerar lacunas internas.

### 6. Campos de renda fixa passam integralmente sem validação em outras categorias

**Local:** [AssetService.java](src/main/java/com/rick1135/Valora/service/AssetService.java), linha **47**.

Confirmei que `ACOES` aceita CDI, percentual e spread, inclusive contraditórios, e os devolve no response.

**Correção recomendada:** rejeitar esses campos fora de `RENDA_FIXA` ou normalizá-los explicitamente. A validação deve representar também a categoria.

## Pontos sólidos

- **Intervalo diário:** `[purchaseDate, min(today, expirationDate))` está correto para a convenção solicitada. Compra hoje não acumula; vencimento hoje exclui a taxa de hoje; fins de semana e feriados reconhecidos pelo `MarketCalendar` são pulados. A [metodologia de acumulado DI da B3](https://b3.com.br/main.jsp?lumA=1&lumII=2C9FBE63638CFE2501638D373B435E37&lumPageId=2C9FBE63638CFE2501638D3464C8577B) também adota início inclusivo e fim exclusivo.
- **CDI/SELIC:** para taxas diárias percentuais, aplicar o percentual antes da composição e multiplicar pelo spread diário equivalente em base 252 está coerente. O sincronizador usa as séries diárias 12 e 11.
- **Consulta em lote:** [FixedIncomeValuationService.java](src/main/java/com/rick1135/Valora/service/FixedIncomeValuationService.java), linha **203**, faz uma busca do benchmark e uma busca dos valores por indexador, independentemente do número de lotes. Não há N+1 de benchmarks por posição/lote.
- **Amplitude da consulta:** considera o primeiro lote FIFO ainda aberto e mescla intervalos. Para o algoritmo atual de IPCA, antecipar `start` ao dia 1 e consultar até `end−1` está correto, inclusive quando `end` cai no dia 1. Alterar a convenção financeira exigirá rever essa amplitude.
- **Fallback:** qualquer lote que efetivamente retorne `reliable=false` faz toda a posição voltar a `quantity × averagePrice`. Pós-fixados antigos com `rateType=null` também ficam protegidos.
- **V17:** as colunas são anuláveis; o backfill limita-se a `RENDA_FIXA` com PRE/indexador nulo e não reinterpreta pós-fixados. Não encontrei incompatibilidade estrutural na leitura do SQL.
- **MapStruct/DTOs:** o mapper gerado inclui ambos os campos novos; os construtores antigos permanecem disponíveis. Os testes selecionados de serviço e controller passaram.
- **Deflação:** confirmei por execução que IPCA de −0,5%, com spread zero, reduz R$ 1.000 para R$ 995. Não há piso artificial em zero.

## Cobertura que falta

Prioridade para estes testes:

- Referências de IPCA independentes do algoritmo: aniversários, defasagem, compra antes/depois do dia 15 e aniversário em feriado.
- Último mês parcial histórico ausente, título vencido e posição no mês corrente sem nenhum benchmark.
- Virada para o dia 1 antes da publicação do mês anterior: hoje o resultado pode passar de confiável para fallback apenas pela mudança do mês.
- IPCA negativo, inclusive primeiro mês parcial e sequência com inflação positiva.
- Compra e vencimento hoje, D-1 numa segunda-feira/pós-feriado e observação ausente já fora da janela de tolerância.
- Matriz de validação com campos conflitantes, categoria incompatível e requests legados ambíguos.
- Upgrade PostgreSQL de V16 para V17 com PRE, indexador nulo e pós-fixados existentes; persistência e leitura dos campos novos.
- Reconciliação de arredondamentos com exemplos oficiais: alta precisão intermediária, por si só, não garante igualdade com as regras de truncamento e arredondamento da B3.

## Validação executada

```powershell
.\mvnw.cmd '-Dtest=AssetServiceTest,PostFixedIncomeYieldCalculatorTest,FixedIncomeValuationServiceTest,AssetControllerTest,PortfolioServiceTest,PortfolioPerformanceCalculatorTest,PositionServiceTest,MarketCalendarTest,BenchmarkSyncServiceTest,BenchmarkPerformanceCalculatorTest' test
```

Resultado: **122 testes, zero falhas, zero erros e zero testes ignorados**.

As reproduções adicionais foram executadas em arquivos Java temporários fora do repositório, usando as classes compiladas do projeto. Elas confirmaram o IPCA ausente tratado como confiável, a aplicação de deflação, a aceitação de taxas conflitantes, a aceitação de campos de renda fixa em ações e a inferência de tipo em request legado ambíguo.

## Reavaliação do commit `2a5ec56`

Comparação: `5c42952..2a5ec56` (`fix: aprimora validação e cobertura da renda fixa`). Esta seção atualiza o status dos achados anteriores, que permanecem registrados acima como histórico da revisão inicial. As linhas citadas nesta seção referem-se ao commit `2a5ec56`.

As correções principais foram aplicadas corretamente; o tratamento de D-1 ficou parcialmente resolvido. Não encontrei novos problemas críticos ou altos nas alterações.

Foram executados **165 testes, todos passando**, usando a mesma seleção de classes indicada na validação anterior, além de reproduções isoladas de IPCA histórico ausente, D-1 pendente e datas sem acúmulo. O ambiente de execução usa Java 25. A execução em Java 21 e o upgrade PostgreSQL V16→V17 com dados existentes continuam sem validação nesta revisão.

### Status dos achados anteriores

| Achado anterior | Resultado no commit `2a5ec56` |
|---|---|
| Convenção incorreta de IPCA | Mitigado: valoração de IPCA desativada |
| IPCA ausente retornando `reliable=true` | Corrigido: retorna principal com `reliable=false` |
| Inferência de taxa legada ambígua | Corrigido: somente PRE recebe inferência automática |
| Taxas contraditórias aceitas | Corrigido: percentual rejeita spread não zero; `SPREAD` rejeita `annualRate` |
| D-1 sem data de cobertura | Parcial: informação disponível apenas internamente |
| Campos de renda fixa em outras categorias | Corrigido |

### Pendência — Médio: `valuedThrough` não chega ao consumidor da API

**Locais:**

- [FixedIncomeValuationService.java](src/main/java/com/rick1135/Valora/service/FixedIncomeValuationService.java), linha **74**: campo adicionado ao resultado interno.
- [PositionService.java](src/main/java/com/rick1135/Valora/service/PositionService.java), linha **73**: o consumidor continua extraindo apenas os valores.
- [PositionResponseDTO.java](src/main/java/com/rick1135/Valora/dto/response/PositionResponseDTO.java), linha **6**: o response não contém data de cobertura nem estado de publicação.

**Impacto:** com D-1 pendente, o cliente continua recebendo um valor defasado sem conseguir identificar essa condição. A correção resolve o diagnóstico dentro do motor, mas não sua comunicação ao usuário.

**Correção necessária:** propagar a cobertura e o estado de atualização aos DTOs que apresentam a valoração, incluindo os consumidores do resumo da carteira. Adicionar teste do response da API que comprove a exposição da data e da condição de publicação pendente.

### Pendência — Baixo: `valuedThrough` tem significado inconsistente quando não há acúmulo

**Local:** [PostFixedIncomeYieldCalculator.java](src/main/java/com/rick1135/Valora/service/PostFixedIncomeYieldCalculator.java), linha **42**, em relação aos retornos nas linhas **61** e **119**.

A documentação define o campo como a última data cuja taxa foi aplicada. Entretanto:

- A linha **61** devolve `endDate` para compra hoje, sem aplicar taxa.
- A linha **119** devolve `startDate` quando passaram somente dias não úteis.

**Reprodução executada:** uma compra no feriado de **18/04/2025**, avaliada em **22/04/2025**, retorna `valuedThrough=2025-04-18`, embora nenhuma taxa tenha sido aplicada. Compra em **24/04/2025**, avaliada no mesmo dia, também devolve essa data sem aplicação de taxa.

**Impacto:** consumidores futuros podem interpretar uma data sem observação como cobertura efetiva.

**Correção necessária:** manter `null` quando nenhuma taxa foi aplicada ou redefinir o campo como data de avaliação, separando-o da última observação aplicada. Ajustar a documentação e os testes à definição escolhida, cobrindo compra hoje e intervalo composto apenas por dias não úteis.

### Limitação funcional: suporte financeiro a IPCA continua pendente

A restrição em [PostFixedIncomeYieldCalculator.java](src/main/java/com/rick1135/Valora/service/PostFixedIncomeYieldCalculator.java), linha **75**, é uma mitigação correta, prevista na recomendação anterior. O PR agora entrega valoração de CDI/SELIC e cadastro de IPCA com fallback; não deve ser apresentado como implementação completa da valoração de IPCA.

Para habilitar a valoração de IPCA, ainda será necessário modelar a convenção contratual de atualização, aniversário, defasagem e tratamento de projeções, além dos testes financeiros independentes indicados na revisão inicial.

### Verificações preservadas no novo commit

- [AssetService.java](src/main/java/com/rick1135/Valora/service/AssetService.java), linha **50**, preserva pós-fixados legados com tipo nulo, mantendo o fallback seguro.
- As linhas **137** e **145** de `AssetService.java` eliminam as combinações contraditórias apontadas.
- A linha **57** de `AssetService.java` rejeita campos de renda fixa nas outras categorias.
- O agrupamento por indexador, o FIFO e o fallback integral da posição foram preservados. Retirar o ajuste ao dia 1 da consulta é coerente com a desativação do IPCA.
