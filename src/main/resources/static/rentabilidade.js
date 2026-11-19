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

  function isFiniteNumber(value) {
    return (
      value !== null &&
      value !== undefined &&
      value !== "" &&
      Number.isFinite(Number(value))
    );
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
      typeof portfolio === "object" &&
      typeof portfolio?.name === "string" &&
      portfolio.name.trim()
        ? portfolio.name.trim()
        : "Carteira";

    const portfolioLabel = document.querySelector("#portfolio-name");
    if (portfolioLabel) {
      portfolioLabel.textContent = portfolioName;
    }

    return { id: portfolioId, name: portfolioName };
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

  function renderSummaryCards(summary) {
    const container = document.querySelector("#rentability-cards");
    if (!container) return;

    const prof = summary?.profitability || {};
    const absoluteProfit = toNumber(prof.absoluteProfit);
    const realizedProfit = toNumber(prof.realizedProfit);
    const unrealizedProfit = toNumber(prof.unrealizedProfit);
    const totalProvents = toNumber(summary?.totalProvents);
    const hasTotalPercentage = isFiniteNumber(prof.totalPercentage);
    const totalPercentage = hasTotalPercentage ? toNumber(prof.totalPercentage) : null;

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

  async function loadRentabilidade() {
    showError("");
    renderFallbackNotice(null);
    setLoading(true);

    const emptyState = document.querySelector("#rentabilidade-empty");
    const content = document.querySelector("#rentabilidade-content");

    try {
      const portfolio = await resolvePortfolio();
      const summary =
        (await apiRequest(
          `/portfolios/${encodeURIComponent(portfolio.id)}/summary`
        )) || {};

      renderFallbackNotice(summary);

      if (!hasPortfolioActivity(summary)) {
        if (content) content.hidden = true;
        if (emptyState) emptyState.hidden = false;
        return;
      }

      if (emptyState) emptyState.hidden = true;
      if (content) content.hidden = false;

      renderSummaryCards(summary);
      renderBenchmarks(summary);
      renderBreakdown(summary);
    } catch (error) {
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
      setLoading(false);
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
    loadRentabilidade();
  });

  document.addEventListener("valora:transaction-saved", loadRentabilidade);
  window.addEventListener("valora:portfolio-changed", loadRentabilidade);
})();
