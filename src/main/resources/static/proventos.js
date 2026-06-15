const proventsChartData = [
  { month: "06/25", received: 218, pending: 0 },
  { month: "07/25", received: 126, pending: 0 },
  { month: "08/25", received: 212, pending: 0 },
  { month: "09/25", received: 128, pending: 0 },
  { month: "10/25", received: 134, pending: 0 },
  { month: "11/25", received: 216, pending: 0 },
  { month: "12/25", received: 232, pending: 0 },
  { month: "01/26", received: 206, pending: 0 },
  { month: "02/26", received: 184, pending: 0 },
  { month: "03/26", received: 224, pending: 0 },
  { month: "04/26", received: 172, pending: 0 },
  { month: "05/26", received: 220, pending: 0 },
  { month: "06/26", received: 146, pending: 86 }
];

const distributionData = [
  { asset: "RZTR11", percent: "10,54%", color: "#5796f2" },
  { asset: "CPTS11", percent: "9,81%", color: "#43d7e5" },
  { asset: "MXRF11", percent: "8,42%", color: "#ffde70" },
  { asset: "HGLG11", percent: "7,36%", color: "#9c7af1" }
];

const monthlyHistory = [
  {
    year: "2026",
    values: ["R$ 180", "R$ 210", "R$ 166", "R$ 142", "R$ 196", "R$ 248", "-", "-", "-", "-", "-", "-"],
    average: "R$ 190",
    total: "R$ 1.142"
  },
  {
    year: "2025",
    values: ["R$ 126", "R$ 148", "R$ 134", "R$ 156", "R$ 188", "R$ 205", "R$ 122", "R$ 198", "R$ 172", "R$ 164", "R$ 202", "R$ 240"],
    average: "R$ 171",
    total: "R$ 2.055"
  },
  {
    year: "2024",
    values: ["R$ 72", "R$ 84", "R$ 98", "R$ 90", "R$ 110", "R$ 118", "R$ 120", "R$ 132", "R$ 126", "R$ 140", "R$ 152", "R$ 160"],
    average: "R$ 117",
    total: "R$ 1.402"
  }
];

const proventsRows = [
  {
    asset: "CMIG4",
    type: "A\u00e7\u00f5es",
    status: "A receber",
    payment: "JSCP",
    dateCom: "29/09/25",
    datePayment: "30/12/26",
    quantity: "120",
    unitValue: "R$ 0,41",
    total: "R$ 49,20",
    net: "R$ 41,82"
  },
  {
    asset: "MXRF11",
    type: "FIIs",
    status: "Recebido",
    payment: "Rendimento",
    dateCom: "30/05/26",
    datePayment: "14/06/26",
    quantity: "110",
    unitValue: "R$ 0,10",
    total: "R$ 11,00",
    net: "R$ 11,00"
  },
  {
    asset: "RZTR11",
    type: "FIIs",
    status: "Recebido",
    payment: "Rendimento",
    dateCom: "31/05/26",
    datePayment: "17/06/26",
    quantity: "86",
    unitValue: "R$ 1,08",
    total: "R$ 92,88",
    net: "R$ 92,88"
  },
  {
    asset: "PETR4",
    type: "A\u00e7\u00f5es",
    status: "Recebido",
    payment: "Dividendo",
    dateCom: "22/04/26",
    datePayment: "20/05/26",
    quantity: "64",
    unitValue: "R$ 0,58",
    total: "R$ 37,12",
    net: "R$ 37,12"
  },
  {
    asset: "CPTS11",
    type: "FIIs",
    status: "Recebido",
    payment: "Rendimento",
    dateCom: "29/04/26",
    datePayment: "15/05/26",
    quantity: "92",
    unitValue: "R$ 0,86",
    total: "R$ 79,12",
    net: "R$ 79,12"
  }
];

function renderProgress() {
  const progress = document.querySelector("#provents-progress");
  if (progress) {
    progress.style.width = "90.52%";
  }
}

function renderDistribution() {
  const container = document.querySelector("#provents-distribution");
  if (!container) {
    return;
  }

  const legend = distributionData
    .map((item) => `
      <li>
        <span class="provents-swatch" style="background:${item.color}"></span>
        <span>${item.asset}</span>
        <strong>${item.percent}</strong>
      </li>
    `)
    .join("");

  container.innerHTML = `
    <div class="provents-ring" aria-hidden="true">
      <svg viewBox="0 0 180 180" role="img" aria-label="Distribui\u00e7\u00e3o dos proventos por ativo">
        <g transform="rotate(-90 90 90)">
          <circle class="provents-ring__segment provents-ring__segment--blue" cx="90" cy="90" r="66"></circle>
          <circle class="provents-ring__segment provents-ring__segment--cyan" cx="90" cy="90" r="52"></circle>
          <circle class="provents-ring__segment provents-ring__segment--yellow" cx="90" cy="90" r="38"></circle>
          <circle class="provents-ring__segment provents-ring__segment--purple" cx="90" cy="90" r="24"></circle>
        </g>
        <circle class="provents-ring__hole" cx="90" cy="90" r="34"></circle>
      </svg>
    </div>
    <ul class="provents-distribution-list">${legend}</ul>
  `;
}

function renderChart() {
  const chart = document.querySelector("#provents-chart");
  if (!chart) {
    return;
  }

  const maxTotal = Math.max(...proventsChartData.map((item) => item.received + item.pending));

  chart.innerHTML = proventsChartData
    .map((item) => {
      const receivedHeight = `${Math.max((item.received / maxTotal) * 100, 6)}%`;
      const pendingHeight = item.pending > 0 ? `${Math.max((item.pending / maxTotal) * 100, 6)}%` : "0%";

      return `
        <div class="provents-chart-month">
          <div class="provents-bar-stack" title="${item.month}">
            ${item.pending > 0 ? `<span class="provents-bar provents-bar--pending" style="height:${pendingHeight}"></span>` : ""}
            <span class="provents-bar provents-bar--received" style="height:${receivedHeight}"></span>
          </div>
          <span class="provents-chart-label">${item.month}</span>
        </div>
      `;
    })
    .join("");
}

function renderMonthlyHistory() {
  const table = document.querySelector("#provents-history");
  if (!table) {
    return;
  }

  table.innerHTML = monthlyHistory
    .map((row) => `
      <tr>
        <th scope="row">${row.year}</th>
        ${row.values.map((value) => `<td class="${value === "-" ? "return-muted" : ""}">${value}</td>`).join("")}
        <td>${row.average}</td>
        <td>${row.total}</td>
      </tr>
    `)
    .join("");
}

function renderProventsTable() {
  const table = document.querySelector("#provents-table");
  if (!table) {
    return;
  }

  table.innerHTML = proventsRows
    .map((row) => `
      <tr>
        <th scope="row" class="asset-symbol">${row.asset}</th>
        <td>${row.type}</td>
        <td><span class="provents-status ${row.status === "Recebido" ? "provents-status--received" : "provents-status--pending"}">${row.status}</span></td>
        <td>${row.payment}</td>
        <td>${row.dateCom}</td>
        <td>${row.datePayment}</td>
        <td>${row.quantity}</td>
        <td>${row.unitValue}</td>
        <td>${row.total}</td>
        <td>${row.net}</td>
      </tr>
    `)
    .join("");
}

renderProgress();
renderDistribution();
renderChart();
renderMonthlyHistory();
renderProventsTable();
