(function (global) {
  const TOKEN_STORAGE_KEY = "valora.accessToken";
  const REFRESH_TOKEN_STORAGE_KEY = "valora.refreshToken";
  const ACTIVE_PORTFOLIO_ID_KEY = "valora.activePortfolioId";
  const LOGIN_PATH = "/login.html";
  const DEFAULT_PORTFOLIO_NAME = "Principal";

  let refreshPromise = null;
  let portfolioPromise = null;

  function getAccessToken() {
    return global.localStorage.getItem(TOKEN_STORAGE_KEY);
  }

  function getRefreshToken() {
    return global.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
  }

  function setTokens(tokens) {
    if (!tokens || !tokens.token || !tokens.refreshToken) {
      throw new Error("Resposta de autenticacao incompleta.");
    }
    global.localStorage.setItem(TOKEN_STORAGE_KEY, tokens.token);
    global.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, tokens.refreshToken);
  }

  function clearSession() {
    global.localStorage.removeItem(TOKEN_STORAGE_KEY);
    global.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    global.localStorage.removeItem(ACTIVE_PORTFOLIO_ID_KEY);
    portfolioPromise = null;
  }

  function redirectToLogin() {
    if (global.location.pathname !== LOGIN_PATH) {
      global.location.replace(LOGIN_PATH);
    }
  }

  function isPlainJsonBody(body) {
    return (
      body !== undefined &&
      body !== null &&
      typeof body === "object" &&
      !(body instanceof FormData) &&
      !(body instanceof Blob) &&
      !(body instanceof URLSearchParams) &&
      !(body instanceof ArrayBuffer)
    );
  }

  async function parseResponseBody(response) {
    if (response.status === 204) {
      return null;
    }

    const text = await response.text();
    if (!text) {
      return null;
    }

    try {
      return JSON.parse(text);
    } catch {
      return { message: text };
    }
  }

  async function refreshAccessToken(failedToken) {
    if (refreshPromise) {
      return refreshPromise;
    }

    const currentToken = getAccessToken();
    if (failedToken && currentToken && currentToken !== failedToken) {
      return currentToken;
    }

    refreshPromise = (async () => {
      const refreshToken = getRefreshToken();
      if (!refreshToken) {
        throw new Error("Sessao expirada. Faca login novamente.");
      }

      const response = await global.fetch("/auth/refresh", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ refreshToken }),
      });

      const data = await parseResponseBody(response);
      if (!response.ok) {
        throw new Error(data?.message || "Sessao expirada. Faca login novamente.");
      }

      setTokens(data);
      return data.token;
    })();

    try {
      return await refreshPromise;
    } catch (error) {
      clearSession();
      redirectToLogin();
      throw error;
    } finally {
      refreshPromise = null;
    }
  }

  function buildFetchOptions(options, token) {
    const headers = new Headers(options.headers || {});
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }

    let body = options.body;
    if (isPlainJsonBody(body)) {
      body = JSON.stringify(body);
      if (!headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
      }
    } else if (typeof body === "string" && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }

    const fetchOptions = {
      ...options,
      headers,
    };

    if (body !== undefined) {
      fetchOptions.body = body;
    }

    return fetchOptions;
  }

  async function request(path, options = {}) {
    let token = getAccessToken();

    if (!token) {
      if (!getRefreshToken()) {
        clearSession();
        redirectToLogin();
        throw new Error("Sessao expirada. Faca login novamente.");
      }
      token = await refreshAccessToken();
    }

    let response = await global.fetch(path, buildFetchOptions(options, token));

    if (response.status === 401) {
      const refreshedToken = await refreshAccessToken(token);
      response = await global.fetch(path, buildFetchOptions(options, refreshedToken));

      if (response.status === 401) {
        clearSession();
        redirectToLogin();
        throw new Error("Sessao expirada. Faca login novamente.");
      }
    }

    const data = await parseResponseBody(response);

    if (!response.ok) {
      const error = new Error(data?.message || data?.error || `Erro HTTP ${response.status}`);
      error.status = response.status;
      error.data = data;
      throw error;
    }

    return data;
  }

  function selectPortfolio(portfolios) {
    if (!Array.isArray(portfolios) || portfolios.length === 0) {
      return null;
    }

    const savedId = global.localStorage.getItem(ACTIVE_PORTFOLIO_ID_KEY);
    if (savedId) {
      const saved = portfolios.find((portfolio) => portfolio.id === savedId);
      if (saved) {
        return saved;
      }
    }

    const principal = portfolios.find(
      (portfolio) => portfolio.name?.trim().toLowerCase() === DEFAULT_PORTFOLIO_NAME.toLowerCase()
    );
    return principal || portfolios[0];
  }

  function listPortfolios() {
    return request("/portfolios");
  }

  function saveActivePortfolio(portfolio) {
    if (portfolio?.id) {
      global.localStorage.setItem(ACTIVE_PORTFOLIO_ID_KEY, portfolio.id);
    } else {
      global.localStorage.removeItem(ACTIVE_PORTFOLIO_ID_KEY);
    }
  }

  function setActivePortfolio(portfolio) {
    saveActivePortfolio(portfolio);
    portfolioPromise = null;
    global.dispatchEvent(new CustomEvent("valora:portfolio-changed", {
      detail: { portfolio },
    }));
  }

  async function createPortfolio(payload) {
    const portfolio = await request("/portfolios", { method: "POST", body: payload });
    setActivePortfolio(portfolio);
    return portfolio;
  }

  async function updatePortfolio(id, payload) {
    const portfolio = await request(`/portfolios/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: payload,
    });
    setActivePortfolio(portfolio);
    return portfolio;
  }

  async function deletePortfolio(id) {
    const portfolios = await listPortfolios();
    await request(`/portfolios/${encodeURIComponent(id)}`, { method: "DELETE" });
    setActivePortfolio(selectPortfolio(portfolios.filter((portfolio) => portfolio.id !== id)));
  }

  async function getPortfolio(options = {}) {
    if (options.forceRefresh) {
      portfolioPromise = null;
    }

    if (portfolioPromise) {
      return portfolioPromise;
    }

    const pending = listPortfolios().then((portfolios) => {
      const portfolio = selectPortfolio(portfolios);
      if (portfolioPromise === pending) {
        saveActivePortfolio(portfolio);
      }

      return portfolio;
    });
    portfolioPromise = pending;

    try {
      return await pending;
    } catch (error) {
      if (portfolioPromise === pending) {
        portfolioPromise = null;
      }
      throw error;
    }
  }

  async function logout() {
    const refreshToken = getRefreshToken();
    let revocationError = null;

    try {
      if (refreshToken) {
        const response = await global.fetch("/auth/logout", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ refreshToken }),
        });

        if (!response.ok) {
          const data = await parseResponseBody(response);
          const error = new Error(
            data?.message ||
              data?.error ||
              `Falha ao revogar sessao no servidor (HTTP ${response.status}).`
          );
          error.status = response.status;
          error.data = data;
          revocationError = error;
        }
      }
    } catch {
      // Limpa a sessao local mesmo se o servidor nao responder.
    } finally {
      clearSession();
    }

    if (revocationError) {
      throw revocationError;
    }

    redirectToLogin();
  }

  global.ValoraAPI = {
    request,
    getPortfolio,
    listPortfolios,
    setActivePortfolio,
    createPortfolio,
    updatePortfolio,
    deletePortfolio,
    logout,
    getAccessToken,
    getRefreshToken,
    setTokens,
    clearSession,
  };
})(window);
