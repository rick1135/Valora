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
    isSubmitting: false,
    lastTrigger: null,
  };

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

  function formatLocalDateTimeInput(date) {
    const pad = (value) => String(value).padStart(2, "0");
    return [
      date.getFullYear(),
      pad(date.getMonth() + 1),
      pad(date.getDate()),
    ].join("-") + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
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
    const label = category
      ? `${state.selectedAsset.ticker} \u2014 ${state.selectedAsset.name} (${category})`
      : `${state.selectedAsset.ticker} \u2014 ${state.selectedAsset.name}`;
    selectedAssetText.textContent = label;
    selectedAssetBox.hidden = false;
    assetInput.value = state.selectedAsset.ticker;
  }

  function selectAsset(asset) {
    state.selectedAsset = asset;
    updateSelectedAssetView();
    hideSuggestions();
    setStatus("");
    state.elements.quantityInput.focus();
  }

  function clearSelectedAsset(clearInput = false) {
    state.selectedAsset = null;
    updateSelectedAssetView();
    if (clearInput) {
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

      const categoryLabel = CATEGORY_LABELS[asset.category] || asset.category || "";
      item.append(main);
      if (categoryLabel) {
        item.append(createEl("span", "valora-tx-suggestion__category", categoryLabel));
      }

      item.addEventListener("mousedown", (event) => {
        event.preventDefault();
        selectAsset(asset);
      });

      suggestionsList.append(item);
    });

    suggestionsList.hidden = false;
    assetInput.setAttribute("aria-expanded", "true");
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
    const query = state.elements.assetInput.value.trim();
    if (state.selectedAsset && query.toUpperCase() !== String(state.selectedAsset.ticker).toUpperCase()) {
      clearSelectedAsset(false);
    }

    window.clearTimeout(state.searchTimer);
    if (!query) {
      state.searchSeq += 1;
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
        selectAsset(chosen);
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

    const parsedDate = rawDate ? new Date(rawDate) : null;
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

    setStatus("");
    setSubmitting(true);

    try {
      const api = getApi();
      const portfolioId = await resolvePortfolioId();
      const transactionDate = parsedDate.toISOString();

      const saved = await api.request("/transactions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          portfolioId,
          assetId: state.selectedAsset.id,
          type,
          quantity,
          unitPrice,
          transactionDate,
        }),
      });

      closeModal();
      document.dispatchEvent(
        new CustomEvent("valora:transaction-saved", {
          bubbles: true,
          detail: {
            transaction: saved,
            portfolioId,
          },
        })
      );
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

    autocomplete.append(assetInput, suggestionsList);

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
      assetInput,
      suggestionsList,
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
    const nowLocal = formatLocalDateTimeInput(new Date());
    dateInput.max = nowLocal;
    dateInput.value = nowLocal;
    updateEstimatedTotal();
  }

  function openModal(trigger) {
    const elements = ensureModal();
    state.lastTrigger = trigger instanceof HTMLElement ? trigger : document.activeElement;
    resetForm();
    elements.modal.hidden = false;
    elements.assetInput.focus();
  }

  function closeModal() {
    if (!state.elements || state.elements.modal.hidden) {
      return;
    }
    window.clearTimeout(state.searchTimer);
    state.searchSeq += 1;
    hideSuggestions();
    state.elements.modal.hidden = true;
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
    close: closeModal,
  };
})();
