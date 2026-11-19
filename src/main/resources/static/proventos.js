(() => {
  const API_PAGE_SIZE = 100;
  const TABLE_PAGE_SIZE = 20;

  const PROVENT_TYPE_LABELS = {
    DIVIDEND: "Dividendo",
    JCP: "JSCP",
  };

  const PROVENT_STATUS_META = {
    PAID: { label: "Recebido", className: "provents-status--received" },
    PENDING: { label: "A receber", className: "provents-status--pending" },
  };

  const currencyFormatter = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  const unitCurrencyFormatter = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: 2,
    maximumFractionDigits: 6,
  });

  const quantityFormatter = new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 8,
  });

  const percentFormatter = new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  const state = {
    provisions: [],
    currentPage: 0,
    isLoading: false,
  };

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

  function toNumber(value) {
    if (value === null || value === undefined || value === "") {
      return 0;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function formatCurrency(value) {
    return currencyFormatter.format(toNumber(value));
  }

  function formatUnitCurrency(value) {
    return unitCurrencyFormatter.format(toNumber(value));
  }

  function formatQuantity(value) {
    return quantityFormatter.format(toNumber(value));
  }

  function formatPercent(value) {
    return `${percentFormatter.format(toNumber(value))}%`;
  }

  function parseIsoDate(dateStr) {
    if (typeof dateStr !== "string") {
      return null;
    }
    const match = dateStr.trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!match) {
      return null;
    }
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) {
      return null;
    }
    return { year, month, day };
  }

  function formatDateBr(dateStr) {
    const parsed = parseIsoDate(dateStr);
    if (!parsed) {
      return "-";
    }
    const dd = String(parsed.day).padStart(2, "0");
    const mm = String(parsed.month).padStart(2, "0");
    return `${dd}/${mm}/${parsed.year}`;
  }

  function getProventTypeLabel(type) {
    return PROVENT_TYPE_LABELS[type] || String(type || "-");
  }

  function getProventStatusMeta(status) {
    return (
      PROVENT_STATUS_META[status] || {
        label: String(status || "-"),
        className: "",
      }
    );
  }

  async function apiRequest(path, options) {
    const response = await window.ValoraAPI.request(path, options);
    if (response && typeof response.json === "function" && "ok" in response) {
      if (!response.ok) {
        throw new Error(`Erro na requisi\u00e7\u00e3o (${response.status})`);
      }
      return response.json();
    }
    return response;
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

    const portfolioLabel = document.querySelector("#portfolio-name");
    if (portfolioLabel) {
      portfolioLabel.textContent = portfolioName;
    }

    return { id: portfolioId, name: portfolioName };
  }

  async function fetchAllProvents(portfolioId) {
    const basePath = `/provents/me?portfolioId=${encodeURIComponent(portfolioId)}`;
    const firstPage = await apiRequest(`${basePath}&page=0&size=${API_PAGE_SIZE}`);
    const items = Array.isArray(firstPage?.content) ? [...firstPage.content] : [];
    const totalPages = Number.isInteger(firstPage?.totalPages) ? firstPage.totalPages : 1;

    if (totalPages > 1) {
      const remainingRequests = [];
      for (let page = 1; page < totalPages; page += 1) {
        remainingRequests.push(apiRequest(`${basePath}&page=${page}&size=${API_PAGE_SIZE}`));
      }
      const remainingPages = await Promise.all(remainingRequests);
      for (const pageData of remainingPages) {
        if (Array.isArray(pageData?.content)) {
          items.push(...pageData.content);
        }
      }
    }

    return {
      items,
      totalElements: Number.isFinite(firstPage?.totalElements) ? firstPage.totalElements : items.length,
    };
  }

  function setLoading(isLoading) {
    state.isLoading = isLoading;
    const banner = document.querySelector("#provents-loading");
    if (banner) {
      banner.hidden = !isLoading;
    }
    const main = document.querySelector(".dashboard");
    if (main) {
      main.setAttribute("aria-busy", String(Boolean(isLoading)));
    }
  }

  function showError(message) {
    const banner = document.querySelector("#provents-error");
    if (!banner) {
      return;
    }
    if (!message) {
      banner.hidden = true;
      banner.replaceChildren();
      return;
    }

    const textEl = createEl("p", "", message);
    textEl.style.margin = "0";

    const actionsEl = createEl("div", "provents-error-actions");
    const retryBtn = createEl("button", "button button--ghost", "Tentar novamente");
    retryBtn.type = "button";
    retryBtn.addEventListener("click", () => {
      loadProvents();
    });
    actionsEl.append(retryBtn);

    banner.replaceChildren(textEl, actionsEl);
    banner.hidden = false;
  }

  function setContentVisibility({ hasProvisions, isError }) {
    const kpis = document.querySelector("#provents-kpis");
    const breakdown = document.querySelector("#provents-breakdown");
    const historyPanel = document.querySelector("#monthly-history-panel");
    const detailPanel = document.querySelector("#my-provents-panel");
    const emptyState = document.querySelector("#provents-empty");

    if (isError) {
      if (kpis) kpis.hidden = true;
      if (breakdown) breakdown.hidden = true;
      if (historyPanel) historyPanel.hidden = true;
      if (detailPanel) detailPanel.hidden = true;
      if (emptyState) emptyState.hidden = true;
      return;
    }

    if (kpis) kpis.hidden = false;
    if (breakdown) breakdown.hidden = !hasProvisions;
    if (historyPanel) historyPanel.hidden = !hasProvisions;
    if (detailPanel) detailPanel.hidden = !hasProvisions;
    if (emptyState) emptyState.hidden = hasProvisions;
  }

  function computeMetrics(provisions) {
    let paidNet = 0;
    let paidCount = 0;
    let pendingNet = 0;
    let pendingCount = 0;
    let totalNet = 0;
    let last12MonthsNet = 0;

    const now = new Date();
    const cutoff = new Date(now.getFullYear(), now.getMonth() - 11, 1);
    const cutoffYmd =
      cutoff.getFullYear() * 10000 + (cutoff.getMonth() + 1) * 100 + cutoff.getDate();
    const todayYmd =
      now.getFullYear() * 10000 + (now.getMonth() + 1) * 100 + now.getDate();

    for (const item of provisions) {
      const net = toNumber(item.netAmount);
      totalNet += net;

      if (item.status === "PAID") {
        paidNet += net;
        paidCount += 1;

        const refDate = parseIsoDate(item.paymentDate || item.comDate);
        if (refDate) {
          const refYmd = refDate.year * 10000 + refDate.month * 100 + refDate.day;
          if (refYmd >= cutoffYmd && refYmd <= todayYmd) {
            last12MonthsNet += net;
          }
        }
      } else if (item.status === "PENDING") {
        pendingNet += net;
        pendingCount += 1;
      }
    }

    return {
      totalNet,
      paidNet,
      paidCount,
      pendingNet,
      pendingCount,
      last12MonthsNet,
      last12MonthsAvg: last12MonthsNet / 12,
      totalCount: provisions.length,
    };
  }

  function createKpiCard(label, value, meta, valueClass) {
    const card = createEl("article", "panel provents-kpi-card");
    const labelEl = createEl("p", "provents-kpi-card__label", label);
    const valueEl = createEl(
      "strong",
      `provents-kpi-card__value${valueClass ? ` ${valueClass}` : ""}`,
      value
    );
    const metaEl = createEl("p", "provents-kpi-card__meta", meta);
    card.append(labelEl, valueEl, metaEl);
    return card;
  }

  function renderKpis(metrics) {
    const container = document.querySelector("#provents-kpis");
    if (!container) {
      return;
    }

    const cards = [
      createKpiCard(
        "Total provisionado (l\u00edquido)",
        formatCurrency(metrics.totalNet),
        `${metrics.totalCount} ${metrics.totalCount === 1 ? "lan\u00e7amento" : "lan\u00e7amentos"} (${metrics.paidCount} ${metrics.paidCount === 1 ? "recebido" : "recebidos"} \u2022 ${metrics.pendingCount} a receber)`,
        ""
      ),
      createKpiCard(
        "Recebidos (l\u00edquido)",
        formatCurrency(metrics.paidNet),
        `${metrics.paidCount} ${metrics.paidCount === 1 ? "pagamento recebido" : "pagamentos recebidos"}`,
        "provents-kpi-card__value--positive"
      ),
      createKpiCard(
        "A receber (l\u00edquido)",
        formatCurrency(metrics.pendingNet),
        `${metrics.pendingCount} ${metrics.pendingCount === 1 ? "pagamento pendente" : "pagamentos pendentes"}`,
        "provents-kpi-card__value--pending"
      ),
      createKpiCard(
        "\u00daltimos 12 meses",
        formatCurrency(metrics.last12MonthsNet),
        `Recebidos no per\u00edodo \u2022 M\u00e9dia: ${formatCurrency(metrics.last12MonthsAvg)}/m\u00eas`,
        ""
      ),
    ];

    container.replaceChildren(...cards);
  }

  function renderAssetBreakdown(provisions) {
    const list = document.querySelector("#provents-by-asset");
    const empty = document.querySelector("#provents-by-asset-empty");
    if (!list || !empty) {
      return;
    }

    const byTicker = new Map();
    let grandTotal = 0;

    for (const item of provisions) {
      const ticker = item.ticker || "-";
      const net = toNumber(item.netAmount);
      grandTotal += net;

      const entry = byTicker.get(ticker) || { ticker, net: 0, count: 0 };
      entry.net += net;
      entry.count += 1;
      byTicker.set(ticker, entry);
    }

    const sorted = Array.from(byTicker.values()).sort((a, b) => b.net - a.net);

    if (sorted.length === 0) {
      list.replaceChildren();
      empty.hidden = false;
      return;
    }

    empty.hidden = true;
    const rows = sorted.map((entry) => {
      const share = grandTotal > 0 ? (entry.net / grandTotal) * 100 : 0;

      const li = createEl("li", "provents-asset-item");
      const row = createEl("div", "provents-asset-item__row");
      const tickerEl = createEl("span", "provents-asset-item__ticker", entry.ticker);
      const valuesEl = createEl(
        "span",
        "provents-asset-item__values",
        `${formatCurrency(entry.net)} (${formatPercent(share)})`
      );
      row.append(tickerEl, valuesEl);

      const bar = createEl("div", "provents-asset-bar");
      bar.setAttribute("aria-hidden", "true");
      const fill = createEl("span", "provents-asset-bar__fill");
      fill.style.width = `${Math.max(Math.min(share, 100), 2)}%`;
      bar.append(fill);

      li.append(row, bar);
      return li;
    });

    list.replaceChildren(...rows);
  }

  function renderTypeBreakdown(provisions) {
    const list = document.querySelector("#provents-by-type");
    const empty = document.querySelector("#provents-by-type-empty");
    if (!list || !empty) {
      return;
    }

    const byType = new Map();
    for (const item of provisions) {
      const typeKey = item.type || "OUTROS";
      const entry = byType.get(typeKey) || {
        type: typeKey,
        gross: 0,
        tax: 0,
        net: 0,
        count: 0,
      };
      entry.gross += toNumber(item.grossAmount);
      entry.tax += toNumber(item.withholdingTaxAmount);
      entry.net += toNumber(item.netAmount);
      entry.count += 1;
      byType.set(typeKey, entry);
    }

    const sorted = Array.from(byType.values()).sort((a, b) => b.net - a.net);
    if (sorted.length === 0) {
      list.replaceChildren();
      empty.hidden = false;
      return;
    }

    empty.hidden = true;
    const rows = sorted.map((entry) => {
      const li = createEl("li", "provents-type-item");
      const left = createEl("div");
      const nameEl = createEl("span", "provents-type-item__name", getProventTypeLabel(entry.type));
      const metaEl = createEl(
        "span",
        "provents-type-item__meta",
        `${entry.count} ${entry.count === 1 ? "evento" : "eventos"} \u2022 Bruto: ${formatCurrency(entry.gross)} \u2022 IR: ${formatCurrency(entry.tax)}`
      );
      left.append(nameEl, metaEl);

      const amountEl = createEl("strong", "provents-type-item__amount", formatCurrency(entry.net));
      li.append(left, amountEl);
      return li;
    });

    list.replaceChildren(...rows);
  }

  function createHistoryCell(total, paidTotal, pendingTotal, hasPaid, hasPending) {
    const td = createEl("td", "provents-history-cell");
    const valueEl = createEl("span", "provents-history-cell__value", formatCurrency(total));
    td.append(valueEl);

    if (hasPaid && hasPending) {
      td.classList.add("provents-history-cell--mixed");
      td.title = `Recebido: ${formatCurrency(paidTotal)} | A receber: ${formatCurrency(pendingTotal)}`;
      td.append(createEl("span", "provents-history-cell__tag", "Misto"));
    } else if (hasPending) {
      td.classList.add("provents-history-cell--pending");
      td.title = `A receber: ${formatCurrency(pendingTotal)}`;
      td.append(createEl("span", "provents-history-cell__tag", "A receber"));
    } else if (hasPaid) {
      td.classList.add("provents-history-cell--received");
      td.title = `Recebido: ${formatCurrency(paidTotal)}`;
      td.append(createEl("span", "provents-history-cell__tag", "Recebido"));
    }

    return td;
  }

  function renderMonthlyHistory(provisions) {
    const tbody = document.querySelector("#provents-history");
    if (!tbody) {
      return;
    }

    const byYear = new Map();
    for (const item of provisions) {
      const parsed = parseIsoDate(item.paymentDate || item.comDate);
      if (!parsed) {
        continue;
      }
      const year = parsed.year;
      const monthIndex = parsed.month - 1;
      if (monthIndex < 0 || monthIndex > 11) {
        continue;
      }

      let months = byYear.get(year);
      if (!months) {
        months = Array.from({ length: 12 }, () => ({
          total: 0,
          paidTotal: 0,
          pendingTotal: 0,
          hasPaid: false,
          hasPending: false,
          hasEntry: false,
        }));
        byYear.set(year, months);
      }

      const net = toNumber(item.netAmount);
      const cell = months[monthIndex];
      cell.total += net;
      cell.hasEntry = true;
      if (item.status === "PAID") {
        cell.paidTotal += net;
        cell.hasPaid = true;
      } else if (item.status === "PENDING") {
        cell.pendingTotal += net;
        cell.hasPending = true;
      }
    }

    const years = Array.from(byYear.keys()).sort((a, b) => b - a);
    const rows = years.map((year) => {
      const months = byYear.get(year);
      const tr = createEl("tr");
      const yearTh = createEl("th", "", String(year));
      yearTh.scope = "row";
      tr.append(yearTh);

      let yearTotal = 0;
      let yearPaid = 0;
      let yearPending = 0;
      let yearHasPaid = false;
      let yearHasPending = false;
      let activeMonths = 0;

      for (const monthData of months) {
        if (monthData.hasEntry) {
          yearTotal += monthData.total;
          yearPaid += monthData.paidTotal;
          yearPending += monthData.pendingTotal;
          yearHasPaid = yearHasPaid || monthData.hasPaid;
          yearHasPending = yearHasPending || monthData.hasPending;
          activeMonths += 1;
          tr.append(
            createHistoryCell(
              monthData.total,
              monthData.paidTotal,
              monthData.pendingTotal,
              monthData.hasPaid,
              monthData.hasPending
            )
          );
        } else {
          tr.append(createEl("td", "return-muted", "-"));
        }
      }

      const avg = activeMonths > 0 ? yearTotal / activeMonths : 0;
      tr.append(createEl("td", "", formatCurrency(avg)));
      tr.append(
        createHistoryCell(yearTotal, yearPaid, yearPending, yearHasPaid, yearHasPending)
      );
      return tr;
    });

    tbody.replaceChildren(...rows);
  }

  function renderProventsTablePage() {
    const tbody = document.querySelector("#provents-table");
    const countLabel = document.querySelector("#provents-count-label");
    const pagination = document.querySelector("#provents-pagination");
    const paginationInfo = document.querySelector("#provents-pagination-info");
    const prevBtn = document.querySelector("#provents-prev-btn");
    const nextBtn = document.querySelector("#provents-next-btn");

    if (!tbody) {
      return;
    }

    const totalItems = state.provisions.length;
    const totalPages = Math.max(1, Math.ceil(totalItems / TABLE_PAGE_SIZE));
    if (state.currentPage >= totalPages) {
      state.currentPage = totalPages - 1;
    }
    if (state.currentPage < 0) {
      state.currentPage = 0;
    }

    const start = state.currentPage * TABLE_PAGE_SIZE;
    const pageItems = state.provisions.slice(start, start + TABLE_PAGE_SIZE);

    if (countLabel) {
      const paidCount = state.provisions.filter((item) => item.status === "PAID").length;
      const pendingCount = state.provisions.filter((item) => item.status === "PENDING").length;
      countLabel.textContent = `${totalItems} ${totalItems === 1 ? "registro" : "registros"} (${paidCount} ${paidCount === 1 ? "recebido" : "recebidos"} \u2022 ${pendingCount} a receber)`;
    }

    const rows = pageItems.map((item) => {
      const tr = createEl("tr");

      const tickerTh = createEl("th", "asset-symbol", item.ticker || "-");
      tickerTh.scope = "row";

      const statusMeta = getProventStatusMeta(item.status);
      const statusTd = createEl("td");
      const statusBadge = createEl("span", `provents-status ${statusMeta.className}`.trim(), statusMeta.label);
      statusTd.append(statusBadge);

      tr.append(
        tickerTh,
        createEl("td", "", getProventTypeLabel(item.type)),
        statusTd,
        createEl("td", "", formatDateBr(item.comDate)),
        createEl("td", "", formatDateBr(item.paymentDate)),
        createEl("td", "", formatQuantity(item.quantityOnComDate)),
        createEl("td", "", formatUnitCurrency(item.amountPerShare)),
        createEl("td", "", formatCurrency(item.grossAmount)),
        createEl("td", "", formatCurrency(item.withholdingTaxAmount)),
        createEl("td", "", formatCurrency(item.netAmount))
      );

      return tr;
    });

    tbody.replaceChildren(...rows);

    if (pagination && paginationInfo && prevBtn && nextBtn) {
      pagination.hidden = totalPages <= 1;
      paginationInfo.textContent = `P\u00e1gina ${state.currentPage + 1} de ${totalPages} \u2022 ${totalItems} ${totalItems === 1 ? "provento" : "proventos"}`;
      prevBtn.disabled = state.currentPage === 0;
      nextBtn.disabled = state.currentPage >= totalPages - 1;
    }
  }

  async function loadProvents() {
    showError("");
    setLoading(true);
    try {
      const portfolio = await resolvePortfolio();
      const portfolioId = portfolio.id;

      const proventsData = await fetchAllProvents(portfolioId);

      state.provisions = proventsData.items;
      state.currentPage = 0;

      const metrics = computeMetrics(state.provisions);
      renderKpis(metrics);

      const hasProvisions = state.provisions.length > 0;
      setContentVisibility({ hasProvisions, isError: false });

      if (hasProvisions) {
        renderAssetBreakdown(state.provisions);
        renderTypeBreakdown(state.provisions);
        renderMonthlyHistory(state.provisions);
        renderProventsTablePage();
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : "Erro ao carregar proventos.";
      showError(`N\u00e3o foi poss\u00edvel carregar os proventos da carteira: ${message}`);
      setContentVisibility({ hasProvisions: false, isError: true });
    } finally {
      setLoading(false);
    }
  }

  function wirePagination() {
    const prevBtn = document.querySelector("#provents-prev-btn");
    const nextBtn = document.querySelector("#provents-next-btn");

    if (prevBtn) {
      prevBtn.addEventListener("click", () => {
        if (state.currentPage > 0) {
          state.currentPage -= 1;
          renderProventsTablePage();
        }
      });
    }

    if (nextBtn) {
      nextBtn.addEventListener("click", () => {
        const totalPages = Math.max(1, Math.ceil(state.provisions.length / TABLE_PAGE_SIZE));
        if (state.currentPage < totalPages - 1) {
          state.currentPage += 1;
          renderProventsTablePage();
        }
      });
    }
  }

  function wireLogoutAction() {
    const logoutBtn = document.querySelector("#logout-btn");
    if (!logoutBtn) {
      return;
    }

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
        showError(message);
        window.location.replace("/login.html");
      } finally {
        logoutBtn.disabled = false;
      }
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    wireLogoutAction();
    wirePagination();
    loadProvents();
  });

  document.addEventListener("valora:transaction-saved", loadProvents);
  window.addEventListener("valora:portfolio-changed", loadProvents);
})();
