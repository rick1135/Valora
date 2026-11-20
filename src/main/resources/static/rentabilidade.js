(() => {
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

  const compactCurrencyFormatter = new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    notation: "compact",
    maximumFractionDigits: 2,
  });

  const chartDateFormatter = new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "2-digit",
    timeZone: "UTC",
  });

  function isFiniteNumber(value) {
    return (
      value !== null &&
      value !== undefined &&
      value !== "" &&
      Number.isFinite(Number(value))
    );
  }
  function formatTwrValuedThrough(value) {
    if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const date = new Date(`${value}T00:00:00Z`);
    return Number.isNaN(date.getTime()) ? null : new Intl.DateTimeFormat("pt-BR", {
      day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC",
    }).format(date);
  }

  function toNumber(value) {
    if (!isFiniteNumber(value)) {
      return 0;
    }
    return Number(value);
  }

  function formatCurrency(value, signed = false) {
    const num = toNumber(value);
    const formatted = currencyFormatter.format(num);
    if (signed && num > 0) {
      return `+${formatted}`;
    }
    return formatted;
  }

  function formatPercent(value, signed = false) {
    const num = toNumber(value);
    const formatted = `${percentFormatter.format(num)}%`;
    if (signed && num > 0) {
      return `+${formatted}`;
    }
    return formatted;
  }

  function valueTone(value) {
    const num = toNumber(value);
    if (num > 0) return "positive";
    if (num < 0) return "negative";
    return "neutral";
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
    const loadingBanner = document.querySelector("#rentabilidade-loading");
    if (loadingBanner) {
      loadingBanner.hidden = !isLoading;
    }
    const main = document.querySelector(".dashboard");
    if (main) {
      main.setAttribute("aria-busy", String(Boolean(isLoading)));
    }
  }

  function showError(message) {
    const banner = document.querySelector("#rentabilidade-error");
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

    const fallbackTickers = Array.isArray(summary?.fallbackTickers)
      ? summary.fallbackTickers.filter(Boolean)
      : [];

    if (!summary?.fallbackQuoteUsed) {
      banner.hidden = true;
      banner.textContent = "";
      return;
    }

    const tickerList = fallbackTickers.join(", ");
    banner.textContent = tickerList
      ? `Cota\u00e7\u00f5es em tempo real indispon\u00edveis para ${tickerList}. Valores calculados com pre\u00e7o m\u00e9dio como fallback.`
      : "Algumas cota\u00e7\u00f5es em tempo real est\u00e3o indispon\u00edveis; valores calculados com pre\u00e7o m\u00e9dio como fallback.";
    banner.hidden = false;
  }

  function hasPortfolioActivity(summary) {
    const positions = Array.isArray(summary?.positions) ? summary.positions : [];
    const allocations = Array.isArray(summary?.allocations) ? summary.allocations : [];
    const prof = summary?.profitability || {};

    return (
      positions.length > 0 ||
      allocations.length > 0 ||
      toNumber(summary?.totalInvested) > 0 ||
      toNumber(summary?.totalPatrimony) > 0 ||
      toNumber(summary?.totalProvents) !== 0 ||
      toNumber(prof.absoluteProfit) !== 0 ||
      toNumber(prof.realizedProfit) !== 0 ||
      toNumber(prof.unrealizedProfit) !== 0
    );
  }

  function renderSummaryCards(summary, history) {
    const container = document.querySelector("#rentability-cards");
    if (!container) return;

    const prof = summary?.profitability || {};
    const absoluteProfit = toNumber(prof.absoluteProfit);
    const realizedProfit = toNumber(prof.realizedProfit);
    const unrealizedProfit = toNumber(prof.unrealizedProfit);
    const totalProvents = toNumber(summary?.totalProvents);
    const hasTotalPercentage = isFiniteNumber(prof.totalPercentage);
    const totalPercentage = hasTotalPercentage ? toNumber(prof.totalPercentage) : null;
    const hasPeriodTwr = isFiniteNumber(history?.twrPercentage);
    const twr = hasPeriodTwr ? history.twrPercentage : prof.twrPercentage;
    const hasTwr = isFiniteNumber(twr);
    const twrReliable = hasPeriodTwr ? history.pricingComplete !== false : prof.twrReliable === true;
    const twrEstimated = hasTwr && !twrReliable;
    let twrBadge = twrEstimated ? { text: hasPeriodTwr ? "Estimado" : "Parcial" } : null;
    let twrNote = "O TWR isola o efeito de aportes e resgates.";
    if (hasPeriodTwr) {
      twrNote = twrReliable
        ? "Retorno no período selecionado. Isola o efeito de aportes e resgates."
        : "Retorno estimado: há cotações incompletas ou renda fixa a custo no período.";
    } else if (history && history.twrPercentage === null) {
      twrNote = "Período descontínuo ou sem base contínua para TWR.";
    } else if (hasTwr) {
      const valuedThrough = formatTwrValuedThrough(prof.twrValuedThrough);
      if (prof.twrUpToDate === false) {
        twrBadge = { text: "Desatualizado" };
        twrNote = `Retorno consolidado da carteira. ${valuedThrough ? `Atualizado até ${valuedThrough} ` : ""}(desatualizado em relação ao último fechamento).`;
      } else {
        twrNote = twrReliable
          ? "Retorno consolidado da carteira. Isola o efeito de aportes e resgates."
          : "Retorno estimado: histórico com cotações incompletas ou renda fixa a custo.";
        if (valuedThrough) twrNote = `Atualizado até ${valuedThrough}. ${twrNote}`;
      }
    }
    const cards = [
      {
        label: "Lucro absoluto",
        value: formatCurrency(absoluteProfit, absoluteProfit > 0),
        tone: valueTone(absoluteProfit),
        highlight: true,
        badge: hasTotalPercentage
          ? {
              text: formatPercent(totalPercentage, totalPercentage > 0),
              tone: valueTone(totalPercentage),
            }
          : {
              text: "Percentual indispon\u00edvel",
              tone: "unavailable",
            },
        note: "Soma de lucro n\u00e3o realizado, lucro realizado e proventos; percentual calculado sobre o custo atual.",
      },
      {
        label: "Retorno ponderado (TWR)",
        value: hasTwr ? formatPercent(twr, true) : "Indisponível",
        tone: hasTwr ? valueTone(twr) : "neutral",
        highlight: true,
        badge: twrBadge,
        note: twrNote,
      },
      {
        label: "Lucro n\u00e3o realizado",
        value: formatCurrency(unrealizedProfit, unrealizedProfit > 0),
        tone: valueTone(unrealizedProfit),
        note: "Diferen\u00e7a entre patrim\u00f4nio atual e capital investido nas posi\u00e7\u00f5es abertas.",
      },
      {
        label: "Lucro realizado",
        value: formatCurrency(realizedProfit, realizedProfit > 0),
        tone: valueTone(realizedProfit),
        note: "Resultado apurado em opera\u00e7\u00f5es de venda j\u00e1 encerradas.",
      },
      {
        label: "Proventos recebidos",
        value: formatCurrency(totalProvents),
        tone: totalProvents > 0 ? "positive" : "neutral",
        note: "Total l\u00edquido de proventos pagos contabilizados na carteira.",
      },
    ];

    const nodes = cards.map((card) => {
      const article = createEl(
        "article",
        card.highlight
          ? "rentability-card rentability-card--highlight"
          : "rentability-card"
      );

      const header = createEl("div", "rentability-card__header");
      header.append(createEl("p", "rentability-card__label", card.label));

      if (card.badge) {
        const badgeClass =
          card.badge.tone === "positive"
            ? "rentability-card__badge rentability-card__badge--positive"
            : card.badge.tone === "negative"
              ? "rentability-card__badge rentability-card__badge--negative"
              : card.badge.tone === "unavailable"
                ? "rentability-card__badge rentability-card__badge--unavailable"
                : "rentability-card__badge";
        header.append(createEl("span", badgeClass, card.badge.text));
      }

      const valueClass =
        card.tone === "positive"
          ? "rentability-card__value rentability-card__value--positive"
          : card.tone === "negative"
            ? "rentability-card__value rentability-card__value--negative"
            : "rentability-card__value";

      article.append(
        header,
        createEl("p", valueClass, card.value),
        createEl("p", "rentability-card__note", card.note)
      );

      return article;
    });

    container.replaceChildren(...nodes);
  }

  function renderBenchmarks(summary) {
    const list = document.querySelector("#benchmark-list");
    const emptyMsg = document.querySelector("#benchmarks-empty");
    if (!list || !emptyMsg) return;

    const prof = summary?.profitability || {};
    const comparisons = Array.isArray(prof.benchmarkComparisons)
      ? prof.benchmarkComparisons
      : [];

    if (comparisons.length === 0) {
      list.replaceChildren();
      list.hidden = true;
      emptyMsg.hidden = false;
      return;
    }

    list.hidden = false;
    emptyMsg.hidden = true;

    const items = comparisons.map((benchmark) => {
      const li = createEl("li", "benchmark-item");
      const info = createEl("div", "benchmark-item__info");
      const name = benchmark?.name ? String(benchmark.name) : "Benchmark";
      info.append(createEl("span", "benchmark-item__name", name));

      const hasPct = isFiniteNumber(benchmark?.percentage);
      info.append(
        createEl(
          "span",
          "benchmark-item__meta",
          hasPct
            ? "Refer\u00eancia acumulada no per\u00edodo"
            : "S\u00e9rie de benchmark indispon\u00edvel para o per\u00edodo."
        )
      );

      let valueClass = "benchmark-item__value value-unavailable";
      let valueText = "Indispon\u00edvel";

      if (hasPct) {
        const pct = toNumber(benchmark.percentage);
        const tone = valueTone(pct);
        valueClass =
          tone === "positive"
            ? "benchmark-item__value value-positive"
            : tone === "negative"
              ? "benchmark-item__value value-negative"
              : "benchmark-item__value value-neutral";
        valueText = formatPercent(pct, pct > 0);
      }

      li.append(info, createEl("span", valueClass, valueText));
      return li;
    });

    list.replaceChildren(...items);
  }

  function renderBreakdown(summary) {
    const container = document.querySelector("#profitability-breakdown");
    if (!container) return;

    const prof = summary?.profitability || {};
    const hasTotalPct = isFiniteNumber(prof.totalPercentage);
    const hasDayChange = Boolean(prof.dayChangeAvailable);

    const rows = [
      {
        label: "Rentabilidade sobre custo atual",
        value: hasTotalPct
          ? formatPercent(prof.totalPercentage, toNumber(prof.totalPercentage) > 0)
          : "Indispon\u00edvel",
        tone: hasTotalPct ? valueTone(prof.totalPercentage) : "unavailable",
      },
      {
        label: "Total investido",
        value: formatCurrency(summary?.totalInvested),
        tone: "neutral",
      },
      {
        label: "Patrim\u00f4nio atual",
        value: formatCurrency(summary?.totalPatrimony),
        tone: "neutral",
      },
      {
        label: "Varia\u00e7\u00e3o no dia",
        value: hasDayChange
          ? `${formatCurrency(prof.dayAbsoluteVariation, toNumber(prof.dayAbsoluteVariation) > 0)} (${formatPercent(prof.dayPercentageVariation, toNumber(prof.dayPercentageVariation) > 0)})`
          : "Indispon\u00edvel",
        tone: hasDayChange ? valueTone(prof.dayAbsoluteVariation) : "unavailable",
      },
    ];

    const nodes = rows.map((row) => {
      const div = createEl("div", "overview-row");
      const labelEl = createEl("span", "overview-row__label", row.label);
      const valueClass =
        row.tone === "positive"
          ? "overview-row__value overview-row__value--positive"
          : row.tone === "negative"
            ? "overview-row__value overview-row__value--negative"
            : row.tone === "unavailable"
              ? "overview-row__value value-unavailable"
              : "overview-row__value";
      const valueEl = createEl("span", valueClass, row.value);
      div.append(labelEl, valueEl);
      return div;
    });

    container.replaceChildren(...nodes);
  }

  function createSvgEl(tag, attributes, text) {
    const element = document.createElementNS("http://www.w3.org/2000/svg", tag);
    for (const [name, value] of Object.entries(attributes)) {
      element.setAttribute(name, value);
    }
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function makeSeries(label, style, points, valueKey) {
    const samples = (Array.isArray(points) ? points : [])
      .map((point) => {
        const value = point?.[valueKey];
        return {
          time: Date.parse(point?.date),
          value: isFiniteNumber(value) ? Number(value) : null,
        };
      })
      .filter((sample) => Number.isFinite(sample.time))
      .sort((a, b) => a.time - b.time);
    const values = samples.filter((sample) => sample.value !== null);
    return { label, className: `chart-series--${style}`, samples, values };
  }

  function drawSeries(svg, series, x, y) {
    let segment = [];

    function flushSegment() {
      if (segment.length === 1) {
        const [point] = segment;
        svg.append(createSvgEl("circle", {
          class: series.className,
          cx: x(point.time),
          cy: y(point.value),
          r: 2.5,
          fill: "currentColor",
        }));
      } else if (segment.length > 1) {
        svg.append(createSvgEl("polyline", {
          class: `chart-line ${series.className}`,
          points: segment.map((point) => `${x(point.time)},${y(point.value)}`).join(" "),
        }));
      }
      segment = [];
    }

    for (const point of series.samples) {
      if (point.value === null) {
        flushSegment();
      } else {
        segment.push(point);
      }
    }
    flushSegment();
  }

  function createChart(id, series, formatAxis, start, end) {
    const left = 94;
    const right = 408;
    const top = 12;
    const bottom = 174;
    let min = Infinity;
    let max = -Infinity;
    for (const item of series) {
      for (const { value } of item.values) {
        min = Math.min(min, value);
        max = Math.max(max, value);
      }
    }
    const padding = Math.max((max - min) * 0.08, Math.abs(max) * 0.000001, 0.02);
    min -= padding;
    max += padding;

    const x = (time) => left + ((time - start) / (end - start)) * (right - left);
    const y = (value) => bottom - ((value - min) / (max - min)) * (bottom - top);
    const svg = createSvgEl("svg", {
      viewBox: "0 0 440 202",
      role: "img",
      "aria-labelledby": `${id}-chart-title`,
    });

    const horizontalLine = (row, className) => createSvgEl("line", {
      class: className, x1: left, y1: row, x2: right, y2: row,
    });
    for (let step = 0; step <= 4; step += 1) {
      const value = min + ((max - min) * step) / 4;
      const row = y(value);
      svg.append(
        horizontalLine(row, "chart-grid-line"),
        createSvgEl("text", {
          class: "chart-axis-label",
          x: left - 8,
          y: row,
          "text-anchor": "end",
          "dominant-baseline": "middle",
        }, formatAxis(value))
      );
    }
    if (min <= 0 && max >= 0) {
      svg.append(horizontalLine(y(0), "chart-zero-line"));
    }

    const mid = Math.floor(((start + end) / 2) / 86400000) * 86400000;
    const dateLabels = [start, end];
    if (mid > start && mid < end) dateLabels.splice(1, 0, mid);
    for (const time of dateLabels) {
      svg.append(createSvgEl("text", {
        class: "chart-axis-label",
        x: x(time),
        y: bottom + 22,
        "text-anchor": "middle",
      }, chartDateFormatter.format(time)));
    }

    for (const item of series) drawSeries(svg, item, x, y);
    return svg;
  }

  function renderChart(id, series, formatValue, formatAxis, status, portfolioDates) {
    const container = document.querySelector(`#${id}-chart-container`);
    const legend = document.querySelector(`#${id}-chart-legend`);
    const empty = document.querySelector(`#${id}-chart-empty`);
    const available = series.filter((item) => item.values.length > 0);
    const hasChart = portfolioDates > 1 && available.some(({ values }) =>
      values[0].time < values[values.length - 1].time
    );

    const legendItems = available.map((item) => {
      const entry = createEl("li");
      const swatch = createEl("span", `chart-legend__swatch ${item.className}`);
      swatch.setAttribute("aria-hidden", "true");
      const latest = item.values[item.values.length - 1].value;
      entry.append(
        swatch,
        createEl("span", null, item.label),
        createEl("strong", null, formatValue(latest))
      );
      return entry;
    });
    legend.replaceChildren(...legendItems);
    legend.hidden = legendItems.length === 0;

    container.replaceChildren();
    container.hidden = !hasChart;
    empty.hidden = hasChart;
    if (!hasChart) {
      const noData = available.length === 0 || portfolioDates === 0;
      empty.textContent = status || (noData
        ? "Não há dados para este gráfico no período selecionado."
        : "São necessárias pelo menos duas datas com dados para exibir o gráfico.");
      return;
    }

    const start = Math.min(...available.map(({ values }) => values[0].time));
    const end = Math.max(...available.map(({ values }) => values[values.length - 1].time));
    container.append(createChart(id, available, formatAxis, start, end));
  }

  function renderHistory(history, status) {
    const points = history?.points;
    const benchmarks = history?.benchmarks;
    const benchmarkLabel = (label, ticker) =>
      history?.benchmarksComplete?.[ticker] === false ? `${label} (parcial)` : label;
    const twrSeries = [
      makeSeries("Carteira", "portfolio", points, "twrPercentage"),
      makeSeries(benchmarkLabel("CDI", "CDI"), "cdi", benchmarks?.CDI, "percentage"),
      makeSeries(benchmarkLabel("Ibovespa", "^BVSP"), "ibovespa", benchmarks?.["^BVSP"], "percentage"),
      makeSeries(benchmarkLabel("Selic", "SELIC"), "selic", benchmarks?.SELIC, "percentage"),
      makeSeries(benchmarkLabel("IPCA", "IPCA"), "ipca", benchmarks?.IPCA, "percentage"),
    ];
    const patrimonySeries = [
      makeSeries("Total investido", "invested", points, "totalInvested"),
      makeSeries("Patrimônio total", "portfolio", points, "totalPatrimony"),
    ];
    const portfolioDates = new Set(twrSeries[0].samples.map(({ time }) => time)).size;

    document.querySelector("#history-pricing-warning").hidden = history?.pricingComplete !== false;
    renderChart("twr", twrSeries, (value) => formatPercent(value, true), formatPercent, status, portfolioDates);
    renderChart("patrimony", patrimonySeries, formatCurrency,
      (value) => compactCurrencyFormatter.format(value), status, portfolioDates);
  }

  let loadId = 0;
  let historyLoadId = 0;
  let selectedRange = "ALL";
  let portfolioPath = null;
  let currentSummary = null;
  let currentHistory = null;
  let historyLoading = false;
  let historyHasActivity = false;

  function updateSummaryVisibility() {
    if (!currentSummary) return;

    const hasActivity = hasPortfolioActivity(currentSummary) || historyHasActivity;
    document.querySelector("#rentabilidade-content").hidden = !hasActivity;
    document.querySelector("#rentabilidade-empty").hidden = hasActivity || historyLoading;
    renderSummaryCards(currentSummary, currentHistory);
    setLoading(!hasActivity && historyLoading);
  }

  async function loadHistory(path, range) {
    const requestId = ++historyLoadId;
    const charts = document.querySelector("#history-charts-section");
    historyLoading = true;
    currentHistory = null;
    charts.setAttribute("aria-busy", "true");
    renderHistory(null, "Carregando histórico...");
    updateSummaryVisibility();

    let status = "";
    try {
      const history = await apiRequest(`${path}/history?range=${encodeURIComponent(range)}`);
      if (requestId !== historyLoadId) return;
      currentHistory = history;
      historyHasActivity ||= Array.isArray(history?.points) && history.points.length > 0;
    } catch {
      if (requestId !== historyLoadId) return;
      status = "Não foi possível carregar o histórico. Selecione um período para tentar novamente.";
    }

    historyLoading = false;
    renderHistory(currentHistory, status);
    charts.setAttribute("aria-busy", "false");
    updateSummaryVisibility();
  }

  async function loadRentabilidade() {
    const currentLoad = ++loadId;
    ++historyLoadId;
    portfolioPath = null;
    currentSummary = null;
    currentHistory = null;
    historyLoading = false;
    historyHasActivity = false;
    showError("");
    renderFallbackNotice(null);
    setLoading(true);

    const emptyState = document.querySelector("#rentabilidade-empty");
    const content = document.querySelector("#rentabilidade-content");
    if (content) content.hidden = true;
    if (emptyState) emptyState.hidden = true;

    try {
      const portfolio = await window.ValoraAPI.getPortfolio();
      if (currentLoad !== loadId) return;
      if (!portfolio) {
        showError("Crie uma carteira para visualizar a rentabilidade.");
        return;
      }
      portfolioPath = `/portfolios/${encodeURIComponent(portfolio.id)}`;
      const summaryRequest = apiRequest(`${portfolioPath}/summary`);
      loadHistory(portfolioPath, selectedRange);
      const summary = (await summaryRequest) || {};
      if (currentLoad !== loadId) return;

      currentSummary = summary;
      renderFallbackNotice(summary);
      renderBenchmarks(summary);
      renderBreakdown(summary);
      updateSummaryVisibility();
    } catch (error) {
      if (currentLoad !== loadId) return;
      if (content) content.hidden = true;
      if (emptyState) emptyState.hidden = true;
      const message =
        error instanceof Error
          ? error.message
          : "Erro ao carregar dados de rentabilidade.";
      showError(
        `N\u00e3o foi poss\u00edvel carregar a rentabilidade da carteira: ${message}`
      );
    } finally {
      if (currentLoad === loadId && !currentSummary) setLoading(false);
    }
  }

  function wireRangeSelector() {
    const buttons = document.querySelectorAll("#history-range-selector .range-btn");
    for (const button of buttons) {
      button.addEventListener("click", () => {
        selectedRange = button.dataset.range;
        for (const option of buttons) {
          const active = option === button;
          option.classList.toggle("range-btn--active", active);
          option.setAttribute("aria-pressed", String(active));
        }
        if (portfolioPath) loadHistory(portfolioPath, selectedRange);
      });
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
    wireRangeSelector();
    loadRentabilidade();
  });

  document.addEventListener("valora:transaction-saved", loadRentabilidade);
  window.addEventListener("valora:portfolio-changed", loadRentabilidade);
})();
