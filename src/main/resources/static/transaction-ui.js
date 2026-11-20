(function () {
  const SEARCH_DEBOUNCE_MS = 220;
  const CURRENCY_FORMATTER = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
  });

  const CATEGORY_LABELS = {
    ACOES: "A\u00e7\u00f5es",
    FII: "FII",
    ETF: "ETF",
    CRIPTO: "Cripto",
    RENDA_FIXA: "Renda Fixa",
  };

  const state = {
    elements: null,
    selectedAsset: null,
    suggestions: [],
    activeIndex: -1,
    searchTimer: null,
    searchSeq: 0,
    selectionSeq: 0,
    isSubmitting: false,
    lastTrigger: null,
    editing: null,
    originalDateInput: "",
    idempotencyKey: null,
  };

  function renewIdempotencyKey() {
    if (state.editing) return;
    if (window.crypto.randomUUID) {
      state.idempotencyKey = window.crypto.randomUUID();
      return;
    }

    const bytes = window.crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
    state.idempotencyKey = [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16),
      hex.slice(16, 20), hex.slice(20)].join("-");
  }

  function createEl(tag, className, text) {
    const el = document.createElement(tag);
    if (className) {
      el.className = className;
    }
    if (text !== undefined) {
      el.textContent = text;
    }
    return el;
  }

  function formatLocalDateTimeInput(date, includeSeconds = false) {
    const pad = (value) => String(value).padStart(2, "0");
    const seconds = includeSeconds ? `:${pad(date.getSeconds())}` : "";
    return [
      date.getFullYear(),
      pad(date.getMonth() + 1),
      pad(date.getDate()),
    ].join("-") + `T${pad(date.getHours())}:${pad(date.getMinutes())}${seconds}`;
  }

  function extractErrorMessage(error) {
    if (!error) {
      return "N\u00e3o foi poss\u00edvel registrar o lan\u00e7amento.";
    }
    if (typeof error === "string" && error.trim()) {
      return error.trim();
    }
    const candidate =
      error.data?.message ||
      error.body?.message ||
      error.payload?.message ||
      error.message;
    if (typeof candidate === "string" && candidate.trim()) {
      return candidate.trim();
    }
    return "N\u00e3o foi poss\u00edvel registrar o lan\u00e7amento. Tente novamente.";
  }

  function getApi() {
    const api = window.ValoraAPI;
    if (!api || typeof api.request !== "function" || typeof api.getPortfolio !== "function") {
      throw new Error("Servi\u00e7o de API indispon\u00edvel no momento.");
    }
    return api;
  }

  async function resolvePortfolioId() {
    const api = getApi();
    const portfolio = await api.getPortfolio();
    if (typeof portfolio === "string" && portfolio.trim()) {
      return portfolio.trim();
    }
    if (portfolio && typeof portfolio === "object") {
      const id = portfolio.id || portfolio.portfolioId;
      if (typeof id === "string" && id.trim()) {
        return id.trim();
      }
    }
    throw new Error("Nenhuma carteira ativa encontrada. Selecione ou crie uma carteira.");
  }

  function setStatus(message, tone = "error") {
    const { status } = state.elements;
    if (!message) {
      status.hidden = true;
      status.textContent = "";
      delete status.dataset.tone;
      return;
    }
    status.hidden = false;
    status.dataset.tone = tone;
    status.textContent = message;
  }

  function updateEstimatedTotal() {
    const { quantityInput, unitPriceInput, totalPreview } = state.elements;
    const quantity = Number.parseFloat(quantityInput.value);
    const unitPrice = Number.parseFloat(unitPriceInput.value);

    if (Number.isFinite(quantity) && quantity > 0 && Number.isFinite(unitPrice) && unitPrice > 0) {
      totalPreview.textContent = `Total da opera\u00e7\u00e3o: ${CURRENCY_FORMATTER.format(quantity * unitPrice)}`;
      return;
    }
    totalPreview.textContent = "Total da opera\u00e7\u00e3o: R$ 0,00";
  }

  function hideSuggestions() {
    const { suggestionsList, assetInput } = state.elements;
    state.suggestions = [];
    state.activeIndex = -1;
    suggestionsList.hidden = true;
    suggestionsList.replaceChildren();
    assetInput.setAttribute("aria-expanded", "false");
    assetInput.removeAttribute("aria-activedescendant");
  }

  function updateSelectedAssetView() {
    const { selectedAssetBox, selectedAssetText, assetInput } = state.elements;
    if (!state.selectedAsset) {
      selectedAssetBox.hidden = true;
      selectedAssetText.textContent = "";
      return;
    }
    const category = CATEGORY_LABELS[state.selectedAsset.category] || state.selectedAsset.category || "";
    const { ticker, name } = state.selectedAsset;
    let label = name ? `${ticker} \u2014 ${name}` : ticker;
    if (category) label += ` (${category})`;
    selectedAssetText.textContent = label;
    selectedAssetBox.hidden = false;
    assetInput.value = state.selectedAsset.ticker;
  }

  function selectAsset(asset) {
    state.selectionSeq += 1;
    renewIdempotencyKey();
    window.clearTimeout(state.searchTimer);
    state.searchSeq += 1;
    state.selectedAsset = asset;
    updateSelectedAssetView();
    hideSuggestions();
    setStatus("");
    state.elements.quantityInput.focus();
  }

  async function chooseAsset(asset) {
    state.selectionSeq += 1;
    if (asset.persisted === false || !asset.id) {
      const seq = ++state.selectionSeq;
      setStatus(`Importando ${asset.ticker} da B3...`, "info");
      try {
        const importedAsset = await getApi().request("/assets/import", {
          method: "POST",
          body: { ticker: asset.ticker },
        });
        if (seq !== state.selectionSeq || !state.elements || state.elements.modal.hidden) return;
        selectAsset(importedAsset);
        setStatus("Ativo importado da B3 com sucesso.", "success");
      } catch (error) {
        if (seq !== state.selectionSeq || !state.elements || state.elements.modal.hidden) return;
        setStatus(extractErrorMessage(error), "error");
      }
      return;
    }
    selectAsset(asset);
  }

  function clearSelectedAsset(clearInput = false) {
    state.selectionSeq += 1;
    state.selectedAsset = null;
    updateSelectedAssetView();
    if (clearInput) {
      renewIdempotencyKey();
      window.clearTimeout(state.searchTimer);
      state.searchSeq += 1;
      state.elements.assetInput.value = "";
      hideSuggestions();
      state.elements.assetInput.focus();
    }
  }

  function renderSuggestions(items, emptyMessage) {
    const { suggestionsList, assetInput } = state.elements;
    suggestionsList.replaceChildren();
    state.suggestions = Array.isArray(items) ? items : [];
    state.activeIndex = -1;

    if (state.suggestions.length === 0) {
      const emptyItem = createEl(
        "li",
        "valora-tx-suggestion-empty",
        emptyMessage || "Nenhum ativo encontrado."
      );
      suggestionsList.append(emptyItem);
      const query = assetInput.value.trim();
      if (query.length >= 2 && !emptyMessage) {
        const actions = createEl("li", "valora-tx-quick-actions");
        const importButton = createEl("button", "valora-tx-quick-action", `Importar "${query.toUpperCase()}" da B3`);
        importButton.type = "button";
        importButton.addEventListener("mousedown", (event) => event.preventDefault());
        importButton.addEventListener("click", () => {
          importButton.disabled = true;
          chooseAsset({ ticker: query.toUpperCase(), persisted: false }).finally(() => {
            if (importButton.isConnected) importButton.disabled = false;
          });
        });
        const fixedButton = createEl("button", "valora-tx-quick-action", "Cadastrar Renda Fixa");
        fixedButton.type = "button";
        fixedButton.addEventListener("mousedown", (event) => event.preventDefault());
        fixedButton.addEventListener("click", () => openFixedIncomeForm(query));
        actions.append(importButton, fixedButton);
        suggestionsList.append(actions);
      }
      suggestionsList.hidden = false;
      assetInput.setAttribute("aria-expanded", "true");
      return;
    }

    state.suggestions.forEach((asset, index) => {
      const item = createEl("li", "valora-tx-suggestion");
      item.id = `valora-tx-option-${index}`;
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", "false");

      const main = createEl("div", "valora-tx-suggestion__main");
      const ticker = createEl("span", "valora-tx-suggestion__ticker", asset.ticker || "");
      const name = createEl("span", "valora-tx-suggestion__name", asset.name || "");
      main.append(ticker, name);
      if (asset.persisted === false) main.append(createEl("span", "valora-tx-suggestion__badge", "B3 · Importar"));

      const categoryLabel = CATEGORY_LABELS[asset.category] || asset.category || "";
      item.append(main);
      if (categoryLabel) {
        item.append(createEl("span", "valora-tx-suggestion__category", categoryLabel));
      }

      item.addEventListener("mousedown", (event) => {
        event.preventDefault();
        chooseAsset(asset);
      });

      suggestionsList.append(item);
    });

    suggestionsList.hidden = false;
    assetInput.setAttribute("aria-expanded", "true");
  }

  function labeledControl(container, labelText, control) {
    const label = createEl("label", "valora-tx-fixed-field", labelText);
    label.append(control);
    container.append(label);
    return control;
  }

  function openFixedIncomeForm(query) {
    const { fixedIncomePanel, assetInput } = state.elements;
    fixedIncomePanel.replaceChildren();
    fixedIncomePanel.hidden = false;
    const heading = createEl("h3", "valora-tx-fixed-title", "Novo ativo de renda fixa");
    const form = createEl("form", "valora-tx-fixed-form");
    const control = (name, label, type = "text") => {
      const input = createEl("input", "valora-tx-input");
      input.name = name;
      input.type = type;
      input.required = !["issuer"].includes(name);
      if (name === "ticker") input.value = query.toUpperCase();
      return labeledControl(form, label, input);
    };
    const ticker = control("ticker", "Ticker");
    const name = control("name", "Nome");
    const indexer = document.createElement("select");
    indexer.className = "valora-tx-input";
    ["PRE", "CDI", "SELIC", "IPCA"].forEach((value) => { const option = createEl("option", "", value); option.value = value; indexer.append(option); });
    labeledControl(form, "Indexador", indexer);
    const rateType = document.createElement("select");
    rateType.className = "valora-tx-input";
    [["FIXED", "Taxa fixa"], ["PERCENTAGE_OF_INDEX", "% do indexador"], ["SPREAD", "Spread sobre o indexador"]].forEach(([value, label]) => { const option = createEl("option", "", label); option.value = value; rateType.append(option); });
    labeledControl(form, "Tipo de taxa", rateType);
    const annualRate = control("annualRate", "Taxa anual (%)", "number");
    annualRate.step = "any";
    const spreadRate = control("spreadRate", "Spread (%)", "number");
    spreadRate.step = "any";
    const issuer = control("issuer", "Emissor");
    const expirationDate = control("expirationDate", "Vencimento", "date");
    const updateRateFields = () => {
      annualRate.closest("label").hidden = rateType.value === "SPREAD";
      spreadRate.closest("label").hidden = rateType.value !== "SPREAD";
      annualRate.required = rateType.value !== "SPREAD";
      spreadRate.required = rateType.value === "SPREAD";
    };
    rateType.addEventListener("change", updateRateFields);
    updateRateFields();
    const actions = createEl("div", "valora-tx-fixed-actions");
    const cancel = createEl("button", "valora-tx-btn valora-tx-btn--ghost", "Cancelar");
    cancel.type = "button";
    cancel.addEventListener("click", () => { fixedIncomePanel.hidden = true; assetInput.focus(); });
    const save = createEl("button", "valora-tx-btn valora-tx-btn--primary", "Salvar ativo");
    save.type = "submit";
    actions.append(cancel, save);
    form.append(actions);
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      save.disabled = true;
      const payload = { ticker: ticker.value.trim().toUpperCase(), name: name.value.trim(), indexer: indexer.value, rateType: rateType.value, issuer: issuer.value.trim(), expirationDate: expirationDate.value };
      if (rateType.value === "SPREAD") payload.spreadRate = Number(spreadRate.value);
      else payload.annualRate = Number(annualRate.value);
      try {
        const created = await getApi().request("/assets/fixed-income", { method: "POST", body: payload });
        fixedIncomePanel.hidden = true;
        setStatus("Ativo de renda fixa cadastrado.", "success");
        selectAsset(created);
      } catch (error) { save.disabled = false; setStatus(extractErrorMessage(error), "error"); }
    });
    fixedIncomePanel.append(heading, form);
    state.suggestions = [];
    state.activeIndex = -1;
    state.elements.suggestionsList.hidden = true;
    assetInput.setAttribute("aria-expanded", "false");
    assetInput.removeAttribute("aria-activedescendant");
    ticker.focus();
  }

  function highlightSuggestion(nextIndex) {
    const { suggestionsList, assetInput } = state.elements;
    const options = suggestionsList.querySelectorAll("[role='option']");
    if (options.length === 0) {
      return;
    }

    const boundedIndex = ((nextIndex % options.length) + options.length) % options.length;
    state.activeIndex = boundedIndex;

    options.forEach((option, index) => {
      const selected = index === boundedIndex;
      option.setAttribute("aria-selected", selected ? "true" : "false");
      if (selected) {
        assetInput.setAttribute("aria-activedescendant", option.id);
        option.scrollIntoView({ block: "nearest" });
      }
    });
  }

  async function searchAssets(query) {
    const seq = ++state.searchSeq;
    try {
      const api = getApi();
      const results = await api.request(
        `/assets/search?ticker=${encodeURIComponent(query)}`,
        { method: "GET" }
      );
      if (seq !== state.searchSeq) {
        return;
      }
      renderSuggestions(Array.isArray(results) ? results : []);
    } catch (error) {
      if (seq !== state.searchSeq) {
        return;
      }
      renderSuggestions([], extractErrorMessage(error));
    }
  }

  function handleAssetInput() {
    state.selectionSeq += 1;
    const query = state.elements.assetInput.value.trim();
    if (state.selectedAsset && query.toUpperCase() !== String(state.selectedAsset.ticker).toUpperCase()) {
      clearSelectedAsset(false);
    }

    window.clearTimeout(state.searchTimer);
    state.searchSeq += 1;
    if (!query) {
      hideSuggestions();
      return;
    }

    state.searchTimer = window.setTimeout(() => {
      searchAssets(query);
    }, SEARCH_DEBOUNCE_MS);
  }

  function handleAssetKeydown(event) {
    const { suggestionsList } = state.elements;
    if (suggestionsList.hidden || state.suggestions.length === 0) {
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      highlightSuggestion(state.activeIndex + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      highlightSuggestion(state.activeIndex <= 0 ? state.suggestions.length - 1 : state.activeIndex - 1);
    } else if (event.key === "Enter" && state.activeIndex >= 0) {
      event.preventDefault();
      const chosen = state.suggestions[state.activeIndex];
      if (chosen) {
        chooseAsset(chosen);
      }
    } else if (event.key === "Escape") {
      event.stopPropagation();
      hideSuggestions();
    }
  }

  function setSubmitting(submitting) {
    state.isSubmitting = submitting;
    const { submitButton, cancelButton } = state.elements;
    submitButton.disabled = submitting;
    cancelButton.disabled = submitting;
    submitButton.textContent = submitting ? "Salvando..." : "Salvar lan\u00e7amento";
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (state.isSubmitting) {
      return;
    }

    const { form, quantityInput, unitPriceInput, dateInput } = state.elements;
    if (!state.selectedAsset?.id) {
      setStatus("Selecione um ativo v\u00e1lido na lista de busca.");
      state.elements.assetInput.focus();
      return;
    }

    const type = form.elements.type?.value || "BUY";
    const quantity = Number.parseFloat(quantityInput.value);
    const unitPrice = Number.parseFloat(unitPriceInput.value);
    const rawDate = dateInput.value;

    if (!Number.isFinite(quantity) || quantity <= 0) {
      setStatus("Informe uma quantidade positiva.");
      quantityInput.focus();
      return;
    }

    if (!Number.isFinite(unitPrice) || unitPrice <= 0) {
      setStatus("Informe um pre\u00e7o unit\u00e1rio positivo.");
      unitPriceInput.focus();
      return;
    }

    const editing = state.editing;
    const originalDate = editing && rawDate === state.originalDateInput && editing.transactionDate;
    const parsedDate = rawDate ? new Date(originalDate || rawDate) : null;
    if (!parsedDate || Number.isNaN(parsedDate.getTime())) {
      setStatus("Informe uma data e hora v\u00e1lida para a opera\u00e7\u00e3o.");
      dateInput.focus();
      return;
    }

    if (parsedDate.getTime() > Date.now()) {
      setStatus("A data da transa\u00e7\u00e3o n\u00e3o pode ser futura.");
      dateInput.focus();
      return;
    }

    const assetId = state.selectedAsset.id;
    const idempotencyKey = state.idempotencyKey;
    setStatus("");
    setSubmitting(true);

    try {
      const api = getApi();
      const portfolioId = editing?.portfolioId || await resolvePortfolioId();
      const path = editing ? `/transactions/${encodeURIComponent(editing.transactionId)}` : "/transactions";
      const saved = await api.request(path, {
        method: editing ? "PUT" : "POST",
        headers: editing ? undefined : { "Idempotency-Key": idempotencyKey },
        body: {
          portfolioId,
          assetId,
          type,
          quantity,
          unitPrice,
          transactionDate: originalDate || parsedDate.toISOString(),
        },
      });

      setSubmitting(false);
      closeModal();
      const events = ["valora:transaction-saved", "valora:transaction-created"];
      if (editing) events.push("valora:transaction-updated");
      events.forEach((name) => document.dispatchEvent(
        new CustomEvent(name, {
          bubbles: true,
          detail: { transaction: saved, portfolioId },
        })
      ));
    } catch (error) {
      setStatus(extractErrorMessage(error), "error");
    } finally {
      setSubmitting(false);
    }
  }

  function buildTypeOption(value, label, checked) {
    const wrapper = createEl("label", "valora-tx-type-option");
    const radio = document.createElement("input");
    radio.type = "radio";
    radio.name = "type";
    radio.value = value;
    radio.checked = checked;

    const pill = createEl("span", "valora-tx-type-pill", label);
    pill.dataset.type = value;

    wrapper.append(radio, pill);
    return wrapper;
  }

  function ensureModal() {
    if (state.elements) {
      return state.elements;
    }

    const modal = createEl("div", "valora-tx-modal");
    modal.hidden = true;
    modal.setAttribute("role", "dialog");
    modal.setAttribute("aria-modal", "true");
    modal.setAttribute("aria-labelledby", "valora-tx-title");

    const dialog = createEl("div", "valora-tx-dialog");

    const header = createEl("div", "valora-tx-header");
    const headerText = createEl("div");
    const title = createEl("h2", "valora-tx-title", "Novo lan\u00e7amento");
    title.id = "valora-tx-title";
    const subtitle = createEl(
      "p",
      "valora-tx-subtitle",
      "Registre uma opera\u00e7\u00e3o de compra ou venda na carteira ativa."
    );
    headerText.append(title, subtitle);

    const closeButton = createEl("button", "valora-tx-close", "\u00d7");
    closeButton.type = "button";
    closeButton.setAttribute("aria-label", "Fechar modal");
    header.append(headerText, closeButton);

    const form = createEl("form", "valora-tx-form");
    form.noValidate = true;

    const assetField = createEl("div", "valora-tx-field");
    const assetLabel = createEl("label", "valora-tx-label", "Ativo");
    assetLabel.htmlFor = "valora-tx-asset-input";

    const autocomplete = createEl("div", "valora-tx-autocomplete");
    const assetInput = createEl("input", "valora-tx-input");
    assetInput.id = "valora-tx-asset-input";
    assetInput.type = "text";
    assetInput.autocomplete = "off";
    assetInput.placeholder = "Digite o ticker (ex: PETR4, MXRF11, BTC)";
    assetInput.setAttribute("role", "combobox");
    assetInput.setAttribute("aria-autocomplete", "list");
    assetInput.setAttribute("aria-expanded", "false");
    assetInput.setAttribute("aria-controls", "valora-tx-suggestions");

    const suggestionsList = createEl("ul", "valora-tx-suggestions");
    suggestionsList.id = "valora-tx-suggestions";
    suggestionsList.setAttribute("role", "listbox");
    suggestionsList.hidden = true;

    const fixedIncomePanel = createEl("div", "valora-tx-fixed-panel");
    fixedIncomePanel.hidden = true;

    autocomplete.append(assetInput, suggestionsList, fixedIncomePanel);

    const selectedAssetBox = createEl("div", "valora-tx-selected-asset");
    selectedAssetBox.hidden = true;
    const selectedAssetText = createEl("span", "valora-tx-selected-asset__text");
    const clearAssetButton = createEl("button", "valora-tx-selected-asset__clear", "Trocar");
    clearAssetButton.type = "button";
    selectedAssetBox.append(selectedAssetText, clearAssetButton);

    assetField.append(assetLabel, autocomplete, selectedAssetBox);

    const typeField = createEl("div", "valora-tx-field");
    const typeLabel = createEl("span", "valora-tx-label", "Tipo de opera\u00e7\u00e3o");
    const typeGroup = createEl("div", "valora-tx-type-group");
    typeGroup.append(
      buildTypeOption("BUY", "Compra (BUY)", true),
      buildTypeOption("SELL", "Venda (SELL)", false)
    );
    typeField.append(typeLabel, typeGroup);

    const numbersRow = createEl("div", "valora-tx-row");

    const quantityField = createEl("div", "valora-tx-field");
    const quantityLabel = createEl("label", "valora-tx-label", "Quantidade");
    quantityLabel.htmlFor = "valora-tx-quantity";
    const quantityInput = createEl("input", "valora-tx-input");
    quantityInput.id = "valora-tx-quantity";
    quantityInput.name = "quantity";
    quantityInput.type = "number";
    quantityInput.step = "any";
    quantityInput.min = "0.00000001";
    quantityInput.placeholder = "Ex: 10";
    quantityInput.required = true;
    quantityField.append(quantityLabel, quantityInput);

    const priceField = createEl("div", "valora-tx-field");
    const priceLabel = createEl("label", "valora-tx-label", "Pre\u00e7o unit\u00e1rio (R$)");
    priceLabel.htmlFor = "valora-tx-unit-price";
    const unitPriceInput = createEl("input", "valora-tx-input");
    unitPriceInput.id = "valora-tx-unit-price";
    unitPriceInput.name = "unitPrice";
    unitPriceInput.type = "number";
    unitPriceInput.step = "any";
    unitPriceInput.min = "0.00000001";
    unitPriceInput.placeholder = "Ex: 34,50";
    unitPriceInput.required = true;
    priceField.append(priceLabel, unitPriceInput);

    numbersRow.append(quantityField, priceField);

    const totalPreview = createEl("p", "valora-tx-total", "Total da opera\u00e7\u00e3o: R$ 0,00");

    const dateField = createEl("div", "valora-tx-field");
    const dateLabel = createEl("label", "valora-tx-label", "Data e hora da opera\u00e7\u00e3o");
    dateLabel.htmlFor = "valora-tx-date";
    const dateInput = createEl("input", "valora-tx-input");
    dateInput.id = "valora-tx-date";
    dateInput.name = "transactionDate";
    dateInput.type = "datetime-local";
    dateInput.required = true;
    dateField.append(dateLabel, dateInput);

    const status = createEl("p", "valora-tx-status");
    status.hidden = true;
    status.setAttribute("role", "alert");

    const actions = createEl("div", "valora-tx-actions");
    const cancelButton = createEl("button", "valora-tx-btn valora-tx-btn--ghost", "Cancelar");
    cancelButton.type = "button";
    const submitButton = createEl("button", "valora-tx-btn valora-tx-btn--primary", "Salvar lan\u00e7amento");
    submitButton.type = "submit";
    actions.append(cancelButton, submitButton);

    form.append(
      assetField,
      typeField,
      numbersRow,
      totalPreview,
      dateField,
      status,
      actions
    );

    dialog.append(header, form);
    modal.append(dialog);
    document.body.append(modal);

    closeButton.addEventListener("click", closeModal);
    cancelButton.addEventListener("click", closeModal);
    modal.addEventListener("click", (event) => {
      if (event.target === modal) {
        closeModal();
      }
    });

    clearAssetButton.addEventListener("click", () => clearSelectedAsset(true));
    assetInput.addEventListener("input", handleAssetInput);
    assetInput.addEventListener("keydown", handleAssetKeydown);
    assetInput.addEventListener("blur", () => {
      window.setTimeout(hideSuggestions, 120);
    });

    quantityInput.addEventListener("input", updateEstimatedTotal);
    unitPriceInput.addEventListener("input", updateEstimatedTotal);
    form.addEventListener("input", (event) => {
      if (!event.target.closest(".valora-tx-fixed-panel")) renewIdempotencyKey();
    });
    form.addEventListener("submit", handleSubmit);

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && state.elements && !state.elements.modal.hidden) {
        closeModal();
      }
    });

    state.elements = {
      modal,
      dialog,
      form,
      title,
      subtitle,
      assetInput,
      suggestionsList,
      fixedIncomePanel,
      selectedAssetBox,
      selectedAssetText,
      quantityInput,
      unitPriceInput,
      dateInput,
      totalPreview,
      status,
      cancelButton,
      submitButton,
    };

    return state.elements;
  }

  function resetForm() {
    const { form, dateInput } = state.elements;
    form.reset();
    state.selectedAsset = null;
    updateSelectedAssetView();
    hideSuggestions();
    setStatus("");
    const nowLocal = formatLocalDateTimeInput(new Date(), Boolean(state.editing));
    dateInput.step = state.editing ? "1" : "60";
    dateInput.max = nowLocal;
    dateInput.value = nowLocal;
    updateEstimatedTotal();
  }

  function openModal(trigger, transaction = null) {
    state.selectionSeq += 1;
    if (state.isSubmitting) return;
    const elements = ensureModal();
    state.lastTrigger = trigger instanceof HTMLElement ? trigger : document.activeElement;
    state.editing = transaction;
    resetForm();
    renewIdempotencyKey();
    elements.title.textContent = transaction ? "Editar lan\u00e7amento" : "Novo lan\u00e7amento";
    elements.subtitle.hidden = Boolean(transaction);
    if (transaction) {
      state.selectedAsset = { id: transaction.assetId, ticker: transaction.ticker };
      updateSelectedAssetView();
      elements.form.elements.type.value = transaction.type;
      elements.quantityInput.value = transaction.quantity;
      elements.unitPriceInput.value = transaction.unitPrice;
      const date = new Date(transaction.transactionDate);
      elements.dateInput.value = formatLocalDateTimeInput(date, true);
      state.originalDateInput = elements.dateInput.value;
      updateEstimatedTotal();
    }
    elements.modal.hidden = false;
    (transaction ? elements.quantityInput : elements.assetInput).focus();
  }

  function closeModal() {
    state.selectionSeq += 1;
    if (state.isSubmitting || !state.elements || state.elements.modal.hidden) {
      return;
    }
    window.clearTimeout(state.searchTimer);
    state.searchSeq += 1;
    hideSuggestions();
    state.elements.modal.hidden = true;
    state.editing = null;
    state.idempotencyKey = null;
    if (state.lastTrigger instanceof HTMLElement && state.lastTrigger.isConnected) {
      state.lastTrigger.focus();
    }
    state.lastTrigger = null;
  }

  document.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof Element)) {
      return;
    }
    const trigger = target.closest("[data-new-transaction]");
    if (!trigger) {
      return;
    }
    event.preventDefault();
    openModal(trigger);
  });

  window.ValoraTransactionUI = {
    open: openModal,
    openEdit: (transaction) => openModal(document.activeElement, transaction),
    close: closeModal,
  };
})();
