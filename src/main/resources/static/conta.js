(function () {
  const api = window.ValoraAPI;
  const error = document.querySelector("#account-error");
  const success = document.querySelector("#account-success");
  const profileForm = document.querySelector("#profile-form");
  const passwordForm = document.querySelector("#password-form");
  const deleteForm = document.querySelector("#delete-form");

  function feedback(message, isSuccess = false) {
    error.hidden = isSuccess || !message;
    success.hidden = !isSuccess || !message;
    (isSuccess ? success : error).textContent = message;
  }

  function formatDate(value) {
    if (!value) return "Não informado";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : new Intl.DateTimeFormat("pt-BR", { dateStyle: "long" }).format(date);
  }

  async function loadAccount() {
    try {
      const account = await api.request("/account/me");
      document.querySelector("#account-email").textContent = account.email || "Não informado";
      document.querySelector("#account-created").textContent = formatDate(account.createdAt || account.createdDate || account.createdOn);
      document.querySelector("#account-name").value = account.name || "";
    } catch (requestError) {
      feedback(requestError.message);
    }
  }

  profileForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    feedback("");
    try {
      await api.request("/account/profile", { method: "PUT", body: { name: profileForm.elements.name.value.trim() } });
      feedback("Perfil atualizado com sucesso.", true);
    } catch (requestError) { feedback(requestError.message); }
  });

  passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    feedback("");
    const currentPassword = passwordForm.elements.currentPassword.value;
    const newPassword = passwordForm.elements.newPassword.value;
    const confirmPassword = passwordForm.elements.confirmPassword.value;
    if (newPassword !== confirmPassword) { feedback("A confirmação da senha não corresponde."); return; }
    try {
      await api.request("/account/password", { method: "PUT", body: { currentPassword, newPassword } });
      feedback("Senha alterada com sucesso. Faça login novamente.", true);
      api.clearSession();
      window.setTimeout(() => window.location.replace("/login.html"), 900);
    } catch (requestError) { feedback(requestError.message); }
  });

  deleteForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    feedback("");
    if (!document.querySelector("#delete-confirm").checked) { feedback("Confirme a exclusão da conta para continuar."); return; }
    try {
      await api.request("/account", { method: "DELETE", body: { currentPassword: deleteForm.elements.currentPassword.value } });
      api.clearSession();
      window.location.replace("/login.html");
    } catch (requestError) { feedback(requestError.message); }
  });

  document.querySelector("#logout-btn").addEventListener("click", async (event) => {
    event.currentTarget.disabled = true;
    try { await api.logout(); } catch { window.location.replace("/login.html"); }
  });

  loadAccount();
})();
