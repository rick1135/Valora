# Valora - Investment Portfolio API

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen?style=for-the-badge&logo=spring)
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
- [ ] **Fase 4:** Integração com APIs externas (Cotações em Tempo Real) e Cálculo de Rentabilidade;
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
* **Arquitetura:**
    * **Tratamento de concorrência (race conditions):** Uso do `@Version` para impedir que duas ordens simultâneas corrompam a quantidade ou o preço médio.
    * **Tratamento Global de Exceções:** Respostas de erro padronizadas (RFC 7807) utilizando `@RestControllerAdvice`.

### Próximos Passos
* Consumo de APIs do Mercado Financeiro via **Spring Cloud OpenFeign** para obter o preço atual dos ativos.
* Cálculo da rentabilidade da carteira (Lucro/Prejuízo) em tempo real.
* Configuração do **Redis** para cache de cotações e otimização de consultas.

---

## 🛠️ Tecnologias e Stack

* **Linguagem:** Java 21
* **Framework:** Spring Boot 3.x (Web, Data JPA, Security, Validation)
* **Banco de Dados:** PostgreSQL 16
* **Cache:** Redis 7
* **Migrações:** Flyway
* **Segurança:** Auth0 Java-JWT
* **Testes:** JUnit 5, Mockito, MockMvc (Testes Unitários e de Integração usando H2 Database)
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
   cd Valora
   
2. **Suba a infra do Banco de Dados e Cache(Docker):**
    ```bash
    docker-compose up -d postgres redis
    ```
   O Flyway rodará automaticamente ao iniciar o Spring Boot, criando todas as tabelas e índices necessários.

3. **Inicie a aplicação:**
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   A aplicação estará disponível em `http://localhost:8080`

## Principais Endpoints da API
* **Auth:**
    * `POST /auth/register` - Registrar novo usuário
    * `POST /auth/login` - Autenticar e obter JWT
* **Ativos:**
    * `POST /assets` - Cadastrar um novo ativo (Ex: PETR4)
    * `GET /assets/search?ticker={ticker}` - Busca ativos (Público/Autenticado)
* **Transações:**
    * `POST /transactions` - Registra uma compra ou venda (atualiza a posição automaticamente).
* **Portfólio:**
    * `GET /portfolio` - Retorna a carteira atualizada do usuário logado (Quantidade, Preço Médio e Custo Total).
