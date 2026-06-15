const TOKEN_STORAGE_KEY = "valora.accessToken";
const REFRESH_TOKEN_STORAGE_KEY = "valora.refreshToken";

function setStatus(form, message, tone = "error") {
  const status = form.querySelector("[data-form-status]");
  if (!status) {
    return;
  }
  status.textContent = message;
  status.dataset.tone = tone;
}

function readValue(form, name) {
  return form.elements[name]?.value?.trim() ?? "";
}

function storeTokens(responseBody) {
  if (!responseBody.token || !responseBody.refreshToken) {
    throw new Error("Resposta de autenticacao incompleta.");
  }

  localStorage.setItem(TOKEN_STORAGE_KEY, responseBody.token);
  localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, responseBody.refreshToken);
}

async function submitJson(endpoint, payload) {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.message || "Nao foi possivel concluir a solicitacao.");
  }

  return body;
}

function bindLoginForm() {
  const form = document.querySelector("[data-auth-form='login']");
  if (!form) {
    return;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    setStatus(form, "Entrando...", "info");

    try {
      const body = await submitJson("/auth/login", {
        email: readValue(form, "email"),
        password: form.elements.password.value,
      });
      storeTokens(body);
      window.location.href = "/dashboard.html";
    } catch (error) {
      setStatus(form, error.message);
    }
  });
}

function bindRegisterForm() {
  const form = document.querySelector("[data-auth-form='register']");
  if (!form) {
    return;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    setStatus(form, "Criando conta...", "info");

    const password = form.elements.password.value;
    const passwordConfirmation = form.elements.passwordConfirmation.value;
    if (password !== passwordConfirmation) {
      setStatus(form, "As senhas nao conferem.");
      return;
    }

    try {
      const body = await submitJson("/auth/register", {
        name: readValue(form, "name"),
        email: readValue(form, "email"),
        password,
      });
      storeTokens(body);
      window.location.href = "/dashboard.html";
    } catch (error) {
      setStatus(form, error.message);
    }
  });
}

bindLoginForm();
bindRegisterForm();
