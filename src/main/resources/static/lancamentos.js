(function () {
  const PAGE_SIZE = 20;
  const dateFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short" });
  const quantityFormat = new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 8 });
  const priceFormat = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 8,
  });

  const api = window.ValoraAPI;
  const form = document.querySelector("#history-filters");
  const tickerInput = document.querySelector("#history-ticker");
  const typeInput = document.querySelector("#history-type");
  const errorBanner = document.querySelector("#history-error");
  const loading = document.querySelector("#history-loading");
  const empty = document.querySelector("#history-empty");
  const table = document.querySelector("#history-table-wrap");
  const rows = document.querySelector("#history-rows");
  const pagination = document.querySelector("#history-pagination");
  const previous = document.querySelector("#history-previous");
  const next = document.querySelector("#history-next");
  const pageInfo = document.querySelector("#history-page-info");
  const logout = document.querySelector("#logout-btn");
  const importButton = document.querySelector("#import-csv-btn");
  const importModal = document.querySelector("#csv-import-modal");
  const importBackdrop = document.querySelector("#csv-import-backdrop");
  const importForm = document.querySelector("#csv-import-form");
  const fileInput = document.querySelector("#csv-file-input");
  const preview = document.querySelector("#csv-preview");
  const importStatus = document.querySelector("#csv-import-status");
  const confirmImport = document.querySelector("#csv-confirm-btn");
  let importFormData = null;

  function setImportStatus(message, tone = "error") {
    importStatus.textContent = message;
    importStatus.hidden = !message;
    importStatus.dataset.tone = tone;
  }

  function closeImport() {
    importModal.hidden = true;
    importBackdrop.hidden = true;
    importFormData = null;
    confirmImport.disabled = true;
  }

  function appendImportList(parent, title, values, renderValue = (value) => String(value)) {
    const heading = document.createElement("h3");
    heading.textContent = title;
    const list = document.createElement("ul");
    values.forEach((value) => {
      const item = document.createElement("li");
      item.textContent = renderValue(value);
      list.append(item);
    });
    parent.append(heading, list);
  }

  function renderImportPreview(result) {
    preview.replaceChildren();
    preview.hidden = false;
    const counts = document.createElement("p");
    counts.textContent = `Linhas: ${result.totalRows ?? 0} · Válidas: ${result.validCount ?? 0} · Com erros: ${result.errorCount ?? 0}`;
    preview.append(counts);
    const missing = Array.isArray(result.missingTickers) ? result.missingTickers : [];
    if (missing.length) {
      const warning = document.createElement("p");
      warning.textContent = "Estes ativos precisam ser cadastrados ou importados antes da importação.";
      preview.append(warning);
      appendImportList(preview, "Ativos ausentes", missing);
    }
    const errors = Array.isArray(result.errors) ? result.errors : [];
    if (errors.length) appendImportList(preview, "Erros encontrados", errors, (item) => `Linha ${item.lineNumber ?? item.line ?? item.row ?? "?"}: ${item.message || "Erro de validação"}`);
    const samples = Array.isArray(result.validRows) ? result.validRows.slice(0, 10) : (Array.isArray(result.sample) ? result.sample.slice(0, 10) : []);
    if (samples.length) appendImportList(preview, "Amostra de linhas válidas", samples, (item) => `${item.ticker || item.assetTicker || ""} ${item.type || ""} ${item.quantity ?? ""} @ ${item.unitPrice ?? ""} (${item.date || item.transactionDate || ""})`);
    confirmImport.disabled = !(result.validCount > 0 && result.errorCount === 0 && missing.length === 0);
  }

  importButton.addEventListener("click", () => {
    importModal.hidden = false;
    importBackdrop.hidden = false;
    setImportStatus("");
    preview.hidden = true;
    confirmImport.disabled = true;
    fileInput.focus();
  });
  document.querySelector("#csv-import-close").addEventListener("click", closeImport);
  importBackdrop.addEventListener("click", closeImport);
  importForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = fileInput.files?.[0];
    if (!file) { setImportStatus("Selecione um arquivo CSV."); return; }
    if (file.size > 2 * 1024 * 1024) { setImportStatus("O arquivo deve ter no máximo 2 MB."); return; }
    importFormData = new FormData();
    importFormData.append("file", file);
    const analyze = document.querySelector("#csv-analyze-btn");
    analyze.disabled = true;
    setImportStatus("Analisando arquivo...", "info");
    try {
      if (!activePortfolioId) activePortfolioId = (await api.getPortfolio())?.id;
      if (!activePortfolioId) throw new Error("Nenhuma carteira ativa encontrada.");
      const result = await api.request(`/transactions/import/preview?portfolioId=${encodeURIComponent(activePortfolioId)}`, { method: "POST", body: importFormData });
      renderImportPreview(result);
      setImportStatus("Prévia pronta.", "success");
    } catch (error) { setImportStatus(error.message); }
    finally { analyze.disabled = false; }
  });
  confirmImport.addEventListener("click", async () => {
    if (!importFormData || confirmImport.disabled || !activePortfolioId) return;
    confirmImport.disabled = true;
    setImportStatus("Importando operações...", "info");
    try {
      const result = await api.request(`/transactions/import?portfolioId=${encodeURIComponent(activePortfolioId)}`, { method: "POST", body: importFormData });
      setImportStatus(`Importação concluída: ${result.importedCount ?? 0} importadas; ${result.skippedDuplicatesCount ?? 0} duplicatas ignoradas.`, "success");
      await loadHistory(0);
      window.setTimeout(closeImport, 1400);
    } catch (error) { setImportStatus(error.message); confirmImport.disabled = false; }
  });
  document.addEventListener("keydown", (event) => { if (event.key === "Escape" && !importModal.hidden) closeImport(); });

  let filters = {};
  let page = 0;
  let loadId = 0;
  let activePortfolioId;

  function showError(message) {
    errorBanner.textContent = message;
    errorBanner.hidden = !message;
  }

  function showEmpty(message) {
    empty.textContent = message;
    empty.hidden = false;
  }

  function action(label, onClick) {
    const button = document.createElement("button");
    button.className = "button button--ghost";
    button.type = "button";
    button.textContent = label;
    button.addEventListener("click", onClick);
    return button;
  }

  function renderRow(transaction) {
    const row = document.createElement("tr");
    const values = [
      dateFormat.format(new Date(transaction.transactionDate)),
      transaction.ticker,
      transaction.type === "BUY" ? "Compra" : "Venda",
      quantityFormat.format(transaction.quantity),
      priceFormat.format(transaction.unitPrice),
    ];
    values.forEach((value) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.append(cell);
    });

    const actions = document.createElement("td");
    actions.className = "history-row-actions";
    const edit = action("Editar", () => window.ValoraTransactionUI.openEdit(transaction));
    const remove = action("Excluir", () => deleteTransaction(transaction, edit, remove));
    remove.classList.add("history-delete");
    actions.append(edit, remove);
    row.append(actions);
    return row;
  }

  async function loadHistory(requestedPage = 0, selectedPortfolio) {
    const currentLoad = ++loadId;
    page = requestedPage;
    showError("");
    loading.hidden = false;
    empty.hidden = true;
    table.hidden = true;
    pagination.hidden = true;
    rows.replaceChildren();

    try {
      const portfolio = selectedPortfolio === undefined ? await api.getPortfolio() : selectedPortfolio;
      if (currentLoad !== loadId) return;
      activePortfolioId = portfolio?.id;
      if (!activePortfolioId) {
        showEmpty("Crie uma carteira para registrar e consultar seus lançamentos.");
        return;
      }

      const query = new URLSearchParams({
        portfolioId: activePortfolioId,
        page: requestedPage,
        size: PAGE_SIZE,
        sort: "transactionDate,desc",
        ...filters,
      });
      const result = await api.request(`/transactions?${query}`);
      if (currentLoad !== loadId) return;

      const lastPage = Math.max(0, result.totalPages - 1);
      if (requestedPage > lastPage) {
        return loadHistory(lastPage, portfolio);
      }

      page = result.number;
      rows.replaceChildren(...result.content.map(renderRow));
      table.hidden = result.content.length === 0;
      if (table.hidden) {
        showEmpty(filters.ticker || filters.type
          ? "Nenhum lançamento corresponde aos filtros."
          : "Sua carteira ainda não tem lançamentos. Registre uma compra ou venda para começar.");
        return;
      }

      pageInfo.textContent = `Página ${page + 1} de ${result.totalPages}`;
      previous.disabled = result.first;
      next.disabled = result.last;
      pagination.hidden = false;
    } catch (error) {
      if (currentLoad === loadId) showError(error.message);
    } finally {
      if (currentLoad === loadId) loading.hidden = true;
    }
  }

  async function deleteTransaction(transaction, edit, remove) {
    if (!window.confirm(`Excluir o lançamento de ${transaction.ticker}?`)) return;
    const currentLoad = loadId;
    edit.disabled = true;
    remove.disabled = true;
    showError("");
    try {
      await api.request(`/transactions/${encodeURIComponent(transaction.transactionId)}`, {
        method: "DELETE",
      });
      if (activePortfolioId === transaction.portfolioId) await loadHistory(page);
    } catch (error) {
      if (currentLoad === loadId) showError(error.message);
      edit.disabled = false;
      remove.disabled = false;
    }
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    filters = {};
    const ticker = tickerInput.value.trim().toUpperCase();
    if (ticker) filters.ticker = ticker;
    if (typeInput.value) filters.type = typeInput.value;
    loadHistory();
  });
  previous.addEventListener("click", () => loadHistory(page - 1));
  next.addEventListener("click", () => loadHistory(page + 1));
  window.addEventListener("valora:portfolio-changed", (event) => loadHistory(0, event.detail.portfolio));
  document.addEventListener("valora:transaction-saved", (event) => {
    if (event.detail.portfolioId === activePortfolioId) loadHistory(page);
  });
  logout.addEventListener("click", async () => {
    logout.disabled = true;
    try {
      await api.logout();
    } catch (error) {
      window.location.replace("/login.html");
    }
  });

  loadHistory();
})();
