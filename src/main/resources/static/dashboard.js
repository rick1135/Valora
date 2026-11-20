(() => {
  const SVG_NS = "http://www.w3.org/2000/svg";

  const CATEGORY_META = {
    ACOES: { label: "A\u00e7\u00f5es", color: "#ffde70" },
    FII: { label: "FIIs", color: "#5796f2" },
    ETF: { label: "ETFs", color: "#a78bfa" },
    CRIPTO: { label: "Criptos", color: "#62ddb2" },
    RENDA_FIXA: { label: "Renda Fixa", color: "#08d096" },
  };

  const currencyFormatter = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  const quantityFormatter = new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 8,
  });

  const percentFormatter = new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

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

  function formatPercent(value, signed = false) {
    const num = toNumber(value);
    const formatted = `${percentFormatter.format(num)}%`;
    if (signed && num > 0) {
      return `+${formatted}`;
    }
    return formatted;
  }

  function formatQuantity(value) {
    return quantityFormatter.format(toNumber(value));
  }

  function getCategoryMeta(category) {
    return (
      CATEGORY_META[category] || {
        label: String(category || "Outros"),
        color: "#8c99ad",
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

  function setLoading(isLoading) {
    const loadingBanner = document.querySelector("#dashboard-loading");
    if (loadingBanner) {
      loadingBanner.hidden = !isLoading;
    }
    const main = document.querySelector(".dashboard");
    if (main) {
      main.setAttribute("aria-busy", String(Boolean(isLoading)));
    }
  }

  function showError(message) {
    const banner = document.querySelector("#dashboard-error");
    if (!banner) return;
    if (!message) {
      banner.hidden = true;
      banner.textContent = "";
      return;
    }
    banner.textContent = message;
    banner.hidden = false;
  }

  function renderFallbackNotice(summary) {
    const banner = document.querySelector("#fallback-notice");
    if (!banner) return;

    const tickers = Array.isArray(summary?.fallbackTickers) ? summary.fallbackTickers : [];
    if (!summary?.fallbackQuoteUsed) {
      banner.hidden = true;
      banner.textContent = "";
      return;
    }

    banner.textContent =
      tickers.length > 0
        ? `Cota\u00e7\u00e3o de mercado indispon\u00edvel para: ${tickers.join(", ")}. ` +
          "O resumo consolidado usa o pre\u00e7o m\u00e9dio de compra como fallback."
        : "Cota\u00e7\u00e3o de mercado indispon\u00edvel para parte da carteira. " +
          "O resumo consolidado usa o pre\u00e7o m\u00e9dio de compra como fallback.";
    banner.hidden = false;
  }

  function buildMetricCard({ label, value, note, tone, badge, badgeTone, badgeTitle, trend, trendTone }) {
    const card = document.createElement("article");
    card.className = `metric-card${tone ? ` metric-card--${tone}` : ""}`;

    const labelEl = document.createElement("p");
    labelEl.className = "metric-card__label";
    labelEl.textContent = label;

    const bodyEl = document.createElement("div");
    bodyEl.className = "metric-card__body";

    const valueEl = document.createElement("span");
    valueEl.className = "metric-card__value";
    valueEl.textContent = value;
    bodyEl.append(valueEl);

    if (badge) {
      const badgeEl = document.createElement("span");
      badgeEl.className = `metric-card__badge${badgeTone ? ` metric-card__badge--${badgeTone}` : ""}`;
      badgeEl.textContent = badge;
      if (badgeTitle) {
        badgeEl.title = badgeTitle;
      }
      bodyEl.append(badgeEl);
    }

    if (trend) {
      const trendEl = document.createElement("span");
      trendEl.className = `metric-card__trend${trendTone ? ` metric-card__trend--${trendTone}` : ""}`;
      trendEl.setAttribute("aria-hidden", "true");
      trendEl.textContent = trend;
      bodyEl.append(trendEl);
    }

    card.append(labelEl, bodyEl);
    if (note) {
      const noteEl = document.createElement("p");
      noteEl.className = "metric-card__note";
      noteEl.textContent = note;
      card.append(noteEl);
    }
    return card;
  }

  function renderMetrics(summary, hasPositions) {
    const grid = document.querySelector("#metric-grid");
    if (!grid) return;

    const totalPatrimony = toNumber(summary?.totalPatrimony);
    const totalInvested = toNumber(summary?.totalInvested);
    const totalProvents = toNumber(summary?.totalProvents);
    const fallbackUsed = Boolean(summary?.fallbackQuoteUsed);
    const profitability = summary?.profitability || {};
    const absoluteProfit = toNumber(profitability.absoluteProfit);
    const hasTotalPercentage =
      profitability.totalPercentage !== null &&
      profitability.totalPercentage !== undefined &&
      Number.isFinite(Number(profitability.totalPercentage));
    const totalPercentage = hasTotalPercentage ? Number(profitability.totalPercentage) : null;

    const dayChangeAvailable = Boolean(profitability.dayChangeAvailable);
    const dayPct = toNumber(profitability.dayPercentageVariation);
    const dayBadge = dayChangeAvailable ? `Hoje: ${formatPercent(dayPct, true)}` : null;
    const dayBadgeTone = dayPct > 0 ? "positive" : dayPct < 0 ? "" : "neutral";
    const dayBadgeTitle = dayChangeAvailable
      ? "Varia\u00e7\u00e3o do patrim\u00f4nio no preg\u00e3o de hoje em rela\u00e7\u00e3o ao fechamento anterior"
      : null;

    const profitTone =
      absoluteProfit > 0 ? "" : absoluteProfit < 0 ? "negative" : "neutral";
    const pctTone =
      totalPercentage === null
        ? "neutral"
        : totalPercentage > 0
          ? ""
          : totalPercentage < 0
            ? "negative"
            : "neutral";
    const trendSymbol =
      totalPercentage === null
        ? null
        : totalPercentage > 0
          ? "\u2197"
          : totalPercentage < 0
            ? "\u2198"
            : null;

    const benchmarks = Array.isArray(profitability.benchmarkComparisons)
      ? profitability.benchmarkComparisons
      : [];
    const primaryBenchmark = benchmarks.find(
      (item) =>
        item &&
        typeof item.name === "string" &&
        item.name.trim() !== "" &&
        item.percentage !== null &&
        item.percentage !== undefined &&
        Number.isFinite(Number(item.percentage))
    );
    const unavailableBenchmarkNames = benchmarks
      .filter(
        (item) =>
          item &&
          typeof item.name === "string" &&
          item.name.trim() !== "" &&
          (item.percentage === null ||
            item.percentage === undefined ||
            !Number.isFinite(Number(item.percentage)))
      )
      .map((item) => item.name.trim());
    const rentabilityNote = primaryBenchmark
      ? `${primaryBenchmark.name}: ${formatPercent(primaryBenchmark.percentage, true)}`
      : unavailableBenchmarkNames.length > 0
        ? `Benchmark indispon\u00edvel (${unavailableBenchmarkNames.join(", ")})`
        : !hasTotalPercentage
          ? "Carteira zerada com resultado realizado"
          : hasPositions
            ? "Retorno total sobre o valor aplicado"
            : "Sem opera\u00e7\u00f5es registradas";

    const patrimonyNote = fallbackUsed
      ? `Valor aplicado: ${formatCurrency(totalInvested)} \u2022 Fallback por pre\u00e7o m\u00e9dio`
      : `Valor aplicado: ${formatCurrency(totalInvested)}`;

    const cards = [
      buildMetricCard({
        label: "Patrim\u00f4nio total",
        value: formatCurrency(totalPatrimony),
        note: patrimonyNote,
        tone: "neutral",
        badge: dayBadge,
        badgeTone: dayBadgeTone,
        badgeTitle: dayBadgeTitle,
      }),
      buildMetricCard({
        label: "Lucro total",
        value: formatCurrency(absoluteProfit),
        note: hasPositions || totalProvents > 0 || absoluteProfit !== 0
          ? "Ganho de capital + proventos"
          : "Nenhuma movimenta\u00e7\u00e3o registrada",
        tone: profitTone,
      }),
      buildMetricCard({
        label: "Proventos",
        value: formatCurrency(totalProvents),
        note: "Dividendos, JCP e rendimentos",
        tone: totalProvents > 0 ? "" : "neutral",
      }),
      buildMetricCard({
        label: "Rentabilidade",
        value: hasTotalPercentage
          ? formatPercent(totalPercentage, totalPercentage > 0)
          : "Indispon\u00edvel",
        note: rentabilityNote,
        tone: pctTone,
        trend: trendSymbol,
        trendTone: totalPercentage !== null && totalPercentage < 0 ? "negative" : "",
      }),
    ];

    grid.replaceChildren(...cards);
  }

  function buildOverviewRow(label, value, tone = "") {
    const row = document.createElement("div");
    row.className = "overview-row";

    const labelEl = document.createElement("span");
    labelEl.className = "overview-row__label";
    labelEl.textContent = label;

    const valueEl = document.createElement("span");
    valueEl.className = `overview-row__value${tone ? ` overview-row__value--${tone}` : ""}`;
    valueEl.textContent = value;

    row.append(labelEl, valueEl);
    return row;
  }

  function renderOverviewBreakdown(summary) {
    const container = document.querySelector("#overview-breakdown");
    if (!container) return;

    const totalInvested = toNumber(summary?.totalInvested);
    const totalPatrimony = toNumber(summary?.totalPatrimony);
    const totalProvents = toNumber(summary?.totalProvents);
    const profitability = summary?.profitability || {};
    const absoluteProfit = toNumber(profitability.absoluteProfit);

    const profitTone =
      absoluteProfit > 0 ? "positive" : absoluteProfit < 0 ? "negative" : "";

    const patrimonyLabel = summary?.fallbackQuoteUsed
      ? "Patrim\u00f4nio atual (fallback p/ pre\u00e7o m\u00e9dio)"
      : "Patrim\u00f4nio atual";

    const rows = [
      buildOverviewRow("Valor aplicado", formatCurrency(totalInvested)),
      buildOverviewRow(patrimonyLabel, formatCurrency(totalPatrimony)),
      buildOverviewRow("Resultado acumulado", formatCurrency(absoluteProfit), profitTone),
      buildOverviewRow("Proventos acumulados", formatCurrency(totalProvents), totalProvents > 0 ? "positive" : ""),
    ];

    if (profitability.dayChangeAvailable) {
      const dayAbs = toNumber(profitability.dayAbsoluteVariation);
      const dayTone = dayAbs > 0 ? "positive" : dayAbs < 0 ? "negative" : "";
      rows.push(
        buildOverviewRow(
          "Varia\u00e7\u00e3o do dia",
          `${formatCurrency(dayAbs)} (${formatPercent(profitability.dayPercentageVariation, true)})`,
          dayTone
        )
      );
    }

    container.replaceChildren(...rows);
  }

  function renderAllocation(summary) {
    const content = document.querySelector("#allocation-content");
    const emptyEl = document.querySelector("#allocation-empty");
    const svg = document.querySelector("#allocation-chart");
    const list = document.querySelector("#allocation-list");
    if (!content || !emptyEl || !svg || !list) return;

    const allocations = Array.isArray(summary?.allocations)
      ? summary.allocations.filter((item) => toNumber(item.percentage) > 0 || toNumber(item.totalValue) > 0)
      : [];

    if (allocations.length === 0) {
      content.hidden = true;
      emptyEl.hidden = false;
      svg.replaceChildren();
      list.replaceChildren();
      return;
    }

    content.hidden = false;
    emptyEl.hidden = true;

    const radius = 104;
    const circumference = 2 * Math.PI * radius;

    const bgCircle = document.createElementNS(SVG_NS, "circle");
    bgCircle.setAttribute("cx", "160");
    bgCircle.setAttribute("cy", "160");
    bgCircle.setAttribute("r", String(radius));
    bgCircle.setAttribute("fill", "none");
    bgCircle.setAttribute("stroke", "#1e293b");
    bgCircle.setAttribute("stroke-width", "26");

    const group = document.createElementNS(SVG_NS, "g");
    group.setAttribute("transform", "rotate(-90 160 160)");

    let cumulativeOffset = 0;
    const listItems = [];

    allocations.forEach((item) => {
      const meta = getCategoryMeta(item.category);
      const pct = Math.max(0, Math.min(100, toNumber(item.percentage)));
      const segmentLength = (pct / 100) * circumference;

      if (segmentLength > 0) {
        const segment = document.createElementNS(SVG_NS, "circle");
        segment.setAttribute("cx", "160");
        segment.setAttribute("cy", "160");
        segment.setAttribute("r", String(radius));
        segment.setAttribute("fill", "none");
        segment.setAttribute("stroke", meta.color);
        segment.setAttribute("stroke-width", "26");
        segment.setAttribute("stroke-dasharray", `${segmentLength} ${circumference}`);
        segment.setAttribute("stroke-dashoffset", String(-cumulativeOffset));
        group.append(segment);
        cumulativeOffset += segmentLength;
      }

      const row = document.createElement("li");

      const swatch = document.createElement("span");
      swatch.className = "allocation-swatch";
      swatch.style.background = meta.color;
      swatch.setAttribute("aria-hidden", "true");

      const label = document.createElement("span");
      label.textContent = meta.label;

      const percent = document.createElement("span");
      percent.className = "allocation-percent";
      percent.textContent = formatPercent(pct);

      row.append(swatch, label, percent);
      listItems.push(row);
    });

    const hole = document.createElementNS(SVG_NS, "circle");
    hole.setAttribute("class", "allocation-hole");
    hole.setAttribute("cx", "160");
    hole.setAttribute("cy", "160");
    hole.setAttribute("r", "76");

    svg.replaceChildren(bgCircle, group, hole);
    list.replaceChildren(...listItems);
  }

  function createCell(text, className = "") {
    const td = document.createElement("td");
    if (className) {
      td.className = className;
    }
    td.textContent = text;
    return td;
  }

  function renderAssetsTable(positions, summary) {
    const tableCard = document.querySelector("#assets-table-card");
    const tbody = document.querySelector("#assets-table");
    const emptyState = document.querySelector("#assets-empty");
    if (!tableCard || !tbody || !emptyState) return;

    const list = Array.isArray(positions) ? positions : [];
    if (list.length === 0) {
      tableCard.hidden = true;
      emptyState.hidden = false;
      tbody.replaceChildren();
      return;
    }

    tableCard.hidden = false;
    emptyState.hidden = true;

    const totalPatrimony = toNumber(summary?.totalPatrimony);
    const fallbackTickers = new Set(
      Array.isArray(summary?.fallbackTickers) ? summary.fallbackTickers : []
    );

    const rows = list.map((asset) => {
      const tr = document.createElement("tr");

      const assetTd = document.createElement("td");
      const assetCell = document.createElement("div");
      assetCell.className = "asset-cell";

      const symbolWrap = document.createElement("span");
      symbolWrap.className = "asset-symbol";
      symbolWrap.textContent = asset.ticker || "-";

      if (asset.ticker && fallbackTickers.has(asset.ticker)) {
        const pill = document.createElement("span");
        pill.className = "fallback-pill";
        pill.textContent = "Pre\u00e7o m\u00e9dio";
        pill.title = "Cota\u00e7\u00e3o indispon\u00edvel; usando pre\u00e7o m\u00e9dio";
        symbolWrap.append(pill);
      }

      assetCell.append(symbolWrap);

      if (asset.assetName) {
        const nameEl = document.createElement("span");
        nameEl.className = "asset-name";
        nameEl.textContent = asset.assetName;
        assetCell.append(nameEl);
      }

      assetTd.append(assetCell);

      const hasCurrentPrice =
        asset.currentPrice !== null &&
        asset.currentPrice !== undefined &&
        toNumber(asset.currentPrice) > 0;
      const hasCurrentTotal =
        asset.currentTotalValue !== null &&
        asset.currentTotalValue !== undefined &&
        toNumber(asset.currentTotalValue) > 0;
      const hasProfitability =
        hasCurrentTotal &&
        asset.profitability !== null &&
        asset.profitability !== undefined &&
        Number.isFinite(Number(asset.profitability));

      const currentPriceText = hasCurrentPrice
        ? formatCurrency(asset.currentPrice)
        : "Indispon\u00edvel";
      const currentPriceClass = hasCurrentPrice ? "" : "value-unavailable";

      const currentTotalText = hasCurrentTotal
        ? formatCurrency(asset.currentTotalValue)
        : "Indispon\u00edvel";
      const currentTotalClass = hasCurrentTotal ? "" : "value-unavailable";

      const profitabilityVal = hasProfitability ? toNumber(asset.profitability) : 0;
      const profitabilityText = hasProfitability
        ? formatPercent(profitabilityVal, profitabilityVal > 0)
        : "Indispon\u00edvel";
      const profitabilityClass = !hasProfitability
        ? "value-unavailable"
        : profitabilityVal > 0
          ? "value-positive"
          : profitabilityVal < 0
            ? "value-negative"
            : "value-neutral";

      const shareAvailable = hasCurrentTotal && totalPatrimony > 0;
      const portfolioShareText = shareAvailable
        ? formatPercent((toNumber(asset.currentTotalValue) / totalPatrimony) * 100)
        : "\u2014";
      const portfolioShareClass = shareAvailable ? "" : "value-unavailable";

      tr.append(
        assetTd,
        createCell(formatQuantity(asset.quantity)),
        createCell(formatCurrency(asset.averagePrice)),
        createCell(currentPriceText, currentPriceClass),
        createCell(currentTotalText, currentTotalClass),
        createCell(profitabilityText, profitabilityClass),
        createCell(portfolioShareText, portfolioShareClass)
      );

      return tr;
    });

    tbody.replaceChildren(...rows);
  }

  let loadId = 0;

  async function loadDashboard() {
    const currentLoad = ++loadId;
    const sections = document.querySelectorAll(".portfolio-data");
    sections.forEach((section) => { section.hidden = true; });
    renderFallbackNotice(null);
    showError("");
    setLoading(true);
    try {
      const portfolio = await window.ValoraAPI.getPortfolio();
      if (currentLoad !== loadId) return;
      if (!portfolio) {
        showError("Crie uma carteira para visualizar o resumo.");
        return;
      }
      const portfolioId = portfolio.id;
      const [summary, positions] = await Promise.all([
        apiRequest(`/portfolios/${encodeURIComponent(portfolioId)}/summary`),
        apiRequest(`/portfolios/${encodeURIComponent(portfolioId)}/positions`),
      ]);
      if (currentLoad !== loadId) return;

      const safeSummary = summary || {};
      const safePositions = Array.isArray(positions) ? positions : [];

      renderFallbackNotice(safeSummary);
      renderMetrics(safeSummary, safePositions.length > 0);
      renderOverviewBreakdown(safeSummary);
      renderAllocation(safeSummary);
      renderAssetsTable(safePositions, safeSummary);
      sections.forEach((section) => { section.hidden = false; });
    } catch (error) {
      if (currentLoad !== loadId) return;
      const message = error instanceof Error ? error.message : "Erro ao carregar o dashboard.";
      showError(`N\u00e3o foi poss\u00edvel carregar os dados da carteira: ${message}`);
    } finally {
      if (currentLoad === loadId) setLoading(false);
    }
  }

  function wireEmptyStateAction() {
    const emptyCta = document.querySelector("#empty-state-cta");
    const headerNewTxBtn = document.querySelector("#new-transaction-btn");
    if (emptyCta && headerNewTxBtn) {
      emptyCta.addEventListener("click", () => {
        headerNewTxBtn.click();
      });
    }
  }

  function wireLogoutAction() {
    const logoutBtn = document.querySelector("#logout-btn");
    if (!logoutBtn) return;

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
    wireEmptyStateAction();
    wireLogoutAction();
    loadDashboard();
  });

  document.addEventListener("valora:transaction-saved", loadDashboard);
  window.addEventListener("valora:portfolio-changed", loadDashboard);
})();
