(() => {
  const MIN_TARGET_TOTAL = 99.9;
  const MAX_TARGET_TOTAL = 100.1;
  const SEARCH_DEBOUNCE_MS = 220;

  const CATEGORY_LABELS = {
    ACOES: "A\u00e7\u00f5es",
    FII: "FIIs",
    ETF: "ETFs",
    CRIPTO: "Criptos",
    RENDA_FIXA: "Renda Fixa",
  };

  const currencyFormatter = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  const percentFormatter = new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  const integerFormatter = new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: 0,
  });

  const state = {
    portfolioId: null,
    portfolioName: "Carteira",
    summary: null,
    savedTargets: [],
    draftTargets: [],
    rebalanceOrders: [],
    lastContribution: 1000,
    isSaving: false,
    isSimulating: false,
    searchTimer: null,
    searchSeq: 0,
  };

  function toNumber(value) {
    if (value === null || value === undefined || value === "") {
      return 0;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function parseContributionAmount(value) {
    if (typeof value === "number") {
      return Number.isFinite(value) ? value : null;
    }
    if (typeof value === "string") {
      const trimmed = value.trim();
      if (!trimmed) {
        return null;
      }
      const parsed = Number(trimmed);
      return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
  }

  function formatCurrency(value) {
    return currencyFormatter.format(toNumber(value));
  }

  function formatPercent(value) {
    return `${percentFormatter.format(toNumber(value))}%`;
  }

  function getCategoryLabel(category) {
    return CATEGORY_LABELS[category] || String(category || "Categoria");
  }

  function createEl(tag, className, text) {
    const el = document.createElement(tag);
    if (className) {
      el.className = className;
    }
    if (text !== undefined && text !== null) {
      el.textContent = String(text);
    }
    return el;
  }

  async function apiRequest(path, options) {
    if (!window.ValoraAPI || typeof window.ValoraAPI.request !== "function") {
      throw new Error("Cliente ValoraAPI indispon\u00edvel.");
    }
    return window.ValoraAPI.request(path, options);
  }

  async function resolvePortfolio() {
    if (!window.ValoraAPI || typeof window.ValoraAPI.getPortfolio !== "function") {
      throw new Error("Cliente ValoraAPI indispon\u00edvel.");
    }
    const portfolio = await window.ValoraAPI.getPortfolio();
    const portfolioId = typeof portfolio === "string" ? portfolio : portfolio?.id;
    if (!portfolioId) {
      throw new Error("Nenhuma carteira ativa encontrada.");
    }
    const portfolioName =
      typeof portfolio === "object" && typeof portfolio?.name === "string" && portfolio.name.trim()
        ? portfolio.name.trim()
        : "Carteira";

    state.portfolioId = portfolioId;
    state.portfolioName = portfolioName;

    const label = document.querySelector("#portfolio-name");
    if (label) {
      label.textContent = portfolioName;
    }
    return { id: portfolioId, name: portfolioName };
  }

  function setPageLoading(isLoading) {
    const banner = document.querySelector("#metas-loading");
    if (banner) {
      banner.hidden = !isLoading;
    }
    const main = document.querySelector(".dashboard");
    if (main) {
      main.setAttribute("aria-busy", String(Boolean(isLoading)));
    }
  }

  function setPageError(message) {
    const banner = document.querySelector("#metas-error");
    if (!banner) return;
    if (!message) {
      banner.hidden = true;
      banner.textContent = "";
      return;
    }
    banner.textContent = message;
    banner.hidden = false;
  }

  function setPageStatus(message) {
    const banner = document.querySelector("#metas-status");
    if (!banner) return;
    if (!message) {
      banner.hidden = true;
      banner.textContent = "";
      return;
    }
    banner.textContent = message;
    banner.hidden = false;
  }

  function setRebalanceStatus(message, tone = "info") {
    const el = document.querySelector("#rebalance-status");
    if (!el) return;
    if (!message) {
      el.hidden = true;
      el.textContent = "";
      delete el.dataset.tone;
      return;
    }
    el.hidden = false;
    el.dataset.tone = tone;
    el.textContent = message;
  }

  function calculateDraftTotal() {
    return state.draftTargets.reduce((acc, item) => acc + toNumber(item.targetPercentage), 0);
  }

  function isDraftTotalValid(total) {
    return total >= MIN_TARGET_TOTAL && total <= MAX_TARGET_TOTAL;
  }

  function renderSummaryCards() {
    const grid = document.querySelector("#metas-summary-grid");
    if (!grid) return;

    const totalPatrimony = toNumber(state.summary?.totalPatrimony);
    const savedCount = state.savedTargets.length;
    const draftTotal = calculateDraftTotal();
    const totalContributionSuggested = state.rebalanceOrders.reduce(
      (acc, order) => acc + toNumber(order.suggestedInvestment),
      0
    );

    const cards = [
      {
        label: "Patrim\u00f4nio atual",
        value: formatCurrency(totalPatrimony),
        note: "Base consolidada da carteira",
      },
      {
        label: "Metas salvas",
        value: `${savedCount} ${savedCount === 1 ? "alvo" : "alvos"}`,
        note: savedCount > 0 ? "Categorias e/ou ativos configurados" : "Nenhuma meta salva no servidor",
      },
      {
        label: "Soma do formul\u00e1rio",
        value: formatPercent(draftTotal),
        note: isDraftTotalValid(draftTotal) ? "Pronta para salvar (~100%)" : "Ajuste para somar entre 99,90% e 100,10%",
      },
      {
        label: "Aporte alocado na simula\u00e7\u00e3o",
        value: formatCurrency(totalContributionSuggested),
        note: `Aporte informado: ${formatCurrency(state.lastContribution)}`,
      },
    ];

    const nodes = cards.map((card) => {
      const article = createEl("article", "metric-card");
      article.append(
        createEl("p", "metric-card__label", card.label),
        createEl("strong", "metric-card__value", card.value),
        createEl("span", "metric-card__note", card.note)
      );
      return article;
    });

    grid.replaceChildren(...nodes);
  }

  function updateFormValidationUI() {
    const total = calculateDraftTotal();
    const validSum = isDraftTotalValid(total);
    const hasItems = state.draftTargets.length > 0;
    const allPositive = state.draftTargets.every((item) => {
      const pct = toNumber(item.targetPercentage);
      return pct > 0 && pct <= 100;
    });

    const pill = document.querySelector("#targets-total-pill");
    if (pill) {
      pill.textContent = `Soma: ${formatPercent(total)}`;
      if (!hasItems) {
        pill.dataset.state = "empty";
      } else {
        pill.dataset.state = validSum && allPositive ? "valid" : "invalid";
      }
    }

    const hint = document.querySelector("#targets-validation-hint");
    if (hint) {
      hint.className = "metas-validation-hint";
      if (!hasItems) {
        hint.textContent = "Adicione ao menos uma meta de categoria ou ativo.";
      } else if (!allPositive) {
        hint.textContent = "Cada meta deve ter percentual maior que 0% e at\u00e9 100%.";
        hint.classList.add("metas-validation-hint--error");
      } else if (!validSum) {
        const diff = 100 - total;
        const diffLabel = diff > 0 ? `faltam ${formatPercent(diff)}` : `excedeu ${formatPercent(Math.abs(diff))}`;
        hint.textContent = `A soma deve ficar entre 99,90% e 100,10% (${diffLabel}).`;
        hint.classList.add("metas-validation-hint--error");
      } else {
        hint.textContent = "Aloca\u00e7\u00e3o v\u00e1lida (~100%).";
        hint.classList.add("metas-validation-hint--valid");
      }
    }

    const saveBtn = document.querySelector("#save-targets-btn");
    if (saveBtn) {
      saveBtn.disabled = state.isSaving || !hasItems || !allPositive || !validSum;
    }

    renderSummaryCards();
  }

  function renderDraftTargets() {
    const list = document.querySelector("#targets-form-list");
    const emptyBox = document.querySelector("#targets-form-empty");
    if (!list || !emptyBox) return;

    emptyBox.hidden = state.draftTargets.length > 0;

    const rows = state.draftTargets.map((item, index) => {
      const li = createEl("li", "metas-target-row");

      const identity = createEl("div", "metas-target-identity");
      const isAsset = Boolean(item.assetId);
      const badge = createEl(
        "span",
        `metas-kind-badge ${isAsset ? "metas-kind-badge--asset" : "metas-kind-badge--category"}`,
        isAsset ? "Ativo" : "Categoria"
      );

      const labels = createEl("div", "metas-target-labels");
      const titleText = isAsset ? item.ticker : getCategoryLabel(item.assetCategory);
      const subText = isAsset
        ? item.assetName || "Ativo espec\u00edfico"
        : `Classe: ${item.assetCategory}`;
      labels.append(createEl("strong", "", titleText), createEl("span", "", subText));
      identity.append(badge, labels);

      const percentWrap = createEl("div", "metas-percent-wrap");
      const input = document.createElement("input");
      input.className = "metas-input";
      input.type = "number";
      input.min = "0.01";
      input.max = "100";
      input.step = "0.01";
      input.value = item.targetPercentage !== undefined && item.targetPercentage !== null ? String(item.targetPercentage) : "";
      input.setAttribute("aria-label", `Percentual alvo para ${titleText}`);
      input.addEventListener("input", () => {
        state.draftTargets[index].targetPercentage = input.value;
        updateFormValidationUI();
      });
      percentWrap.append(input, createEl("span", "", "%"));

      const removeBtn = createEl("button", "metas-remove-btn", "Remover");
      removeBtn.type = "button";
      removeBtn.setAttribute("aria-label", `Remover meta ${titleText}`);
      removeBtn.addEventListener("click", () => {
        state.draftTargets.splice(index, 1);
        renderDraftTargets();
      });

      li.append(identity, percentWrap, removeBtn);
      return li;
    });

    list.replaceChildren(...rows);
    updateFormValidationUI();
  }

  function addCategoryTarget(category) {
    if (!category) return;
    const exists = state.draftTargets.some((item) => !item.assetId && item.assetCategory === category);
    if (exists) {
      setPageStatus(`A categoria ${getCategoryLabel(category)} j\u00e1 est\u00e1 na lista.`);
      return;
    }

    setPageStatus("");
    state.draftTargets.push({
      assetCategory: category,
      assetId: null,
      ticker: null,
      assetName: null,
      targetPercentage: state.draftTargets.length === 0 ? 100 : 0,
    });
    renderDraftTargets();
  }

  function addAssetTarget(asset) {
    if (!asset || !asset.id) return;
    const exists = state.draftTargets.some((item) => item.assetId === asset.id);
    if (exists) {
      setPageStatus(`O ativo ${asset.ticker} j\u00e1 est\u00e1 na lista.`);
      return;
    }

    setPageStatus("");
    state.draftTargets.push({
      assetCategory: null,
      assetId: asset.id,
      ticker: asset.ticker,
      assetName: asset.name || asset.ticker,
      targetPercentage: state.draftTargets.length === 0 ? 100 : 0,
    });
    renderDraftTargets();
  }

  function hideAssetSuggestions() {
    const list = document.querySelector("#asset-search-suggestions");
    if (!list) return;
    list.hidden = true;
    list.replaceChildren();
  }

  function renderAssetSuggestions(assets) {
    const list = document.querySelector("#asset-search-suggestions");
    const input = document.querySelector("#asset-search-input");
    if (!list || !input) return;

    if (!Array.isArray(assets) || assets.length === 0) {
      const emptyLi = createEl("li");
      const msg = createEl("div", "metas-suggestion-meta", "Nenhum ativo encontrado.");
      msg.style.padding = "8px 10px";
      emptyLi.append(msg);
      list.replaceChildren(emptyLi);
      list.hidden = false;
      return;
    }

    const items = assets.slice(0, 8).map((asset) => {
      const li = document.createElement("li");
      const btn = createEl("button", "metas-suggestion-item");
      btn.type = "button";
      btn.append(
        createEl("strong", "", asset.ticker),
        createEl("span", "metas-suggestion-meta", `${asset.name || ""} · ${getCategoryLabel(asset.category)}`)
      );
      btn.addEventListener("click", () => {
        addAssetTarget(asset);
        input.value = "";
        hideAssetSuggestions();
      });
      li.append(btn);
      return li;
    });

    list.replaceChildren(...items);
    list.hidden = false;
  }

  function wireAssetSearch() {
    const input = document.querySelector("#asset-search-input");
    if (!input) return;

    input.addEventListener("input", () => {
      const query = input.value.trim();
      window.clearTimeout(state.searchTimer);
      if (query.length < 2) {
        hideAssetSuggestions();
        return;
      }

      const seq = ++state.searchSeq;
      state.searchTimer = window.setTimeout(async () => {
        try {
          const results = await apiRequest(`/assets/search?ticker=${encodeURIComponent(query)}`);
          if (seq !== state.searchSeq) return;
          renderAssetSuggestions(results);
        } catch {
          if (seq === state.searchSeq) {
            hideAssetSuggestions();
          }
        }
      }, SEARCH_DEBOUNCE_MS);
    });

    document.addEventListener("click", (event) => {
      const container = document.querySelector(".metas-asset-search");
      if (container && event.target instanceof Node && !container.contains(event.target)) {
        hideAssetSuggestions();
      }
    });
  }

  function renderRebalanceResults() {
    const emptyEl = document.querySelector("#targets-empty");
    const tableCard = document.querySelector("#rebalance-table-card");
    const tbody = document.querySelector("#rebalance-tbody");
    if (!emptyEl || !tableCard || !tbody) return;

    if (state.savedTargets.length === 0) {
      emptyEl.hidden = false;
      tableCard.hidden = true;
      tbody.replaceChildren();
      renderSummaryCards();
      return;
    }

    emptyEl.hidden = true;

    if (state.rebalanceOrders.length === 0) {
      tableCard.hidden = true;
      tbody.replaceChildren();
      renderSummaryCards();
      return;
    }

    tableCard.hidden = false;

    const rows = state.rebalanceOrders.map((order) => {
      const tr = document.createElement("tr");

      const targetTd = document.createElement("td");
      if (order.ticker) {
        targetTd.append(
          createEl("strong", "", order.ticker),
          createEl("small", "", getCategoryLabel(order.category))
        );
      } else {
        targetTd.append(createEl("strong", "", getCategoryLabel(order.category)));
      }

      const currentTd = createEl("td", "", formatCurrency(order.currentAmount));
      const idealTd = createEl("td", "", formatCurrency(order.idealAmount));

      const gapValue = toNumber(order.gapAmount);
      const gapTd = createEl(
        "td",
        gapValue > 0 ? "metas-value-positive" : gapValue < 0 ? "metas-value-negative" : "",
        formatCurrency(gapValue)
      );

      const suggestedValue = toNumber(order.suggestedInvestment);
      const suggestedTd = createEl(
        "td",
        suggestedValue > 0 ? "metas-value-positive" : "",
        formatCurrency(suggestedValue)
      );

      const priceTd = document.createElement("td");
      const priceVal = toNumber(order.currentPrice);
      priceTd.append(createEl("span", "", priceVal > 0 ? formatCurrency(priceVal) : "—"));
      if (order.marketPriceReliable === false) {
        priceTd.append(createEl("span", "metas-unreliable-tag", "Cota\u00e7\u00e3o fallback"));
      }

      const qtyTd = document.createElement("td");
      if (order.suggestedQuantity !== null && order.suggestedQuantity !== undefined) {
        qtyTd.append(createEl("strong", "", `${integerFormatter.format(toNumber(order.suggestedQuantity))} un.`));
        if (toNumber(order.residualCashAmount) > 0) {
          qtyTd.append(createEl("small", "", `Sobra: ${formatCurrency(order.residualCashAmount)}`));
        }
      } else {
        qtyTd.textContent = "—";
      }

      tr.append(targetTd, currentTd, idealTd, gapTd, suggestedTd, priceTd, qtyTd);
      return tr;
    });

    tbody.replaceChildren(...rows);
    renderSummaryCards();
  }

  async function runRebalanceSimulation(contributionAmount) {
    if (!state.portfolioId) return;

    const amount = parseContributionAmount(contributionAmount);
    if (amount === null) {
      setRebalanceStatus("Informe um valor de aporte v\u00e1lido.", "error");
      return;
    }
    if (amount < 0) {
      setRebalanceStatus("O valor do aporte n\u00e3o pode ser negativo.", "error");
      return;
    }

    state.lastContribution = amount;
    state.isSimulating = true;
    const simulateBtn = document.querySelector("#simulate-btn");
    if (simulateBtn) simulateBtn.disabled = true;

    if (state.savedTargets.length === 0) {
      state.rebalanceOrders = [];
      setRebalanceStatus("");
      renderRebalanceResults();
      state.isSimulating = false;
      if (simulateBtn) simulateBtn.disabled = false;
      return;
    }

    setRebalanceStatus("Calculando sugest\u00f5es de aporte...", "info");
    try {
      const orders = await apiRequest(
        `/portfolios/${encodeURIComponent(state.portfolioId)}/rebalance?contributionAmount=${encodeURIComponent(amount.toFixed(2))}`
      );
      state.rebalanceOrders = Array.isArray(orders) ? orders : [];
      setRebalanceStatus("");
      renderRebalanceResults();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Erro ao simular rebalanceamento.";
      setRebalanceStatus(message, "error");
    } finally {
      state.isSimulating = false;
      if (simulateBtn) simulateBtn.disabled = false;
    }
  }

  async function handleSaveTargets(event) {
    event.preventDefault();
    if (!state.portfolioId || state.isSaving) return;

    setPageError("");
    setPageStatus("");

    const payload = state.draftTargets.map((item) => ({
      assetCategory: item.assetId ? null : item.assetCategory,
      assetId: item.assetId || null,
      targetPercentage: Number(toNumber(item.targetPercentage).toFixed(2)),
    }));

    state.isSaving = true;
    updateFormValidationUI();

    try {
      await apiRequest(`/portfolios/${encodeURIComponent(state.portfolioId)}/targets`, {
        method: "PUT",
        body: payload,
      });
      setPageStatus("Metas de aloca\u00e7\u00e3o salvas com sucesso.");
      await loadMetasPage({ preserveStatus: true });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Erro ao salvar metas.";
      setPageError(`N\u00e3o foi poss\u00edvel salvar as metas: ${message}`);
    } finally {
      state.isSaving = false;
      updateFormValidationUI();
    }
  }

  async function loadMetasPage(options = {}) {
    if (!options.preserveStatus) {
      setPageStatus("");
    }
    setPageError("");
    setPageLoading(true);

    try {
      const portfolio = await resolvePortfolio();
      const portfolioId = portfolio.id;

      const [summary, targets] = await Promise.all([
        apiRequest(`/portfolios/${encodeURIComponent(portfolioId)}/summary`),
        apiRequest(`/portfolios/${encodeURIComponent(portfolioId)}/targets`),
      ]);

      state.summary = summary || {};
      state.savedTargets = Array.isArray(targets) ? targets : [];
      state.draftTargets = state.savedTargets.map((target) => ({
        id: target.id || null,
        assetCategory: target.assetCategory || null,
        assetId: target.assetId || null,
        ticker: target.ticker || null,
        assetName: target.assetName || null,
        targetPercentage: target.targetPercentage ?? 0,
      }));

      renderDraftTargets();

      const contributionInput = document.querySelector("#contribution-input");
      const rawContribution = contributionInput ? contributionInput.value : state.lastContribution;
      await runRebalanceSimulation(rawContribution);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Erro ao carregar metas.";
      setPageError(`N\u00e3o foi poss\u00edvel carregar os dados de metas: ${message}`);
    } finally {
      setPageLoading(false);
    }
  }

  function wireControls() {
    const addCategoryBtn = document.querySelector("#add-category-btn");
    const categorySelect = document.querySelector("#category-select");
    if (addCategoryBtn && categorySelect) {
      addCategoryBtn.addEventListener("click", () => {
        addCategoryTarget(categorySelect.value);
      });
    }

    const loadPresetBtn = document.querySelector("#load-preset-btn");
    if (loadPresetBtn) {
      loadPresetBtn.addEventListener("click", () => {
        state.draftTargets = [
          { assetCategory: "ACOES", assetId: null, ticker: null, assetName: null, targetPercentage: 50 },
          { assetCategory: "FII", assetId: null, ticker: null, assetName: null, targetPercentage: 30 },
          { assetCategory: "RENDA_FIXA", assetId: null, ticker: null, assetName: null, targetPercentage: 20 },
        ];
        renderDraftTargets();
      });
    }

    const targetsForm = document.querySelector("#targets-form");
    if (targetsForm) {
      targetsForm.addEventListener("submit", handleSaveTargets);
    }

    const rebalanceForm = document.querySelector("#rebalance-form");
    const contributionInput = document.querySelector("#contribution-input");
    if (rebalanceForm && contributionInput) {
      rebalanceForm.addEventListener("submit", (event) => {
        event.preventDefault();
        runRebalanceSimulation(contributionInput.value);
      });
    }

    document.querySelectorAll(".metas-chip[data-amount]").forEach((chip) => {
      chip.addEventListener("click", () => {
        const amount = parseContributionAmount(chip.getAttribute("data-amount"));
        if (amount === null) return;
        if (contributionInput) {
          contributionInput.value = amount.toFixed(2);
        }
        runRebalanceSimulation(amount);
      });
    });

    const logoutBtn = document.querySelector("#logout-btn");
    if (logoutBtn) {
      logoutBtn.addEventListener("click", async () => {
        logoutBtn.disabled = true;
        try {
          if (window.ValoraAPI && typeof window.ValoraAPI.logout === "function") {
            await window.ValoraAPI.logout();
          }
        } catch (error) {
          const message =
            error instanceof Error && error.message
              ? error.message
              : "Erro ao encerrar sess\u00e3o.";
          setPageError(message);
          window.location.replace("/login.html");
        } finally {
          logoutBtn.disabled = false;
        }
      });
    }

    wireAssetSearch();
  }

  document.addEventListener("DOMContentLoaded", () => {
    wireControls();
    loadMetasPage();
  });

  document.addEventListener("valora:transaction-saved", () => {
    loadMetasPage({ preserveStatus: true });
  });

  window.addEventListener("valora:portfolio-changed", () => {
    loadMetasPage();
  });
})();
