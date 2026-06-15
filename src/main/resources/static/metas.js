const goalsSummary = [
  {
    label: "Total em metas",
    value: "R$ 38.500,00",
    note: "Soma de objetivos cadastrados",
    tone: "neutral"
  },
  {
    label: "Em andamento",
    value: "4 metas",
    note: "R$ 19.850,00 acumulados",
    tone: "blue"
  },
  {
    label: "Conclu\u00eddas",
    value: "2 metas",
    note: "R$ 7.200,00 realizados",
    tone: "green"
  },
  {
    label: "Prazo cr\u00edtico",
    value: "1 meta",
    note: "A\u00e7\u00e3o sugerida neste m\u00eas",
    tone: "yellow"
  }
];

const activeGoals = [
  {
    name: "Reserva de emerg\u00eancia",
    category: "Seguran\u00e7a",
    deadline: "Dez/26",
    current: "R$ 8.400",
    target: "R$ 12.000",
    progress: 70,
    tone: "green"
  },
  {
    name: "Aporte em FIIs",
    category: "Renda passiva",
    deadline: "Jun/27",
    current: "R$ 4.250",
    target: "R$ 10.000",
    progress: 43,
    tone: "blue"
  },
  {
    name: "Comprar notebook",
    category: "Equipamento",
    deadline: "Nov/26",
    current: "R$ 3.200",
    target: "R$ 6.500",
    progress: 49,
    tone: "yellow"
  },
  {
    name: "Viagem Espanha",
    category: "Experi\u00eancia",
    deadline: "Jul/27",
    current: "R$ 4.000",
    target: "R$ 10.000",
    progress: 40,
    tone: "purple"
  }
];

const evolution = [
  { month: "Jul", value: 18 },
  { month: "Ago", value: 24 },
  { month: "Set", value: 32 },
  { month: "Out", value: 38 },
  { month: "Nov", value: 44 },
  { month: "Dez", value: 52 },
  { month: "Jan", value: 58 },
  { month: "Fev", value: 64 },
  { month: "Mar", value: 70 },
  { month: "Abr", value: 76 },
  { month: "Mai", value: 82 },
  { month: "Jun", value: 88 }
];

const completedGoals = [
  {
    name: "Curso Java Spring",
    category: "Educa\u00e7\u00e3o",
    value: "R$ 1.200",
    completedAt: "Conclu\u00edda em Abr/26"
  },
  {
    name: "Placa de v\u00eddeo",
    category: "Setup",
    value: "R$ 6.000",
    completedAt: "Conclu\u00edda em Jan/26"
  },
  {
    name: "Primeira carteira",
    category: "Investimentos",
    value: "R$ 5.000",
    completedAt: "Conclu\u00edda em Dez/25"
  }
];

function renderGoalsSummary() {
  const grid = document.querySelector("#goals-summary-grid");
  if (!grid) {
    return;
  }

  grid.innerHTML = goalsSummary
    .map((item) => `
      <article class="goals-summary-card goals-summary-card--${item.tone}">
        <p>${item.label}</p>
        <strong>${item.value}</strong>
        <span>${item.note}</span>
      </article>
    `)
    .join("");
}

function renderActiveGoals() {
  const grid = document.querySelector("#goals-card-grid");
  if (!grid) {
    return;
  }

  grid.innerHTML = activeGoals
    .map((goal) => `
      <article class="goal-progress-card goal-progress-card--${goal.tone}">
        <div class="goal-progress-card__top">
          <h3>${goal.name}</h3>
          <span>${goal.deadline}</span>
        </div>
        <p>${goal.category}</p>
        <strong>${goal.current} / ${goal.target}</strong>
        <div class="goal-progress-card__progress">
          <span style="width:${goal.progress}%"></span>
        </div>
        <em>${goal.progress}%</em>
      </article>
    `)
    .join("");
}

function renderEvolution() {
  const chart = document.querySelector("#goals-evolution-chart");
  if (!chart) {
    return;
  }

  chart.innerHTML = evolution
    .map((item) => `
      <div class="goals-evolution-month">
        <span style="height:${item.value}%"></span>
        <strong>${item.month}</strong>
      </div>
    `)
    .join("");
}

function renderCompletedGoals() {
  const list = document.querySelector("#goals-completed-list");
  if (!list) {
    return;
  }

  list.innerHTML = completedGoals
    .map((goal) => `
      <article class="completed-goal-card">
        <span class="completed-goal-card__check" aria-hidden="true">&#10003;</span>
        <div>
          <h3>${goal.name}</h3>
          <p>${goal.category}</p>
        </div>
        <div>
          <strong>${goal.value}</strong>
          <em>${goal.completedAt}</em>
        </div>
      </article>
    `)
    .join("");
}

renderGoalsSummary();
renderActiveGoals();
renderEvolution();
renderCompletedGoals();
