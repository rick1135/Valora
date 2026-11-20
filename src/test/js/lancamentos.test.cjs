const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");

const source = readFileSync(path.join(__dirname, "../../main/resources/static/lancamentos.js"), "utf8");
const settle = () => new Promise((resolve) => setImmediate(resolve));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

class Element {
  constructor() {
    this.hidden = true;
    this.disabled = false;
    this.dataset = {};
    this.children = [];
    this.listeners = new Map();
    this.value = "";
    this.textContent = "";
    this.files = [];
  }
  addEventListener(name, listener) {
    const listeners = this.listeners.get(name) || [];
    listeners.push(listener);
    this.listeners.set(name, listeners);
  }
  async dispatch(name, detail = {}) {
    for (const listener of this.listeners.get(name) || []) {
      await listener({ preventDefault() {}, ...detail });
    }
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  focus() {}
  remove() {}
}

function setup() {
  const elements = new Map();
  const timers = new Map();
  const blobs = new Map();
  const downloads = [];
  const windowEvents = new Element();
  let nextTimer = 0;
  let portfolioId = "A";
  let confirmRequest = async () => ({ importedCount: 1, skippedDuplicatesCount: 0 });
  let previewResponse = { validCount: 1, errorCount: 0, totalRows: 1, validRows: [], missingTickers: [], totalVolume: 10 };
  const el = (selector) => {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
  };
  const context = {
    Intl, Date, URLSearchParams, Blob,
    FormData: class { append() {} },
    URL: {
      createObjectURL(blob) { const url = `blob:${blobs.size}`; blobs.set(url, blob); return url; },
      revokeObjectURL(url) { blobs.delete(url); },
    },
    document: {
      querySelector: el,
      addEventListener() {},
      body: new Element(),
      createElement(tag) {
        const element = new Element();
        if (tag === "a") element.click = () => downloads.push({ name: element.download, blob: blobs.get(element.href) });
        return element;
      },
    },
    window: {
      location: { search: "", pathname: "/lancamentos.html" },
      history: { replaceState() {} },
      addEventListener: windowEvents.addEventListener.bind(windowEvents),
      setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
      clearTimeout(id) { timers.delete(id); },
      ValoraAPI: {
        getPortfolio: async () => ({ id: portfolioId }),
        async request(url) {
          if (url.startsWith("/transactions?")) {
            return { content: [], totalPages: 0, number: 0, first: true, last: true };
          }
          if (url.startsWith("/transactions/import/preview?")) return previewResponse;
          if (url.startsWith("/transactions/import?")) return confirmRequest();
          throw new Error(`Unexpected request: ${url}`);
        },
      },
    },
  };
  vm.runInNewContext(source, context);
  return {
    el, timers, downloads, ready: settle(),
    setConfirmRequest(request) { confirmRequest = request; },
    setPreviewResponse(response) { previewResponse = response; },
    async switchPortfolio(id) {
      portfolioId = id;
      await windowEvents.dispatch("valora:portfolio-changed", { detail: { portfolio: { id } } });
      await settle();
    },
    async openPreview() {
      await el("#import-csv-btn").dispatch("click");
      el("#csv-file-input").files = [{ name: "operations.csv", size: 100, lastModified: 1 }];
      await el("#csv-file-input").dispatch("change");
      await el("#csv-import-form").dispatch("submit");
    },
  };
}

test("an old confirmation cannot change or close a new portfolio's preview", async () => {
  const ui = setup();
  await ui.ready;
  await ui.openPreview();
  const request = deferred();
  ui.setConfirmRequest(() => request.promise);
  const confirmation = ui.el("#csv-confirm-btn").dispatch("click");
  await ui.el("#csv-import-close").dispatch("click");
  await ui.switchPortfolio("B");
  await ui.openPreview();
  const status = ui.el("#csv-import-status").textContent;

  request.resolve({ importedCount: 2, skippedDuplicatesCount: 0 });
  await confirmation;

  assert.equal(ui.el("#csv-import-status").textContent, status);
  assert.equal(ui.el("#csv-confirm-btn").disabled, false);
  assert.equal(ui.el("#csv-import-modal").hidden, false);
  assert.equal(ui.timers.size, 0);
});

test("an old failure cannot enable confirmation for a new invalid preview", async () => {
  const ui = setup();
  await ui.ready;
  await ui.openPreview();
  const request = deferred();
  ui.setConfirmRequest(() => request.promise);
  const confirmation = ui.el("#csv-confirm-btn").dispatch("click");
  await ui.el("#csv-import-close").dispatch("click");
  ui.setPreviewResponse({ validCount: 0, errorCount: 1, errors: [{ lineNumber: 2, message: "Invalid price" }] });
  await ui.openPreview();
  const status = ui.el("#csv-import-status").textContent;

  request.reject(new Error("Old request failed"));
  await confirmation;

  assert.equal(ui.el("#csv-import-status").textContent, status);
  assert.equal(ui.el("#csv-confirm-btn").disabled, true);
});

test("changing the file invalidates an in-flight confirmation", async () => {
  const ui = setup();
  await ui.ready;
  await ui.openPreview();
  const request = deferred();
  ui.setConfirmRequest(() => request.promise);
  const confirmation = ui.el("#csv-confirm-btn").dispatch("click");
  ui.el("#csv-file-input").files = [{ name: "other.csv", size: 200, lastModified: 2 }];
  await ui.el("#csv-file-input").dispatch("change");

  request.resolve({ importedCount: 1 });
  await confirmation;

  assert.equal(ui.el("#csv-confirm-btn").disabled, true);
  assert.equal(ui.el("#csv-preview").hidden, true);
  assert.equal(ui.timers.size, 0);
});

test("successful confirmation closes its own modal and cancels the timer on reopening", async () => {
  const ui = setup();
  await ui.ready;
  await ui.openPreview();
  await ui.el("#csv-confirm-btn").dispatch("click");
  assert.match(ui.el("#csv-import-status").textContent, /Importação concluída/);
  assert.equal(ui.timers.size, 1);
  const staleClose = [...ui.timers.values()][0];

  await ui.el("#csv-import-close").dispatch("click");
  await ui.openPreview();
  assert.equal(ui.timers.size, 0);
  staleClose();
  assert.equal(ui.el("#csv-import-modal").hidden, false);

  await ui.el("#csv-confirm-btn").dispatch("click");
  [...ui.timers.values()][0]();
  assert.equal(ui.el("#csv-import-modal").hidden, true);
});

test("a failure in the current session allows a safe retry", async () => {
  const ui = setup();
  await ui.ready;
  await ui.openPreview();
  ui.setConfirmRequest(async () => { throw new Error("Try again"); });
  await ui.el("#csv-confirm-btn").dispatch("click");

  assert.equal(ui.el("#csv-import-status").textContent, "Try again");
  assert.equal(ui.el("#csv-confirm-btn").disabled, false);
  assert.equal(ui.timers.size, 0);
});

test("the downloaded template contains purchases that do not require previous holdings", async () => {
  const ui = setup();
  await ui.ready;
  await ui.el("#csv-template-btn").dispatch("click");
  assert.equal(ui.downloads.length, 1);
  const download = ui.downloads[0];
  assert.equal(download.name, "modelo-valora.csv");
  const [header, ...rows] = (await download.blob.text()).replace(/^\uFEFF/, "").trim().split(/\r?\n/);
  assert.equal(header, "data;tipo;codigo_acao;quantidade;preco_unitario");
  assert.ok(rows.length > 0);
  for (const row of rows) {
    const [date, type, ticker, quantity, price] = row.split(";");
    assert.match(date, /^\d{2}\/\d{2}\/\d{4}$/);
    assert.equal(type, "Compra");
    assert.ok(ticker);
    assert.ok(Number(quantity) > 0);
    assert.ok(Number(price.replace(",", ".")) > 0);
  }
});
