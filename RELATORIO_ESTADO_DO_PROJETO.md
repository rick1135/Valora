# Valora | Reanálise do estado do projeto

Revisão em 18 de novembro de 2026, após os ajustes locais. O commit de referência continua sendo `1e5dfb5`, mas há 25 arquivos versionados alterados. Esta avaliação considera essas alterações ainda sem commit e substitui o diagnóstico anterior.

O bloqueio de inicialização foi resolvido no teste isolado. O JAR iniciou com Java 21 e concluiu a criação do contexto Spring. A API ganhou saída de sessão e acesso HTTP ao serviço de metas e rebalanceamento. A interface financeira continua demonstrativa e as principais limitações da apuração financeira permanecem.

## Resultado da comparação

| Item | Estado atual | Evidência e limite |
| --- | --- | --- |
| Inicialização do Spring | Resolvido no cenário isolado | A configuração usa `tools.jackson.databind.ObjectMapper`. O JAR registrou `Started ValoraApplication` em 9,559 segundos. PostgreSQL e Redis reais ficaram fora dessa verificação. |
| Serialização Redis | Alterada | Configuração passou a usar `RedisSerializer.json()` e `RedisSerializer.string()`. Compatibilidade com valores de um Redis já populado ainda requer teste real. |
| Documentação da API | Liberada na segurança | Swagger UI e `/v3/api-docs` permitem GET público. O teste adicionado verifica essa política com controladores de teste. A geração do contrato completo não foi exercitada por HTTP. |
| Logout | Implementado no servidor | `POST /auth/logout` revoga o token de renovação e retorna 204. O JavaScript ainda não chama essa rota. |
| Metas de alocação | Acessíveis pela API | `PUT /portfolios/{portfolioId}/targets` recebe uma lista. O serviço verifica a propriedade da carteira. Falta completar a validação dos itens. |
| Rebalanceamento | Acessível pela API | `GET /portfolios/{portfolioId}/rebalance?contributionAmount=...` retorna sugestões. Aporte negativo é rejeitado pelo controlador. |
| Datas financeiras | Mais consistentes | Novos métodos em MarketCalendar centralizam instante atual e conversão para São Paulo. Serviços de carteira, transação, índices e renda fixa passaram a usá-los. |
| Preço de uma classe de ativos | Corrigido para classes com vários ativos | A agregação devolve preço zero quando a classe tem vários ativos. Os valores monetários e percentuais continuam agregados. Há teste com BTC e XRP para evitar uma cotação média formada pela soma de quantidades de unidades diferentes. |
| Navegação | Simplificada | Nomes de abas e de botões ficaram consistentes. Links ilustrativos de PRO e IRPF saíram das páginas alteradas. |
| Dados do usuário nas telas | Pendente | Os quatro scripts financeiros continuam com listas locais e não fazem chamadas HTTP à carteira. |
| URL do Banco Central | Pendente | BcbClient continua montando `bcdata.sgs/433/dados`. |
| Lucro realizado e renda fixa | Pendentes | A fórmula do resumo e o modelo de avaliação continuam com as limitações descritas adiante. |

Fontes das alterações. [Segurança](src/main/java/com/rick1135/Valora/security/SecurityConfigurations.java) · [Redis](src/main/java/com/rick1135/Valora/config/RedisConfig.java) · [Autenticação](src/main/java/com/rick1135/Valora/controller/AuthController.java) · [Carteiras](src/main/java/com/rick1135/Valora/controller/PortfolioController.java) · [Calendário](src/main/java/com/rick1135/Valora/service/MarketCalendar.java) · [Resumo](src/main/java/com/rick1135/Valora/service/PortfolioPerformanceCalculator.java) · [Rebalanceamento](src/main/java/com/rick1135/Valora/service/SmartRebalancingService.java)

## Verificações executadas

| Verificação | Resultado |
| --- | --- |
| Suíte Maven com Java 21 | **173 testes, 157 aprovados, 16 erros de inicialização, zero falhas de asserção e zero ignorados.** A execução completa termina em BUILD FAILURE. São oito testes a mais que na avaliação anterior. |
| Testes que dependem de PostgreSQL | Os mesmos 16 casos continuam bloqueados por ausência de ambiente Docker válido para o Testcontainers. Esse resultado deixa pendente a validação de migrações, restrições do banco e dos testes de segurança que dependem dele. |
| Empacotamento | BUILD SUCCESS com Java 21 e `-DskipTests package`. Gerado `target/Valora-0.0.1-SNAPSHOT.jar`. |
| Inicialização isolada | O JAR iniciou o servidor em uma porta local aleatória, criou o EntityManagerFactory e concluiu o contexto Spring. Flyway, validação do esquema e acesso a metadados JDBC foram desativados. As conexões apontaram para uma porta local sem serviço. O processo foi encerrado ao fim do prazo de 25 segundos. |
| Fluxos completos | Cadastro, login, renovação, persistência, importação real e uso das telas com dados do titular ainda precisam de verificação em conjunto com PostgreSQL e Redis. |

A execução inicial da suíte no sandbox ficou parada durante a inicialização do Mockito. A repetição fora do sandbox concluiu e produziu os números acima. Os logs desta revisão estão em `target/reanalise-testes.log`, `target/reanalise-build.log` e `target/reanalise-inicio.log`. O smoke test continua vazio, e parte dos testes usa serviços simulados ou um contexto Spring próprio. O início isolado confirma a correção da dependência de serialização, enquanto a instalação com banco e cache continua pendente. [Testes](src/test/java/com/rick1135/Valora/) · [Suporte PostgreSQL](src/test/java/com/rick1135/Valora/support/AbstractPostgresIntegrationTest.java) · [Smoke test](src/test/java/com/rick1135/Valora/ValoraApplicationTests.java)

## Pontos que exigem correção

### Entrada pública de metas aceita dados incompletos e percentuais inválidos

A nova rota usa `@Valid`, mas PortfolioTargetInput não declara restrições de campo. O serviço soma os percentuais antes de verificar os demais dados. Um item sem percentual pode gerar erro interno. Além disso, a validação da soma aceita -10% e 110%, pois o total é 100%. O banco declara o percentual como obrigatório, mas não restringe seu intervalo. Metas negativas ou acima de 100% chegam ao cálculo das sugestões.

Validar percentual obrigatório entre 0 e 100, cada elemento da lista, exclusividade entre ativo e categoria e duplicidade de destino. Rejeitar esses casos com resposta 400 antes de substituir as metas. Também é necessário conferir a substituição de uma meta existente pelo mesmo destino contra PostgreSQL. O serviço combina exclusão e inserção na mesma transação, e a ordenação de flush deve respeitar os índices únicos. Essa última condição ainda precisa de reprodução. [Entrada HTTP](src/main/java/com/rick1135/Valora/controller/PortfolioController.java) · [Validação e persistência](src/main/java/com/rick1135/Valora/service/SmartRebalancingService.java) · [Restrição original](src/main/resources/db/migration/V12__create_portfolio_targets.sql)

### O usuário ainda recebe um painel com valores fixos

O login continua redirecionando para dashboard.html. Esse painel continua mostrando R$ 84.230,00 a partir de uma constante. As páginas de proventos, metas e rentabilidade também usam exemplos locais. Os botões “Novo lançamento” e “Integração B3” não têm manipuladores de ação nesses scripts.

A API já oferece a base para listar carteiras, consultar posições e lançar operações. O próximo fluxo útil é criar uma conta, selecionar a carteira Principal, registrar a compra e ver a posição persistida após recarregar. Incluir também carteira vazia, carregamento, indisponibilidade de cotação e erros HTTP. [Autenticação no navegador](src/main/resources/static/auth.js) · [Dashboard](src/main/resources/static/dashboard.js) · [Proventos](src/main/resources/static/proventos.js) · [Rentabilidade](src/main/resources/static/rentabilidade.js) · [Metas](src/main/resources/static/metas.js)

### O resultado acumulado continua omitindo vendas realizadas

O resumo seleciona posições com quantidade positiva e calcula lucro usando patrimônio atual, proventos pagos e custo das posições abertas. Uma compra por R$ 100 seguida de venda total por R$ 120 deixa o lucro exibido em zero, sem outros eventos. A mudança no preço agregado por classe não modifica essa fórmula.

Separar resultado realizado, resultado em aberto, proventos e fluxos de caixa no contrato. Para o percentual comparável com índices, definir o método e o período com tratamento dos aportes e retiradas. [Carteira](src/main/java/com/rick1135/Valora/service/PortfolioService.java) · [Fórmula](src/main/java/com/rick1135/Valora/service/PortfolioPerformanceCalculator.java)

### A centralização das datas não completa o cálculo de renda fixa

FixedIncomeYieldCalculator agora usa MarketCalendar.today(), o que melhora consistência e permite controlar o relógio em testes. A fórmula permanece baseada em taxa anual única, dias corridos e base 365. Ela não recebe indexador ou vencimento. O cadastro aceita CDI, IPCA, Selic e prefixado.

Compras posteriores continuam compartilhando a data da primeira compra. Uma posição antiga com purchase_date nulo continua avaliada em zero, e o resumo continua marcando essa avaliação como válida. A migração V9 continua sem preenchimento das datas existentes. O endpoint detalhado ainda consulta cotações externas para esses títulos, enquanto o resumo calcula uma estimativa.

Definir cálculo por lote, indexador e vencimento, reconciliar dados legados e compartilhar a avaliação entre detalhe e resumo. [Calculadora](src/main/java/com/rick1135/Valora/service/FixedIncomeYieldCalculator.java) · [Data da posição](src/main/java/com/rick1135/Valora/service/TransactionService.java) · [Detalhe](src/main/java/com/rick1135/Valora/service/PositionService.java) · [Migração](src/main/resources/db/migration/V9__add_purchase_date_to_positions.sql)

### A integração BCB permanece com a URL incorreta

A URL continua no formato `https://api.bcb.gov.br/dados/serie/bcdata.sgs/433/dados`. Na verificação ao vivo anterior, esse caminho devolveu 404 e o formato `bcdata.sgs.433/dados` devolveu 200 para o mesmo intervalo. O código do cliente ficou inalterado nesta revisão. A sincronização continua capturando o erro e prosseguindo, e o cálculo pode devolver zero para série ausente.

Corrigir a composição do caminho e testar o contrato para CDI e IPCA. A carga inicial continua limitada a aproximadamente um ano. A recuperação de intervalos perdidos na brapi e a indicação de cobertura incompleta também permanecem pendentes. [Cliente](src/main/java/com/rick1135/Valora/client/BcbClient.java) · [Sincronização](src/main/java/com/rick1135/Valora/service/BenchmarkSyncService.java) · [Cálculo](src/main/java/com/rick1135/Valora/service/BenchmarkPerformanceCalculator.java)

### Proventos ainda precisam de reconciliação histórica e tributária

Lançamentos retroativos continuam atualizando a posição corrente sem reconstruir a sequência histórica. Uma compra anterior à data COM registrada após o provisionamento não recalcula o direito ao provento. A sincronização idêntica é ignorada pela deduplicação do evento.

No JCP importado como bruto, a sincronização continua multiplicando a taxa por 0,85. O provisionamento usa o valor já ajustado como bruto e fixa a retenção em zero. Assim, para R$ 100 brutos pela regra aplicada no código, o extrato grava R$ 85 brutos, zero de imposto e R$ 85 líquidos. A taxa original está disponível nos metadados. Definir a regra aplicável ao evento, usar campos coerentes e testar lançamentos feitos após a importação. [Provisionamento](src/main/java/com/rick1135/Valora/service/ProventService.java) · [Importação](src/main/java/com/rick1135/Valora/service/ProventSyncService.java)

## Sessão, contratos e manutenção

O logout revoga o token de renovação informado. Um JWT de acesso já emitido segue válido até sua expiração, por padrão duas horas. Como revokeRefreshToken absorve falhas do Redis, o retorno 204 também pode ocorrer sem confirmação da exclusão. Se o produto precisar confirmar revogação, essa condição deve ser tratada. O navegador ainda armazena os tokens em localStorage e não implementa renovação nem saída. [Tokens](src/main/java/com/rick1135/Valora/service/TokenService.java) · [Controller](src/main/java/com/rick1135/Valora/controller/AuthController.java)

A troca da serialização do Redis merece uma verificação com dados persistidos pela versão anterior. O cache de cotações tem TTL curto, mas tokens de renovação podem durar 30 dias. Os testes com Redis simulado não verificam a leitura desses valores após a troca. [Configuração](src/main/java/com/rick1135/Valora/config/RedisConfig.java) · [Redis de teste](src/test/java/com/rick1135/Valora/config/TestRedisConfig.java)

As novas metas HTTP representam percentuais de alocação. A tela continua mostrando objetivos em reais e com prazo, como reserva de emergência. Falta um contrato de leitura das metas salvas e definir se haverá também objetivos monetários. O cálculo de quantidade sugerida por ativo ainda arredonda para inteiro, inclusive para cripto. Isso pode deixar de sugerir uma compra fracionada que caberia no aporte. [Serviço](src/main/java/com/rick1135/Valora/service/SmartRebalancingService.java) · [Tela](src/main/resources/static/metas.js)

O calendário continua tratando feriados como falso. Os novos usos de MarketCalendar reduzem diferenças de fuso, mas as rotinas agendadas de benchmarks e importação continuam sem fuso explícito na anotação. Em contêineres, conferir os horários desejados. Os construtores alternativos e fallbacks que criam um calendário com relógio do sistema também podem mascarar uma dependência ausente em testes. [Calendário](src/main/java/com/rick1135/Valora/service/MarketCalendar.java) · [Benchmarks](src/main/java/com/rick1135/Valora/service/BenchmarkSyncService.java)

A infraestrutura continua com PostgreSQL e Redis publicados no host, credenciais fixas locais e Redis sem autenticação no Compose. O perfil padrão permanece dev. O README ainda cita o contrato antigo GET /portfolio e precisa incluir as novas rotas, o primeiro administrador e a preparação do catálogo de ativos. Essas decisões afetam a instalação em ambiente novo. [Compose](docker-compose.yml) · [Configuração](src/main/resources/application.yml) · [README](readme.md)

## Prioridades após os ajustes

| Ordem | Entrega | Como verificar |
| --- | --- | --- |
| 1 | Validar a aplicação com PostgreSQL e Redis reais | Aplicar as 13 migrações e concluir os 173 testes. Cadastrar usuário, autenticar, renovar e revogar o token. |
| 2 | Validar a nova entrada de metas | Rejeitar percentuais nulos, negativos, acima de 100 e destinos repetidos. Salvar e substituir metas existentes no PostgreSQL, consultar sugestões e impedir acesso de outro titular. |
| 3 | Corrigir BCB e apuração financeira | Testar venda total com lucro, renda fixa com dois aportes e vencimento, data legada ausente, JCP e operação retroativa após importação. |
| 4 | Conectar o navegador à carteira | Conta nova vê saldo zero. Compra de dez unidades por R$ 10 resulta em custo de R$ 100 e aparece novamente após recarregar. Uma segunda conta recebe apenas seus próprios dados. |
| 5 | Fechar escopo de metas e séries históricas | Definir objetivos monetários, percentuais e retorno por período. Exibir indicadores com cobertura temporal conhecida. |

O avanço principal desta rodada foi remover o bloqueio de criação do contexto e tornar funções já existentes acessíveis pela API. Para concluir a primeira versão utilizável, o maior trabalho continua na integração das telas e na reconciliação dos números financeiros.

