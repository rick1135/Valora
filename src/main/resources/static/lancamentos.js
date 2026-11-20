(function () {
  const PAGE_SIZE = 20;
  const PREVIEW_LIMIT = 20;
  const dateFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short" });
  const quantityFormat = new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 8 });
  const priceFormat = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 8,
  });
  const totalFormat = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
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
  const templateButton = document.querySelector("#csv-template-btn");
  const fileInput = document.querySelector("#csv-file-input");
  const preview = document.querySelector("#csv-preview");
  const importStatus = document.querySelector("#csv-import-status");
  const confirmImport = document.querySelector("#csv-confirm-btn");
  let importFormData = null;
  let previewPortfolioId = null;
  let previewFileKey = null;
  let previewSeq = 0;
  let importCloseTimer = null;

  function formatPreviewDate(value) {
    if (!value) return "";
    const text = String(value);
    const isoDate = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
    if (isoDate) return `${isoDate[3]}/${isoDate[2]}/${isoDate[1]}`;
    const parsed = new Date(text);
    return Number.isNaN(parsed.getTime()) ? text : dateFormat.format(parsed);
  }

  function formatOperationType(type) {
    if (type === "BUY") return "Compra";
    if (type === "SELL") return "Venda";
    return String(type || "");
  }

  function openImport() {
    importModal.hidden = false;
    importBackdrop.hidden = false;
    setImportStatus("");
    invalidatePreview();
    fileInput.focus();
  }

  function downloadCsvTemplate() {
    const csvContent = "\uFEFFdata;tipo;codigo_acao;quantidade;preco_unitario\r\n"
      + "17/11/2026;Compra;ITSA4;10;13,47\r\n"
      + "18/11/2026;Compra;PETR4;5;38,20\r\n";
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "modelo-valora.csv";
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function setImportStatus(message, tone = "error") {
    importStatus.textContent = message;
    importStatus.hidden = !message;
    importStatus.dataset.tone = tone;
  }

  function invalidatePreview(message = "") {
    previewSeq += 1;
    if (importCloseTimer !== null) {
      window.clearTimeout(importCloseTimer);
      importCloseTimer = null;
    }
    importFormData = null;
    previewPortfolioId = null;
    previewFileKey = null;
    preview.hidden = true;
    preview.replaceChildren();
    confirmImport.disabled = true;
    if (message) setImportStatus(message);
  }

  function closeImport() {
    importModal.hidden = true;
    importBackdrop.hidden = true;
    invalidatePreview();
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

  function appendPreviewTable(parent, allRows) {
    const heading = document.createElement("h3");
    heading.textContent = "Prévia das operações válidas";
    const wrap = document.createElement("div");
    wrap.className = "csv-preview-table-wrap";
    const previewTable = document.createElement("table");
    previewTable.className = "csv-preview-table";

    const thead = document.createElement("thead");
    const headRow = document.createElement("tr");
    ["Linha", "Data", "Tipo", "Código", "Quantidade", "Preço unitário", "Valor total"].forEach((label) => {
      const th = document.createElement("th");
      th.scope = "col";
      th.textContent = label;
      headRow.append(th);
    });
    thead.append(headRow);

    const tbody = document.createElement("tbody");
    allRows.slice(0, PREVIEW_LIMIT).forEach((item) => {
      const tr = document.createElement("tr");
      const qty = Number(item.quantity);
      const unitPrice = Number(item.unitPrice);
      const total = item.totalValue != null ? Number(item.totalValue) : qty * unitPrice;
      const cells = [
        String(item.lineNumber ?? ""),
        formatPreviewDate(item.date || item.transactionDate),
        formatOperationType(item.type),
        String(item.ticker || item.assetTicker || ""),
        Number.isFinite(qty) ? quantityFormat.format(qty) : String(item.quantity ?? ""),
        Number.isFinite(unitPrice) ? priceFormat.format(unitPrice) : String(item.unitPrice ?? ""),
        Number.isFinite(total) ? totalFormat.format(total) : "",
      ];
      cells.forEach((value) => {
        const td = document.createElement("td");
        td.textContent = value;
        tr.append(td);
      });
      tbody.append(tr);
    });

    previewTable.append(thead, tbody);
    wrap.append(previewTable);
    parent.append(heading, wrap);
    if (allRows.length > PREVIEW_LIMIT) {
      const note = document.createElement("p");
      note.className = "csv-preview-note";
      note.textContent = `Mostrando as primeiras ${PREVIEW_LIMIT} de ${allRows.length} linhas válidas.`;
      parent.append(note);
    }
  }

  function renderImportPreview(result) {
    preview.replaceChildren();
    preview.hidden = false;
    const validRows = Array.isArray(result.validRows) ? result.validRows : (Array.isArray(result.sample) ? result.sample : []);
    const computedVolume = result.totalVolume != null
      ? Number(result.totalVolume)
      : validRows.reduce((acc, item) => acc + Number(item.quantity || 0) * Number(item.unitPrice || 0), 0);
    const counts = document.createElement("p");
    counts.className = "csv-preview-summary";
    counts.textContent = `Linhas: ${result.totalRows ?? 0} · Válidas: ${result.validCount ?? 0} · Com erros: ${result.errorCount ?? 0} · Volume total: ${totalFormat.format(Number.isFinite(computedVolume) ? computedVolume : 0)}`;
    preview.append(counts);
    const missing = Array.isArray(result.missingTickers) ? result.missingTickers : [];
    if (missing.length) {
      const warning = document.createElement("p");
      warning.textContent = "Estes ativos precisam ser cadastrados ou importados antes da importação.";
      preview.append(warning);
      appendImportList(preview, "Ativos ausentes", missing);
      const importMissingButton = document.createElement("button");
      importMissingButton.className = "button button--ghost";
      importMissingButton.type = "button";
      importMissingButton.textContent = "Importar ativos ausentes da B3";
      importMissingButton.addEventListener("click", async () => {
        const batchSeq = previewSeq;
        const targetPortfolioId = previewPortfolioId;
        const targetFileKey = previewFileKey;
        const currentFileKey = () => {
          const file = fileInput.files?.[0];
          return file ? `${file.name}:${file.size}:${file.lastModified}` : null;
        };
        const isContextValid = () => batchSeq === previewSeq
          && !importModal.hidden
          && targetPortfolioId === activePortfolioId
          && targetFileKey === currentFileKey();
        importMissingButton.disabled = true;
        setImportStatus("Importando ativos da B3...", "info");
        const imported = [];
        const failures = [];
        for (const ticker of missing) {
          if (!isContextValid()) return;
          try {
            await api.request("/assets/import", { method: "POST", body: { ticker } });
            if (!isContextValid()) return;
            imported.push(ticker);
          } catch (error) {
            if (!isContextValid()) return;
            failures.push({ ticker, message: error?.message || "Falha na importação." });
          }
        }
        if (!isContextValid()) return;
        const summary = [`${imported.length} ativo(s) importado(s).`];
        if (failures.length) summary.push(`Falhas: ${failures.map((item) => `${item.ticker} (${item.message})`).join("; ")}`);
        setImportStatus(summary.join(" "), failures.length ? "error" : "success");
        if (imported.length) {
          if (!isContextValid()) return;
          importForm.requestSubmit();
        }
        else importMissingButton.disabled = false;
      });
      preview.append(importMissingButton);
    }
    const errors = Array.isArray(result.errors) ? result.errors : [];
    if (errors.length) appendImportList(preview, "Erros encontrados", errors, (item) => `Linha ${item.lineNumber ?? item.line ?? item.row ?? "?"}: ${item.message || "Erro de validação"}`);
    if (validRows.length) appendPreviewTable(preview, validRows);
    confirmImport.disabled = !(result.validCount > 0 && result.errorCount === 0 && missing.length === 0);
  }

  importButton.addEventListener("click", openImport);
  if (templateButton) templateButton.addEventListener("click", downloadCsvTemplate);
  document.querySelector("#csv-import-close").addEventListener("click", closeImport);
  importBackdrop.addEventListener("click", closeImport);
  fileInput.addEventListener("change", () => invalidatePreview());
  importForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = fileInput.files?.[0];
    if (!file) { setImportStatus("Selecione um arquivo CSV."); return; }
    if (file.size > 2 * 1024 * 1024) { setImportStatus("O arquivo deve ter no máximo 2 MB."); return; }
    const seq = ++previewSeq;
    const fileKey = `${file.name}:${file.size}:${file.lastModified}`;
    const analyze = document.querySelector("#csv-analyze-btn");
    analyze.disabled = true;
    setImportStatus("Analisando arquivo...", "info");
    try {
      let targetPortfolioId = activePortfolioId;
      if (!targetPortfolioId) {
        targetPortfolioId = (await api.getPortfolio())?.id;
        if (seq !== previewSeq || importModal.hidden) return;
        if (targetPortfolioId && !activePortfolioId) {
          activePortfolioId = targetPortfolioId;
        }
      }
      if (!targetPortfolioId) {
        throw new Error("Nenhuma carteira ativa encontrada.");
      }
      const formData = new FormData();
      formData.append("file", file);
      const result = await api.request(`/transactions/import/preview?portfolioId=${encodeURIComponent(targetPortfolioId)}`, { method: "POST", body: formData });
      if (seq !== previewSeq || importModal.hidden) return;
      previewPortfolioId = targetPortfolioId;
      previewFileKey = fileKey;
      importFormData = formData;
      renderImportPreview(result);
      setImportStatus("Prévia pronta.", "success");
    } catch (error) {
      if (seq === previewSeq && !importModal.hidden) setImportStatus(error.message);
    }
    finally { analyze.disabled = false; }
  });
  confirmImport.addEventListener("click", async () => {
    const file = fileInput.files?.[0];
    const fileKey = file ? `${file.name}:${file.size}:${file.lastModified}` : null;
    if (!importFormData || !previewPortfolioId || previewPortfolioId !== activePortfolioId || fileKey !== previewFileKey) {
      invalidatePreview("Carteira ou arquivo alterado. Analise o arquivo novamente antes de confirmar.");
      return;
    }
    if (confirmImport.disabled) return;
    const confirmationSeq = previewSeq;
    const targetPortfolioId = previewPortfolioId;
    const targetFileKey = previewFileKey;
    const submittedFormData = importFormData;
    const isContextValid = () => {
      const currentFile = fileInput.files?.[0];
      const currentFileKey = currentFile ? `${currentFile.name}:${currentFile.size}:${currentFile.lastModified}` : null;
      return confirmationSeq === previewSeq && !importModal.hidden
        && targetPortfolioId === activePortfolioId && targetPortfolioId === previewPortfolioId
        && targetFileKey === previewFileKey && targetFileKey === currentFileKey;
    };
    confirmImport.disabled = true;
    setImportStatus("Importando operações...", "info");
    try {
      const result = await api.request(`/transactions/import?portfolioId=${encodeURIComponent(targetPortfolioId)}`, { method: "POST", body: submittedFormData });
      if (!isContextValid()) return;
      setImportStatus(`Importação concluída: ${result.importedCount ?? 0} importadas; ${result.skippedDuplicatesCount ?? 0} duplicatas ignoradas.`, "success");
      if (targetPortfolioId === activePortfolioId) await loadHistory(0);
      if (!isContextValid()) return;
      importCloseTimer = window.setTimeout(() => {
        importCloseTimer = null;
        if (isContextValid()) closeImport();
      }, 1400);
    } catch (error) {
      if (!isContextValid()) return;
      setImportStatus(error.message);
      confirmImport.disabled = false;
    }
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
    const totalValue = Number(transaction.quantity) * Number(transaction.unitPrice);
    const values = [
      dateFormat.format(new Date(transaction.transactionDate)),
      transaction.ticker,
      transaction.type === "BUY" ? "Compra" : "Venda",
      quantityFormat.format(transaction.quantity),
      priceFormat.format(transaction.unitPrice),
      Number.isFinite(totalValue) ? totalFormat.format(totalValue) : "",
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
  window.addEventListener("valora:portfolio-changed", (event) => {
    const shouldWarn = !importModal.hidden && Boolean(importFormData || !preview.hidden);
    invalidatePreview(shouldWarn ? "Carteira alterada. Analise o arquivo novamente antes de confirmar." : "");
    loadHistory(0, event.detail.portfolio);
  });
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

  if (new URLSearchParams(window.location.search).get("import") === "1") {
    openImport();
    window.history.replaceState({}, "", window.location.pathname);
  }

  loadHistory();
})();
