(function () {
  const placeholder = document.querySelector("#portfolio-name");
  if (!placeholder) return;

  const api = window.ValoraAPI;
  let portfolios = [];
  let active = null;
  let refreshId = 0;
  let creating = false;
  let saving = false;

  function createElement(tag, className, text) {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function button(label, style = "ghost") {
    const element = createElement("button", `button button--${style}`, label);
    element.type = "button";
    return element;
  }

  function field(labelText, control, maxLength) {
    const label = createElement("label", "portfolio-field", labelText);
    control.className = "portfolio-control";
    control.maxLength = maxLength;
    label.append(control);
    return label;
  }

  const toolbar = createElement("div", "portfolio-toolbar");
  const select = createElement("select", "portfolio-control portfolio-select");
  select.id = "portfolio-name";
  select.setAttribute("aria-label", "Carteira ativa");
  select.disabled = true;
  select.add(new Option("Carregando carteiras…", ""));
  const manage = button("Gerenciar carteiras");
  manage.setAttribute("aria-haspopup", "dialog");
  const toolbarError = createElement("p", "portfolio-error");
  toolbarError.setAttribute("role", "alert");
  toolbar.append(select, manage, toolbarError);
  placeholder.replaceWith(toolbar);

  const dialog = createElement("dialog", "portfolio-dialog");
  dialog.setAttribute("aria-labelledby", "portfolio-dialog-title");
  const title = createElement("h2", "", "Gerenciar carteiras");
  title.id = "portfolio-dialog-title";
  const form = createElement("form", "portfolio-form");
  const name = document.createElement("input");
  name.required = true;
  const description = document.createElement("textarea");
  description.rows = 3;
  const errorMessage = createElement("p", "portfolio-error");
  errorMessage.setAttribute("role", "alert");
  const actions = createElement("div", "portfolio-actions");
  const close = button("Fechar");
  const toggleMode = button("Nova carteira");
  const remove = button("Excluir carteira");
  remove.classList.add("portfolio-delete");
  const save = button("Salvar alterações", "primary");
  save.type = "submit";
  actions.append(close, toggleMode, remove, save);
  form.append(field("Nome", name, 100), field("Descrição", description, 255), errorMessage, actions);
  dialog.append(title, form);
  document.body.append(dialog);

  function renderOptions() {
    const options = portfolios.map((portfolio) => new Option(portfolio.name, portfolio.id));
    select.replaceChildren(...(options.length ? options : [new Option("Nenhuma carteira", "")]));
    select.value = active?.id ?? "";
    select.disabled = options.length === 0;
  }

  async function refreshPortfolios(event) {
    const currentRefresh = ++refreshId;
    toolbarError.textContent = "";
    if (event) active = event.detail.portfolio;
    select.disabled = true;
    try {
      const [items, selected] = await Promise.all([
        api.listPortfolios(),
        event ? active : api.getPortfolio(),
      ]);
      if (currentRefresh !== refreshId) return;
      portfolios = items;
      active = selected;
      renderOptions();
    } catch (error) {
      if (currentRefresh === refreshId) {
        select.replaceChildren(new Option(active?.name ?? "Carteiras indisponíveis", active?.id ?? ""));
        toolbarError.textContent = error.message;
      }
    }
  }

  function renderForm(create) {
    creating = create;
    name.value = create ? "" : active.name;
    description.value = create ? "" : active.description ?? "";
    toggleMode.hidden = !active;
    toggleMode.textContent = create ? "Voltar à carteira ativa" : "Nova carteira";
    remove.hidden = create;
    save.textContent = create ? "Criar carteira" : "Salvar alterações";
    errorMessage.textContent = "";
  }

  async function mutate(operation) {
    if (saving) return;
    saving = true;
    Array.from(form.elements).forEach((control) => { control.disabled = true; });
    errorMessage.textContent = "";
    try {
      await operation();
      dialog.close();
    } catch (error) {
      errorMessage.textContent = error.message;
    } finally {
      saving = false;
      Array.from(form.elements).forEach((control) => { control.disabled = false; });
    }
  }

  select.addEventListener("change", () => {
    const selected = portfolios.find((portfolio) => portfolio.id === select.value);
    if (selected) api.setActivePortfolio(selected);
  });
  manage.addEventListener("click", async () => {
    if (!active || toolbarError.textContent) {
      manage.disabled = true;
      await refreshPortfolios();
      manage.disabled = false;
      if (toolbarError.textContent) return;
    }
    if (dialog.open) return;
    renderForm(!active);
    dialog.showModal();
    name.focus();
  });
  close.addEventListener("click", () => dialog.close());
  dialog.addEventListener("cancel", (event) => {
    if (saving) event.preventDefault();
  });
  toggleMode.addEventListener("click", () => {
    renderForm(!creating);
    name.focus();
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const payload = { name: name.value.trim(), description: description.value.trim() };
    if (!payload.name) {
      errorMessage.textContent = "Informe o nome da carteira.";
      name.focus();
      return;
    }
    mutate(() => creating ? api.createPortfolio(payload) : api.updatePortfolio(active.id, payload));
  });
  remove.addEventListener("click", () => {
    const { id, name: portfolioName } = active;
    if (window.confirm(`Excluir a carteira “${portfolioName}”?`)) {
      mutate(() => api.deletePortfolio(id));
    }
  });

  window.addEventListener("valora:portfolio-changed", refreshPortfolios);
  refreshPortfolios();
})();
