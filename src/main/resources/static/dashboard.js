const metrics = [
  {
    label: "Patrim\u00f4nio total",
    value: "R$ 84.230,00",
    note: "Valor aplicado: R$ 79.520,00",
    badge: "-6,25%",
    tone: "neutral",
  },
  {
    label: "Lucro total",
    value: "R$ 4.710,17",
    note: "Ganho de capital + proventos",
  },
  {
    label: "Proventos 12M",
    value: "R$ 3.246,80",
    note: "Dividendos, JCP e rendimentos",
  },
  {
    label: "Rentabilidade",
    value: "51,97%",
    note: "36,2% abaixo do CDI",
    trend: "\u2197",
  },
];

const monthlyEvolution = [
  { month: "Jul", applied: 45, gain: 0 },
  { month: "Ago", applied: 48, gain: 0 },
  { month: "Set", applied: 50, gain: 0 },
  { month: "Out", applied: 53, gain: 0 },
  { month: "Nov", applied: 61, gain: 0 },
  { month: "Dez", applied: 64, gain: 0 },
  { month: "Jan", applied: 68, gain: 0 },
  { month: "Fev", applied: 70, gain: 0 },
  { month: "Mar", applied: 74, gain: 0 },
  { month: "Abr", applied: 93, gain: 18 },
  { month: "Mai", applied: 87, gain: 16 },
  { month: "Jun", applied: 82, gain: 18 },
];

const allocation = [
  { label: "FIIs", percent: "58,29%", color: "#5796f2" },
  { label: "Criptos", percent: "26,12%", color: "#62ddb2" },
  { label: "A\u00e7\u00f5es", percent: "18,34%", color: "#ffde70" },
];

const assets = [
  {
    symbol: "BBAS3",
    quantity: "42",
    averagePrice: "R$ 25,10",
    currentPrice: "R$ 19,96",
    variation: "-20,50%",
    profitability: "-14,17%",
    portfolioShare: "3,74%",
    buy: "N\u00e3o",
    negative: true,
  },
  {
    symbol: "MXRF11",
    quantity: "110",
    averagePrice: "R$ 10,20",
    currentPrice: "R$ 10,46",
    variation: "2,55%",
    profitability: "12,08%",
    portfolioShare: "8,42%",
    buy: "Sim",
    negative: false,
  },
  {
    symbol: "BTC",
    quantity: "0,034",
    averagePrice: "R$ 295k",
    currentPrice: "R$ 281k",
    variation: "-4,89%",
    profitability: "10,22%",
    portfolioShare: "15,51%",
    buy: "N\u00e3o",
    negative: true,
  },
  {
    symbol: "HGLG11",
    quantity: "18",
    averagePrice: "R$ 158,30",
    currentPrice: "R$ 164,12",
    variation: "3,68%",
    profitability: "8,44%",
    portfolioShare: "6,28%",
    buy: "Sim",
    negative: false,
  },
];

function renderMetrics() {
  const grid = document.querySelector("#metric-grid");

  metrics.forEach((metric) => {
    const card = document.createElement("article");
    card.className = `metric-card${metric.tone ? ` metric-card--${metric.tone}` : ""}`;

    const label = document.createElement("p");
    label.className = "metric-card__label";
    label.textContent = metric.label;

    const body = document.createElement("div");
    body.className = "metric-card__body";

    const value = document.createElement("span");
    value.className = "metric-card__value";
    value.textContent = metric.value;
    body.append(value);

    if (metric.badge) {
      const badge = document.createElement("span");
      badge.className = "metric-card__badge";
      badge.textContent = metric.badge;
      body.append(badge);
    }

    if (metric.trend) {
      const trend = document.createElement("span");
      trend.className = "metric-card__trend";
      trend.setAttribute("aria-hidden", "true");
      trend.textContent = metric.trend;
      body.append(trend);
    }

    const note = document.createElement("p");
    note.className = "metric-card__note";
    note.textContent = metric.note;

    card.append(label, body, note);
    grid.append(card);
  });
}

function renderEvolutionChart() {
  const chart = document.querySelector("#equity-chart");

  monthlyEvolution.forEach((item) => {
    const month = document.createElement("div");
    month.className = "bar-month";
    month.setAttribute(
      "aria-label",
      `${item.month}: valor aplicado ${item.applied}%, ganho de capital ${item.gain}%`
    );

    const stack = document.createElement("div");
    stack.className = "bar-stack";

    const applied = document.createElement("span");
    applied.className = "bar-fill";
    applied.style.setProperty("--height", `${item.applied}%`);

    const gain = document.createElement("span");
    gain.className = "gain-fill";
    gain.style.setProperty("--gain", item.gain ? `${item.gain}px` : "0");

    const label = document.createElement("span");
    label.className = "bar-label";
    label.textContent = item.month;

    stack.append(applied, gain);
    month.append(stack, label);
    chart.append(month);
  });
}

function renderAllocation() {
  const list = document.querySelector("#allocation-list");

  allocation.forEach((item) => {
    const row = document.createElement("li");

    const swatch = document.createElement("span");
    swatch.className = "allocation-swatch";
    swatch.style.background = item.color;
    swatch.setAttribute("aria-hidden", "true");

    const label = document.createElement("span");
    label.textContent = item.label;

    const percent = document.createElement("span");
    percent.className = "allocation-percent";
    percent.textContent = item.percent;

    row.append(swatch, label, percent);
    list.append(row);
  });
}

function renderAssetsTable() {
  const table = document.querySelector("#assets-table");

  assets.forEach((asset) => {
    const row = document.createElement("tr");
    const valueTone = asset.negative ? "value-negative" : "value-positive";

    row.innerHTML = `
      <td class="asset-symbol">${asset.symbol}</td>
      <td>${asset.quantity}</td>
      <td>${asset.averagePrice}</td>
      <td>${asset.currentPrice}</td>
      <td class="${valueTone}">${asset.variation}</td>
      <td class="${asset.profitability.startsWith("-") ? "value-negative" : "value-positive"}">${asset.profitability}</td>
      <td>${asset.portfolioShare}</td>
      <td>${asset.buy}</td>
    `;

    table.append(row);
  });
}

renderMetrics();
renderEvolutionChart();
renderAllocation();
renderAssetsTable();
