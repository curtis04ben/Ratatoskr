(() => {
  "use strict";

  const API = "/api/v1";
  const state = {
    token: sessionStorage.getItem("rk_token") || null,
    role: sessionStorage.getItem("rk_role") || null,
    username: sessionStorage.getItem("rk_username") || null,
    entries: [],
    editingId: null,
    editingCanWrite: false,
    sharingEntryId: null,
  };

  const $ = (id) => document.getElementById(id);
  const show = (el) => el.classList.remove("hidden");
  const hide = (el) => el.classList.add("hidden");

  function toast(msg) {
    const el = $("toast");
    el.textContent = msg;
    show(el);
    clearTimeout(toast._t);
    toast._t = setTimeout(() => hide(el), 2400);
  }

  async function api(path, { method = "GET", body, auth = true } = {}) {
    const headers = { "Content-Type": "application/json" };
    if (auth && state.token) headers["Authorization"] = `Bearer ${state.token}`;
    const res = await fetch(API + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (res.status === 401) {
      clearSession();
      showLockScreen();
      throw new Error("Session expired, unlock again");
    }
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || `Request failed (${res.status})`);
    return data;
  }

  function setSession(token, username, role) {
    state.token = token;
    state.username = username;
    state.role = role;
    sessionStorage.setItem("rk_token", token);
    sessionStorage.setItem("rk_username", username);
    sessionStorage.setItem("rk_role", role);
  }

  function clearSession() {
    state.token = null;
    state.username = null;
    state.role = null;
    sessionStorage.removeItem("rk_token");
    sessionStorage.removeItem("rk_username");
    sessionStorage.removeItem("rk_role");
  }

  // ---------- screens ----------
  function showLockScreen() {
    hide($("app-screen"));
    show($("lock-screen"));
    for (const f of ["setup-form", "unlock-form", "invite-form", "reset-form"]) hide($(f));
  }

  function showAppScreen() {
    hide($("lock-screen"));
    show($("app-screen"));
    $("role-badge").textContent = state.role;
    const isVisitor = state.role === "visitor";
    const isAdmin = state.role === "admin";
    $("new-entry-btn").classList.toggle("hidden", isVisitor);
    $("import-btn").classList.toggle("hidden", isVisitor);
    $("open-users").classList.toggle("hidden", !isAdmin);
    loadEntries();
  }

  async function boot() {
    try {
      const { initialized } = await api("/auth/status", { auth: false });
      hide($("status-loading"));
      if (!initialized) {
        show($("setup-form"));
        return;
      }
      if (state.token) {
        try {
          await api("/vault");
          showAppScreen();
          return;
        } catch {
          /* fall through to unlock */
        }
      }
      show($("unlock-form"));
    } catch (err) {
      hide($("status-loading"));
      toast(err.message);
    }
  }

  // ---------- setup / unlock / invite / reset ----------
  $("setup-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const username = $("setup-username").value.trim();
    const pw = $("setup-password").value, confirm = $("setup-password-confirm").value;
    const errEl = $("setup-error");
    errEl.textContent = "";
    if (pw !== confirm) { errEl.textContent = "Passwords don't match."; return; }
    try {
      const r = await api("/auth/setup", { method: "POST", body: { username, master_password: pw }, auth: false });
      setSession(r.token, r.username, r.role);
      showAppScreen();
    } catch (err) { errEl.textContent = err.message; }
  });

  $("unlock-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const username = $("unlock-username").value.trim();
    const pw = $("unlock-password").value;
    const errEl = $("unlock-error");
    errEl.textContent = "";
    try {
      const r = await api("/auth/unlock", { method: "POST", body: { username, master_password: pw }, auth: false });
      setSession(r.token, r.username, r.role);
      $("unlock-password").value = "";
      showAppScreen();
    } catch (err) { errEl.textContent = err.message; }
  });

  $("show-invite").addEventListener("click", () => { hide($("unlock-form")); show($("invite-form")); });
  $("cancel-invite").addEventListener("click", () => { hide($("invite-form")); show($("unlock-form")); });
  $("show-reset").addEventListener("click", () => { hide($("unlock-form")); show($("reset-form")); });
  $("cancel-reset").addEventListener("click", () => { hide($("reset-form")); show($("unlock-form")); });

  $("invite-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const errEl = $("invite-error");
    errEl.textContent = "";
    try {
      const r = await api("/auth/accept-invite", {
        method: "POST",
        auth: false,
        body: {
          username: $("invite-username").value.trim(),
          invite_token: $("invite-token").value.trim(),
          master_password: $("invite-password").value,
        },
      });
      setSession(r.token, r.username, r.role);
      showAppScreen();
    } catch (err) { errEl.textContent = err.message; }
  });

  $("do-factory-reset").addEventListener("click", async () => {
    const errEl = $("reset-error");
    errEl.textContent = "";
    if (!confirm("This permanently deletes every account and every saved entry, for everyone. Continue?")) return;
    try {
      await api("/auth/factory-reset", { method: "POST", body: { confirm_wipe: true }, auth: false });
      clearSession();
      location.reload();
    } catch (err) { errEl.textContent = err.message; }
  });

  $("lock-btn").addEventListener("click", async () => {
    try { await api("/auth/lock", { method: "POST" }); } catch { /* ignore */ }
    clearSession();
    location.reload();
  });

  // ---------- entry list ----------
  async function loadEntries() {
    try {
      state.entries = await api("/vault");
      renderEntries();
    } catch (err) { toast(err.message); }
  }

  function renderEntries() {
    const list = $("entry-list");
    const query = $("search").value.trim().toLowerCase();
    const filtered = state.entries.filter((e) =>
      !query || e.site.toLowerCase().includes(query) || e.username.toLowerCase().includes(query)
    );
    list.innerHTML = "";
    $("empty-state").classList.toggle("hidden", state.entries.length !== 0);

    const isAdmin = state.role === "admin";
    for (const entry of filtered) {
      const li = document.createElement("li");
      li.className = "ledger-row";
      li.tabIndex = 0;

      const isMine = entry.owner_username === state.username;
      const tags = [];
      if (isAdmin && !isMine) tags.push(`<span class="row-tag">${entry.owner_username}</span>`);
      else if (!isMine) tags.push(`<span class="row-tag owner">shared</span>`);
      if (!entry.can_write) tags.push(`<span class="row-tag readonly">read-only</span>`);
      if (entry.totp_secret) tags.push(`<span class="row-tag totp">2FA</span>`);

      li.innerHTML = `
        <div class="row-main">
          <div class="row-site"><span class="row-site-text"></span>${tags.join("")}</div>
          <div class="row-user"></div>
        </div>`;
      li.querySelector(".row-site-text").textContent = entry.site;
      li.querySelector(".row-user").textContent = entry.username || "\u2014";
      li.addEventListener("click", () => openEntryModal(entry));
      list.appendChild(li);
    }
  }

  $("search").addEventListener("input", renderEntries);

  // ---------- entry editor ----------
  let totpTimer = null;

  function stopTotpTimer() {
    if (totpTimer) { clearInterval(totpTimer); totpTimer = null; }
  }

  function tickTotpDisplay(secret) {
    if (!secret || !window.RatatoskrTOTP || !RatatoskrTOTP.isValidBase32(secret)) {
      hide($("entry-totp-display"));
      return;
    }
    const code = RatatoskrTOTP.totp(secret);
    const remaining = RatatoskrTOTP.secondsRemaining();
    $("entry-totp-code").textContent = code.slice(0, 3) + " " + code.slice(3);
    const circumference = 2 * Math.PI * 15.5;
    const fraction = remaining / 30;
    const ring = $("entry-totp-ring-progress");
    ring.style.strokeDasharray = `${circumference * fraction} ${circumference}`;
    ring.classList.toggle("low", remaining <= 5);
    show($("entry-totp-display"));
  }

  function startTotpTimer(secret) {
    stopTotpTimer();
    tickTotpDisplay(secret);
    totpTimer = setInterval(() => tickTotpDisplay($("entry-totp-secret").value.trim()), 1000);
  }

  $("entry-2fa-enabled").addEventListener("change", () => {
    const enabled = $("entry-2fa-enabled").checked;
    $("entry-2fa-fields").classList.toggle("hidden", !enabled);
    if (!enabled) {
      $("entry-totp-secret").value = "";
      stopTotpTimer();
      hide($("entry-totp-display"));
    } else {
      $("entry-totp-secret").focus();
    }
  });

  $("entry-totp-secret").addEventListener("input", () => {
    const secret = $("entry-totp-secret").value.trim();
    if (secret && RatatoskrTOTP.isValidBase32(secret)) {
      startTotpTimer(secret);
    } else {
      stopTotpTimer();
      hide($("entry-totp-display"));
    }
  });

  function openEntryModal(entry) {
    state.editingId = entry ? entry.id : null;
    state.editingCanWrite = entry ? entry.can_write : true;
    $("entry-modal-title").textContent = entry ? "Edit entry" : "New entry";
    $("entry-id").value = entry ? entry.id : "";
    $("entry-site").value = entry ? entry.site : "";
    $("entry-username").value = entry ? entry.username : "";
    $("entry-url").value = entry ? entry.url : "";
    $("entry-password").value = entry ? entry.password : "";
    $("entry-notes").value = entry ? entry.notes : "";
    $("entry-error").textContent = "";

    const hasTotp = !!(entry && entry.totp_secret);
    $("entry-2fa-enabled").checked = hasTotp;
    $("entry-2fa-fields").classList.toggle("hidden", !hasTotp);
    $("entry-totp-secret").value = hasTotp ? entry.totp_secret : "";
    stopTotpTimer();
    if (hasTotp) startTotpTimer(entry.totp_secret);
    else hide($("entry-totp-display"));

    const readOnly = entry && !entry.can_write;
    for (const id of ["entry-site", "entry-username", "entry-url", "entry-password", "entry-notes", "entry-totp-secret", "entry-2fa-enabled"]) {
      $(id).disabled = !!readOnly;
    }
    $("entry-generate-btn").classList.toggle("hidden", !!readOnly);
    $("entry-save-btn").classList.toggle("hidden", !!readOnly);
    $("entry-delete-btn").classList.toggle("hidden", !entry || !entry.can_write);
    $("entry-share-btn").classList.toggle("hidden", !entry || (state.role !== "admin" && entry.owner_username !== state.username));

    $("entry-meta").textContent = entry
      ? `Owner: ${entry.owner_username}${entry.shared ? " \u00b7 shared with others" : ""}`
      : "";

    show($("entry-modal"));
    if (!readOnly) $("entry-site").focus();
  }

  function closeEntryModal() { stopTotpTimer(); hide($("entry-modal")); }

  // ---------- CSV export / import ----------
  $("export-btn").addEventListener("click", () => {
    $("export-ack-checkbox").checked = false;
    $("export-confirm-btn").disabled = true;
    $("export-error").textContent = "";
    show($("export-modal"));
  });

  $("export-ack-checkbox").addEventListener("change", () => {
    $("export-confirm-btn").disabled = !$("export-ack-checkbox").checked;
  });

  $("export-cancel-btn").addEventListener("click", () => hide($("export-modal")));

  $("export-confirm-btn").addEventListener("click", async () => {
    $("export-error").textContent = "";
    try {
      const res = await fetch(`${API}/vault/export`, {
        headers: { Authorization: `Bearer ${state.token}` },
      });
      if (!res.ok) throw new Error(`Export failed (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `ratatoskr-export-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      hide($("export-modal"));
      toast("Exported \u2014 remember to delete the file once you're done with it");
    } catch (err) { $("export-error").textContent = err.message; }
  });

  $("import-btn").addEventListener("click", () => $("import-file-input").click());

  $("import-file-input").addEventListener("change", async () => {
    const file = $("import-file-input").files[0];
    $("import-file-input").value = "";
    if (!file) return;
    if (!confirm(`Import entries from "${file.name}"? This adds new entries, it doesn't overwrite existing ones.`)) return;

    const formData = new FormData();
    formData.append("file", file);
    try {
      const res = await fetch(`${API}/vault/import`, {
        method: "POST",
        headers: { Authorization: `Bearer ${state.token}` },
        body: formData,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.detail || `Import failed (${res.status})`);
      let msg = `Imported ${data.imported} entr${data.imported === 1 ? "y" : "ies"}`;
      if (data.skipped) msg += `, skipped ${data.skipped} blank row${data.skipped === 1 ? "" : "s"}`;
      if (data.errors && data.errors.length) msg += `, ${data.errors.length} error(s) \u2014 see console`;
      toast(msg);
      if (data.errors && data.errors.length) console.warn("Import errors:", data.errors);
      await loadEntries();
    } catch (err) { toast(err.message); }
  });

  $("new-entry-btn").addEventListener("click", () => openEntryModal(null));
  $("entry-cancel-btn").addEventListener("click", closeEntryModal);

  $("entry-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const totpEnabled = $("entry-2fa-enabled").checked;
    const totpSecret = totpEnabled ? $("entry-totp-secret").value.trim() : "";
    if (totpEnabled && !RatatoskrTOTP.isValidBase32(totpSecret)) {
      $("entry-error").textContent = "That 2FA secret doesn't look valid \u2014 double check you copied the base32 key, not the 6-digit code.";
      return;
    }
    const body = {
      site: $("entry-site").value.trim(),
      username: $("entry-username").value.trim(),
      url: $("entry-url").value.trim(),
      password: $("entry-password").value,
      totp_secret: totpSecret,
      notes: $("entry-notes").value,
    };
    try {
      if (state.editingId) {
        await api(`/vault/${state.editingId}`, { method: "PUT", body });
      } else {
        await api("/vault", { method: "POST", body });
      }
      closeEntryModal();
      await loadEntries();
      toast("Saved");
    } catch (err) { $("entry-error").textContent = err.message; }
  });

  $("entry-delete-btn").addEventListener("click", async () => {
    if (!state.editingId) return;
    if (!confirm("Delete this entry? This can't be undone.")) return;
    try {
      await api(`/vault/${state.editingId}`, { method: "DELETE" });
      closeEntryModal();
      await loadEntries();
      toast("Deleted");
    } catch (err) { $("entry-error").textContent = err.message; }
  });

  $("entry-generate-btn").addEventListener("click", async () => {
    try {
      const { password } = await api("/generate", { method: "POST", body: defaultGenOptions() });
      $("entry-password").value = password;
    } catch (err) { toast(err.message); }
  });

  // ---------- share modal ----------
  $("entry-share-btn").addEventListener("click", async () => {
    state.sharingEntryId = state.editingId;
    await loadShares();
    show($("share-modal"));
  });

  async function loadShares() {
    $("share-error").textContent = "";
    try {
      const shares = await api(`/vault/${state.sharingEntryId}/shares`);
      const list = $("share-list");
      list.innerHTML = "";
      for (const s of shares) {
        const li = document.createElement("li");
        li.innerHTML = `<span class="s-name"></span><span class="s-role"></span>`;
        li.querySelector(".s-name").textContent = `${s.username}${s.can_write ? "" : " (read-only)"}`;
        li.querySelector(".s-role").textContent = s.role;
        if (s.username !== state.username) {
          const revoke = document.createElement("button");
          revoke.type = "button";
          revoke.className = "btn-ghost small";
          revoke.textContent = "Remove";
          revoke.addEventListener("click", async () => {
            try {
              await api(`/vault/${state.sharingEntryId}/shares/${encodeURIComponent(s.username)}`, { method: "DELETE" });
              await loadShares();
              await loadEntries();
            } catch (err) { $("share-error").textContent = err.message; }
          });
          li.appendChild(revoke);
        }
        list.appendChild(li);
      }
    } catch (err) { $("share-error").textContent = err.message; }
  }

  $("share-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const username = $("share-username").value.trim();
    const canWrite = $("share-can-write").checked;
    $("share-error").textContent = "";
    try {
      await api(`/vault/${state.sharingEntryId}/shares`, { method: "POST", body: { username, can_write: canWrite } });
      $("share-username").value = "";
      $("share-can-write").checked = false;
      await loadShares();
      await loadEntries();
      toast("Shared");
    } catch (err) { $("share-error").textContent = err.message; }
  });

  $("share-close-btn").addEventListener("click", () => hide($("share-modal")));

  // ---------- users (admin) modal ----------
  $("open-users").addEventListener("click", async () => {
    await loadUsers();
    hide($("invite-result"));
    show($("users-modal"));
  });
  $("users-close-btn").addEventListener("click", () => hide($("users-modal")));

  async function loadUsers() {
    $("users-error").textContent = "";
    try {
      const users = await api("/users");
      const list = $("users-list");
      list.innerHTML = "";
      for (const u of users) {
        const li = document.createElement("li");
        li.innerHTML = `<span class="s-name"></span>`;
        li.querySelector(".s-name").textContent = u.username;

        const roleSelect = document.createElement("select");
        for (const r of ["user", "visitor", "admin"]) {
          const opt = document.createElement("option");
          opt.value = r; opt.textContent = r;
          if (r === u.role) opt.selected = true;
          roleSelect.appendChild(opt);
        }
        roleSelect.disabled = u.username === state.username;
        roleSelect.addEventListener("change", async () => {
          try {
            await api(`/users/${u.id}/role`, { method: "PATCH", body: { role: roleSelect.value } });
            toast(`${u.username} is now ${roleSelect.value}`);
          } catch (err) { $("users-error").textContent = err.message; await loadUsers(); }
        });
        li.appendChild(roleSelect);

        if (u.username !== state.username) {
          const del = document.createElement("button");
          del.type = "button";
          del.className = "btn-ghost small";
          del.textContent = "Delete";
          del.addEventListener("click", async () => {
            if (!confirm(`Delete ${u.username}? This also deletes entries only they could access.`)) return;
            try {
              await api(`/users/${u.id}`, { method: "DELETE" });
              await loadUsers();
              await loadEntries();
            } catch (err) { $("users-error").textContent = err.message; }
          });
          li.appendChild(del);
        }
        list.appendChild(li);
      }
    } catch (err) { $("users-error").textContent = err.message; }
  }

  $("invite-create-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("users-error").textContent = "";
    try {
      const r = await api("/users/invite", {
        method: "POST",
        body: { username: $("invite-new-username").value.trim(), role: $("invite-new-role").value },
      });
      $("invite-result-token").textContent = r.token;
      show($("invite-result"));
      $("invite-new-username").value = "";
      await loadUsers();
    } catch (err) { $("users-error").textContent = err.message; }
  });

  // ---------- generator ----------
  function defaultGenOptions() {
    return {
      length: Number($("gen-length").value),
      use_upper: $("gen-upper").checked,
      use_lower: $("gen-lower").checked,
      use_digits: $("gen-digits").checked,
      use_symbols: $("gen-symbols").checked,
      avoid_ambiguous: $("gen-ambiguous").checked,
    };
  }

  async function regenerate() {
    $("generator-error").textContent = "";
    try {
      const { password } = await api("/generate", { method: "POST", body: defaultGenOptions() });
      $("generated-value").textContent = password;
    } catch (err) { $("generator-error").textContent = err.message; }
  }

  $("open-generator").addEventListener("click", () => { show($("generator-modal")); regenerate(); });
  $("generator-close-btn").addEventListener("click", () => hide($("generator-modal")));
  $("generator-regenerate").addEventListener("click", regenerate);
  $("gen-length").addEventListener("input", () => {
    $("gen-length-value").textContent = $("gen-length").value;
    regenerate();
  });
  for (const id of ["gen-upper", "gen-lower", "gen-digits", "gen-symbols", "gen-ambiguous"]) {
    $(id).addEventListener("change", regenerate);
  }
  $("copy-generated").addEventListener("click", async () => {
    const text = $("generated-value").textContent;
    if (!text.trim()) return;
    await navigator.clipboard.writeText(text);
    toast("Copied to clipboard");
  });

  // close modals on backdrop click / Escape
  for (const id of ["entry-modal", "generator-modal", "share-modal", "users-modal", "export-modal"]) {
    $(id).addEventListener("click", (e) => {
      if (e.target !== $(id)) return;
      if (id === "entry-modal") closeEntryModal(); else hide($(id));
    });
  }
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      closeEntryModal();
      for (const id of ["generator-modal", "share-modal", "users-modal", "export-modal"]) hide($(id));
    }
  });

  boot();
})();
