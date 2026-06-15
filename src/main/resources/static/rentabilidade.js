const profitabilityCards = [
  {
    title: "Rentabilidade total",
    value: "51,97%",
    note: "36,2% abaixo do CDI",
    trend: "\u2197",
    tone: "positive",
  },
  {
    title: "\u00daltimos 12 meses",
    value: "4,78%",
    note: "67,77% abaixo do CDI",
    trend: "\u2197",
    tone: "positive",
  },
  {
    title: "\u00daltimo m\u00eas",
    value: "-6,53%",
    note: "677,73% abaixo do CDI",
    trend: "\u2198",
    tone: "negative",
  },
];

const comparisonSeries = [
  {
    label: "Carteira",
    color: "#5796f2",
    visible: true,
    values: [4, 9, 9.2, 10, 11, 25, 22, 28, 23, 36],
  },
  {
    label: "CDI",
    color: "#ffde70",
    visible: true,
    values: [3.2, 4.6, 6.1, 8.4, 10.4, 15.5, 19.2, 23.2, 29.7, 35.4, 41.2, 47.2],
  },
  {
    label: "IPCA",
    color: "#a5b0c1",
    visible: false,
    values: [2.1, 3.2, 4.1, 4.8, 5.3, 6.8, 7.3, 8.6, 9.1, 10.2, 11.8, 12.6],
  },
  {
    label: "IFIX",
    color: "#62ddb2",
    visible: false,
    values: [1.6, 2.6, 3.7, 5.1, 6.8, 8.1, 10.5, 12.3, 14.2, 15.6, 18.8, 20.1],
  },
  {
    label: "IBOV",
    color: "#9c7af1",
    visible: false,
    values: [1.4, 6.5, 3.1, 8.9, 7.4, 12.2, 9.5, 16.4, 18.1, 14.2, 20.5, 22.1],
  },
];

const returnRows = [
  {
    year: "2026",
    values: ["3,61%", "2,30%", "-2,03%", "-3,81%", "-2,43%", "-4,20%", "-", "-", "-", "-", "-", "-"],
    annual: "-6,63%",
  },
  {
    year: "2025",
    values: ["-1,90%", "1,36%", "6,21%", "4,18%", "1,25%", "1,56%", "-2,16%", "2,33%", "3,33%", "0,80%", "3,40%", "2,47%"],
    annual: "25,01%",
  },
  {
    year: "2024",
    values: ["-1,61%", "0,09%", "2,99%", "-1,53%", "0,92%", "-0,27%", "1,00%", "2,55%", "-1,90%", "-3,06%", "-0,13%", "-1,86%"],
    annual: "-2,96%",
  },
];

function valueTone(value) {
  if (value === "-") {
    return "muted";
  }

  return value.startsWith("-") ? "negative" : "positive";
}

function renderProfitabilityCards() {
  const container = document.querySelector("#profitability-cards");

  profitabilityCards.forEach((card) => {
    const article = document.createElement("article");
    article.className = `profitability-card profitability-card--${card.tone}`;

    article.innerHTML = `
      <p class="profitability-card__label">${card.title}</p>
      <div class="profitability-card__body">
        <span class="profitability-card__value">${card.value}</span>
        <span class="profitability-card__trend" aria-hidden="true">${card.trend}</span>
      </div>
      <p class="profitability-card__note">${card.note}</p>
    `;

    container.append(article);
  });
}

function renderLegend() {
  const legend = document.querySelector("#comparison-legend");

  comparisonSeries.forEach((series) => {
    const item = document.createElement("li");
    item.innerHTML = `
      <span class="legend-dot" style="background: ${series.color}" aria-hidden="true"></span>
      <span>${series.label}</span>
    `;
    legend.append(item);
  });
}

function buildLinePath(values, width, height, min, max) {
  const step = width / (values.length - 1);

  return values
    .map((value, index) => {
      const x = index * step;
      const y = height - ((value - min) / (max - min)) * height;
      return `${index === 0 ? "M" : "L"} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");
}

function renderComparisonChart() {
  const chart = document.querySelector("#line-comparison");
  const visibleSeries = comparisonSeries.filter((series) => series.visible);
  const allValues = visibleSeries.flatMap((series) => series.values);
  const min = Math.min(...allValues, 0);
  const max = Math.max(...allValues);
  const width = 1040;
  const height = 350;
  const padding = 42;
  const gridLines = [0, 1, 2, 3, 4];

  const paths = visibleSeries
    .map((series) => {
      const path = buildLinePath(series.values, width, height, min, max);
      return `<path d="${path}" stroke="${series.color}" class="comparison-line"></path>`;
    })
    .join("");

  const grid = gridLines
    .map((line) => {
      const y = (height / (gridLines.length - 1)) * line;
      return `<line x1="0" x2="${width}" y1="${y}" y2="${y}" class="comparison-gridline"></line>`;
    })
    .join("");

  chart.innerHTML = `
    <svg viewBox="0 0 ${width + padding * 2} ${height + padding * 2}" role="img" aria-label="Carteira e CDI em rentabilidade acumulada">
      <g transform="translate(${padding} ${padding})">
        ${grid}
        ${paths}
      </g>
    </svg>
  `;
}

function renderReturnMatrix() {
  const table = document.querySelector("#return-matrix");

  returnRows.forEach((row) => {
    const tr = document.createElement("tr");
    const cells = row.values
      .map((value) => `<td class="return-${valueTone(value)}">${value}</td>`)
      .join("");
    tr.innerHTML = `
      <th scope="row">${row.year}</th>
      ${cells}
      <td class="return-${valueTone(row.annual)}">${row.annual}</td>
    `;
    table.append(tr);
  });
}

renderProfitabilityCards();
renderLegend();
renderComparisonChart();
renderReturnMatrix();
