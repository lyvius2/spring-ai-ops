// Mutable LLM state — updated after save so re-opens work without page reload
let _llmConfigured     = !!LLM_CONFIGURED;
let _currentLlm        = CURRENT_LLM;
let _llmBothConfigured = !!LLM_SELECT_PROVIDER;

// CSRF token embedded by the server at page load time
const CSRF_TOKEN = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content') || '';

// ── Select Provider Modal ─────────────────────────────────────────────────────

function openLlmSelectProviderModal() {
    document.getElementById('select-provider-alert-error').style.display = 'none';
    document.getElementById('select-provider-btn').disabled = false;
    document.getElementById('select-provider-btn').textContent = 'Select';
    const firstRadio = document.querySelector('input[name="select-provider"]');
    if (firstRadio) firstRadio.checked = true;
    document.getElementById('select-provider-modal').style.display = 'flex';
}

async function submitSelectProvider() {
    const llm   = document.querySelector('input[name="select-provider"]:checked').value;
    const btn   = document.getElementById('select-provider-btn');
    const errEl = document.getElementById('select-provider-alert-error');

    btn.disabled = true;
    btn.textContent = 'Applying...';
    errEl.style.display = 'none';

    try {
        const res  = await fetch('/api/llm/select-provider', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ llm }),
        });
        const data = await res.json();
        if (data.status === 'SUCCESS') {
            setTimeout(() => location.reload(), 500);
        } else {
            errEl.textContent = data.message || 'An error occurred. Please try again.';
            errEl.style.display = 'block';
            btn.disabled = false;
            btn.textContent = 'Select';
        }
    } catch (e) {
        errEl.textContent = 'A network error occurred.';
        errEl.style.display = 'block';
        btn.disabled = false;
        btn.textContent = 'Select';
    }
}

// ── LLM Modal ────────────────────────────────────────────────────────────────

function hasLlmKeyConfigured(provider) {
    if (!provider) return false;
    if (provider === _currentLlm) return true;
    return _llmBothConfigured;
}

function updateLlmKeyInput() {
    const selected = document.querySelector('input[name="llm-provider"]:checked');
    if (!selected) return;
    const apiKeyInput   = document.getElementById('llm-api-key');
    const closeBtn      = document.getElementById('llm-modal-close');
    const isReconfigure = closeBtn && closeBtn.style.display !== 'none';

    if (isReconfigure && hasLlmKeyConfigured(selected.value)) {
        apiKeyInput.value = '';
        apiKeyInput.placeholder = 'API key already saved — leave blank to keep the existing key';
    } else {
        apiKeyInput.value = '';
        apiKeyInput.placeholder = selected.value === 'anthropic' ? 'sk-ant-...' : 'sk-...';
    }
}

function openLlmModal(isReconfigure) {
    const closeBtn = document.getElementById('llm-modal-close');
    const btn      = document.getElementById('llm-save-btn');

    // Always reset button state — it may be disabled/relabelled from a previous save
    btn.disabled    = false;

    if (isReconfigure && _currentLlm) {
        const radio = document.querySelector(`input[name="llm-provider"][value="${_currentLlm}"]`);
        if (radio) radio.checked = true;
        document.getElementById('llm-modal-desc').textContent =
            'Update your LLM provider or API key.';
        btn.textContent = 'Update & Reconnect';
        closeBtn.style.display = 'block';
    } else {
        const firstRadio = document.querySelector('input[name="llm-provider"]');
        if (firstRadio) firstRadio.checked = true;
        btn.textContent = 'Save & Connect';
        closeBtn.style.display = 'none';
    }

    // Wire up provider change → key input state update
    document.querySelectorAll('input[name="llm-provider"]').forEach(radio => {
        radio.onchange = updateLlmKeyInput;
    });
    updateLlmKeyInput();

    hideLlmAlerts();
    document.getElementById('llm-modal').style.display = 'flex';
}

function closeLlmModal() {
    document.getElementById('llm-modal').style.display = 'none';
}

async function saveLlmConfig() {
    const llm    = document.querySelector('input[name="llm-provider"]:checked').value;
    const apiKey = document.getElementById('llm-api-key').value.trim();
    const btn    = document.getElementById('llm-save-btn');
    const isReconfigure = btn.textContent.includes('Update');

    if (!apiKey && !hasLlmKeyConfigured(llm)) {
        showLlmAlert('error', 'Please enter an API key.');
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Connecting...';
    hideLlmAlerts();

    try {
        const res  = await fetch('/api/llm/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ llm, apiKey }),
        });
        const data = await res.json();

        if (res.ok && data.status === 'SUCCESS') {
            showLlmAlert('success', '');
            setTimeout(async () => {
                closeLlmModal();
                if (isReconfigure && _currentLlm && _currentLlm !== llm) _llmBothConfigured = true;
                _currentLlm    = llm;
                _llmConfigured = true;
                renderLlmStatus(llm);
                if (!isReconfigure) await proceedAfterLlm();
            }, 800);
        } else {
            showLlmAlert('error', data.message || 'An error occurred. Please try again.');
            btn.disabled = false;
            btn.textContent = 'Save & Connect';
        }
    } catch (e) {
        showLlmAlert('error', 'A network error occurred.');
        btn.disabled = false;
        btn.textContent = 'Save & Connect';
    }
}

function showLlmAlert(type, msg) {
    hideLlmAlerts();
    const ids = { success: 'llm-alert-success', warning: 'llm-alert-warning', error: 'llm-alert-error' };
    const el  = document.getElementById(ids[type]);
    if (type !== 'success') el.textContent = msg;
    el.style.display = 'block';
}

function hideLlmAlerts() {
    ['llm-alert-success', 'llm-alert-warning', 'llm-alert-error'].forEach(id => {
        document.getElementById(id).style.display = 'none';
    });
}

// ── Observability Modal (Loki / Prometheus) ───────────────────────────────────

let _observabilityStatusCache = null;
let _statusLokiUrl = '';
let _statusPrometheusUrl = '';

function renderObservabilityStatus() {
    const cell = document.getElementById('status-observability');
    if (!cell) return;

    const badges = [];
    if (_statusLokiUrl) {
        badges.push(`<img src="/images/loki.svg" class="provider-logo provider-logo-badge" alt="Loki" onerror="this.style.display='none'">
                     <span class="badge badge-up">LOKI</span>`);
    }
    if (_statusPrometheusUrl) {
        badges.push(`<img src="/images/prometheus.svg" class="provider-logo provider-logo-badge" alt="Prometheus" onerror="this.style.display='none'">
                     <span class="badge badge-up">PROMETHEUS</span>`);
    }
    if (badges.length === 0) return;

    cell.innerHTML = `
        <div style="display:flex; align-items:center; justify-content:space-between;">
            <div>
                ${badges.join(' ')}
                <span style="color:#3c763d; font-weight:600; margin-left:8px;">&#10003; Connected</span>
            </div>
            <button class="btn-secondary" style="margin-top:0;" onclick="openObservabilityModalForReconfigure()">Reconfigure</button>
        </div>
    `;
}

async function checkObservabilityAndProceed() {
    let lokiConfigured = false;
    let prometheusConfigured = false;
    let lokiUrl = '';
    let prometheusUrl = '';

    try {
        const [lokiRes, promRes] = await Promise.all([
            fetch('/api/loki/status'),
            fetch('/api/prometheus/status'),
        ]);
        const lokiData = await lokiRes.json();
        const promData = await promRes.json();
        lokiConfigured = lokiData.isConfigured;
        prometheusConfigured = promData.isConfigured;
        lokiUrl = lokiData.lokiUrl || '';
        prometheusUrl = promData.prometheusUrl || '';
        _observabilityStatusCache = { lokiConfigured, prometheusConfigured, lokiUrl, prometheusUrl };
    } catch (_) {
        // If check fails, proceed anyway
    }

    if (lokiConfigured) renderLokiStatus(lokiUrl);
    if (prometheusConfigured) renderPrometheusStatus(prometheusUrl);

    if (lokiConfigured) {
        // Loki is required; Prometheus is optional — proceed once Loki is configured
        await checkGitRemoteAndProceed();
        return;
    }

    // Loki not configured — open modal defaulting to LOKI
    openObservabilityModal(_observabilityStatusCache, false, 'LOKI');
}

function openObservabilityModal(statusData, closeable, defaultProvider) {
    _observabilityStatusCache = statusData;

    const closeBtn = document.getElementById('observability-modal-close');
    closeBtn.style.display = closeable ? 'block' : 'none';

    const desc = document.getElementById('observability-modal-desc');
    desc.textContent = closeable
        ? 'Reconfigure an observability provider.'
        : 'Enter the base URL for each observability provider to enable analysis.';

    // Build provider radio buttons from OBSERVABILITY_PROVIDERS enum
    const group = document.getElementById('observability-provider-group');
    group.innerHTML = '';
    OBSERVABILITY_PROVIDERS.forEach((p, idx) => {
        const lname = p.name.toLowerCase();
        const div = document.createElement('div');
        div.className = 'radio-option';
        div.innerHTML = `
            <input type="radio" name="observability-provider" id="obs-${lname}" value="${p.name}" ${idx === 0 ? 'checked' : ''}>
            <label for="obs-${lname}">
                <img src="/images/${lname}.svg" class="provider-logo" alt="${p.displayName}" onerror="this.style.display='none'">
                ${p.displayName}
            </label>`;
        group.appendChild(div);
    });

    if (defaultProvider) {
        const radio = group.querySelector(`input[value="${defaultProvider}"]`);
        if (radio) radio.checked = true;
    }

    group.querySelectorAll('input[name="observability-provider"]').forEach(radio => {
        radio.addEventListener('change', updateObservabilityUrlPlaceholder);
    });
    updateObservabilityUrlPlaceholder();

    document.getElementById('observability-alert-error').style.display = 'none';
    document.getElementById('observability-save-btn').disabled = false;
    document.getElementById('observability-save-btn').textContent = 'Save';

    document.getElementById('observability-modal').style.display = 'flex';
}

function updateObservabilityUrlPlaceholder() {
    const selected = document.querySelector('input[name="observability-provider"]:checked');
    if (!selected) return;
    const providerInfo = OBSERVABILITY_PROVIDERS.find(p => p.name === selected.value);
    const urlInput = document.getElementById('observability-url-input');

    if (providerInfo) urlInput.placeholder = providerInfo.apiUrl;

    if (_observabilityStatusCache) {
        const savedUrl = selected.value === 'LOKI'
            ? _observabilityStatusCache.lokiUrl
            : _observabilityStatusCache.prometheusUrl;
        urlInput.value = savedUrl || '';
    } else {
        urlInput.value = '';
    }
}

function closeObservabilityModal() {
    document.getElementById('observability-modal').style.display = 'none';
}

async function openObservabilityModalForReconfigure() {
    try {
        const [lokiRes, promRes] = await Promise.all([
            fetch('/api/loki/status'),
            fetch('/api/prometheus/status'),
        ]);
        const lokiData = await lokiRes.json();
        const promData = await promRes.json();
        _observabilityStatusCache = {
            lokiConfigured: lokiData.isConfigured,
            prometheusConfigured: promData.isConfigured,
            lokiUrl: lokiData.lokiUrl || '',
            prometheusUrl: promData.prometheusUrl || '',
        };
    } catch (_) {}
    openObservabilityModal(_observabilityStatusCache, true, null);
}

async function saveObservabilityConfig() {
    const providerEl    = document.querySelector('input[name="observability-provider"]:checked');
    const url           = document.getElementById('observability-url-input').value.trim();
    const btn           = document.getElementById('observability-save-btn');
    const errEl         = document.getElementById('observability-alert-error');
    const isReconfigure = document.getElementById('observability-modal-close').style.display !== 'none';

    if (!providerEl) {
        errEl.textContent = 'Please select a provider.';
        errEl.style.display = 'block';
        return;
    }
    // Loki URL is required; Prometheus URL is optional (allow blank to skip)
    if (!url && providerEl.value === 'LOKI') {
        errEl.textContent = 'Please enter a Loki URL.';
        errEl.style.display = 'block';
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Saving...';
    errEl.style.display = 'none';

    const isLoki  = providerEl.value === 'LOKI';
    const apiPath = isLoki ? '/api/loki/config' : '/api/prometheus/config';

    try {
        const res  = await fetch(apiPath, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url }),
        });
        const data = await res.json();

        if (res.ok && data.status === 'OK') {
            if (isLoki) {
                renderLokiStatus(url);
                if (_observabilityStatusCache) { _observabilityStatusCache.lokiUrl = url; _observabilityStatusCache.lokiConfigured = true; }
            } else {
                renderPrometheusStatus(url);
                if (_observabilityStatusCache) { _observabilityStatusCache.prometheusUrl = url; _observabilityStatusCache.prometheusConfigured = true; }
            }

            if (isReconfigure) {
                closeObservabilityModal();
                return;
            }

            // Loki is required; Prometheus is optional.
            // After saving, if Loki is now configured, proceed to Git Remote.
            const lokiOk = _observabilityStatusCache ? _observabilityStatusCache.lokiConfigured : isLoki;

            if (!lokiOk) {
                // Prometheus was just saved but Loki is still missing — switch to LOKI
                btn.disabled = false;
                btn.textContent = 'Save';
                const nextRadio = document.querySelector('input[value="LOKI"]');
                if (nextRadio) {
                    nextRadio.checked = true;
                    updateObservabilityUrlPlaceholder();
                }
                return;
            }

            closeObservabilityModal();
            await checkGitRemoteAndProceed();
        } else {
            errEl.textContent = data.message || 'Failed to save. Please try again.';
            errEl.style.display = 'block';
            btn.disabled = false;
            btn.textContent = 'Save';
        }
    } catch (e) {
        errEl.textContent = 'A network error occurred.';
        errEl.style.display = 'block';
        btn.disabled = false;
        btn.textContent = 'Save';
    }
}

// ── Main Section ─────────────────────────────────────────────────────────────

function showMainSection() {
    const section = document.getElementById('main-section');
    if (section.classList.contains('visible')) return; // prevent duplicate calls
    section.classList.add('visible');
    loadApps();
    connectWebSocket();
}

// ── Application List ─────────────────────────────────────────────────────────

let selectedApp = null;

async function loadApps() {
    const container = document.getElementById('app-list-container');
    try {
        const res  = await fetch('/api/apps');
        const apps = await res.json();
        renderAppList(apps);
    } catch (e) {
        container.innerHTML =
            '<div class="list-placeholder list-error">Failed to load applications.</div>';
    }
}

const ICON_GEAR = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>`;
const ICON_TRASH = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>`;

function createAppItem(name) {
    const item = document.createElement('div');
    item.className = 'app-item';
    item.dataset.app = name;
    item.innerHTML = `
        <span class="app-item-name">${escHtml(name)}</span>
        <div class="app-item-actions">
            <button class="app-item-edit" title="Edit">${ICON_GEAR}</button>
            <button class="app-item-delete" title="Delete">${ICON_TRASH}</button>
        </div>
    `;
    item.addEventListener('click', async function (e) {
        if (e.target.closest('.app-item-delete')) {
            openConfirmDeleteModal(name);
            return;
        }
        if (e.target.closest('.app-item-edit')) {
            openEditAppModal(name);
            return;
        }
        document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
        item.classList.add('active');
        selectedApp = name;
        await renderAppDetail(name);
    });
    return item;
}

function renderAppList(apps) {
    const container = document.getElementById('app-list-container');
    if (!apps || apps.length === 0) {
        container.innerHTML = '<div class="list-placeholder">No applications registered.</div>';
        return;
    }
    container.innerHTML = '';
    apps.forEach(app => container.appendChild(createAppItem(app)));
}

// ── Add Application Modal ────────────────────────────────────────────────────

function openAddAppModal() {
    document.getElementById('add-app-input').value = '';
    document.getElementById('add-app-git-input').value = '';
    document.getElementById('add-app-alert-success').style.display = 'none';
    document.getElementById('add-app-alert-error').style.display   = 'none';
    document.getElementById('add-app-save-btn').disabled = false;
    document.getElementById('add-app-save-btn').textContent = 'Add';
    document.getElementById('add-app-modal').style.display = 'flex';
    setTimeout(() => document.getElementById('add-app-input').focus(), 50);
}

function closeAddAppModal() {
    const modal = document.getElementById('add-app-modal');
    modal.classList.remove('fading-out');
    modal.style.display = 'none';
}

document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    if (document.getElementById('add-app-modal').style.display === 'flex') {
        saveApp();
    } else if (document.getElementById('edit-app-modal').style.display === 'flex') {
        saveEditApp();
    } else if (document.getElementById('llm-modal').style.display === 'flex') {
        saveLlmConfig();
    } else if (document.getElementById('git-remote-modal').style.display === 'flex') {
        saveGitRemoteConfig();
    } else if (document.getElementById('observability-modal').style.display === 'flex') {
        saveObservabilityConfig();
    } else if (document.getElementById('select-provider-modal').style.display === 'flex') {
        submitSelectProvider();
    } else if (document.getElementById('run-analysis-modal').style.display === 'flex') {
        submitRunAnalysis();
    }
});

async function saveApp() {
    const name   = document.getElementById('add-app-input').value.trim();
    const gitUrl = document.getElementById('add-app-git-input').value.trim() || null;
    const btn    = document.getElementById('add-app-save-btn');
    const errEl  = document.getElementById('add-app-alert-error');
    const sucEl  = document.getElementById('add-app-alert-success');

    if (!name) {
        errEl.textContent = 'Please enter an application name.';
        errEl.style.display = 'block';
        sucEl.style.display = 'none';
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Adding...';
    errEl.style.display = 'none';
    sucEl.style.display = 'none';

    try {
        const res  = await fetch('/api/apps', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, gitUrl }),
        });
        const data = await res.json();

        if (data.success) {
            sucEl.style.display = 'block';
            setTimeout(() => {
                const modal = document.getElementById('add-app-modal');
                modal.classList.add('fading-out');
                modal.addEventListener('transitionend', () => {
                    modal.style.display = 'none';
                    modal.classList.remove('fading-out');
                    addAppToList(name);
                }, { once: true });
            }, 800);
        } else {
            errEl.textContent = data.message || 'Failed to register application.';
            errEl.style.display = 'block';
            btn.disabled = false;
            btn.textContent = 'Add';
        }
    } catch (e) {
        errEl.textContent = 'A network error occurred.';
        errEl.style.display = 'block';
        btn.disabled = false;
        btn.textContent = 'Add';
    }
}

function addAppToList(name) {
    const container = document.getElementById('app-list-container');
    const placeholder = container.querySelector('.list-placeholder');
    if (placeholder) placeholder.remove();

    const item = createAppItem(name);
    container.appendChild(item);

    item.classList.add('blink');
    item.addEventListener('animationend', () => item.classList.remove('blink'), { once: true });

    return item;
}

// ── Confirm Delete Modal ─────────────────────────────────────────────────────

let appToDelete = null;

function openConfirmDeleteModal(appName) {
    appToDelete = appName;
    document.getElementById('confirm-delete-desc').textContent =
        `Are you sure you want to delete "${appName}"?`;
    document.getElementById('confirm-delete-modal').style.display = 'flex';
}

function closeConfirmDeleteModal() {
    document.getElementById('confirm-delete-modal').style.display = 'none';
    appToDelete = null;
}

async function confirmDeleteApp() {
    if (!appToDelete) return;
    const name = appToDelete;
    closeConfirmDeleteModal();

    try {
        await fetch(`/api/apps/${encodeURIComponent(name)}`, { method: 'DELETE' });

        const container = document.getElementById('app-list-container');
        const item = Array.from(container.querySelectorAll('.app-item'))
            .find(el => el.dataset.app === name);
        if (item) item.remove();

        if (selectedApp === name) {
            selectedApp = null;
            document.getElementById('app-detail-panel').innerHTML = `
                <div class="empty-state">
                    <span>Select an application from the left panel to view details.</span>
                </div>
            `;
        }

        if (container.querySelectorAll('.app-item').length === 0) {
            container.innerHTML = '<div class="list-placeholder">No applications registered.</div>';
        }
    } catch (e) {
        // silent fail — list will be consistent on next reload
    }
}

// ── Edit Application Modal ───────────────────────────────────────────────────

let appToEdit = null;

async function openEditAppModal(name) {
    appToEdit = name;
    document.getElementById('edit-app-name-input').value = name;
    document.getElementById('edit-app-git-input').value = '';
    document.getElementById('edit-app-alert-success').style.display = 'none';
    document.getElementById('edit-app-alert-error').style.display   = 'none';
    document.getElementById('edit-app-save-btn').disabled = false;
    document.getElementById('edit-app-save-btn').textContent = 'Save';
    document.getElementById('edit-app-modal').style.display = 'flex';

    try {
        const res  = await fetch(`/api/apps/${encodeURIComponent(name)}`);
        const data = await res.json();
        document.getElementById('edit-app-git-input').value = data.gitUrl || '';
    } catch (_) {
        // Proceed with empty git URL if fetch fails
    }

    setTimeout(() => document.getElementById('edit-app-name-input').focus(), 50);
}

function closeEditAppModal() {
    const modal = document.getElementById('edit-app-modal');
    modal.classList.remove('fading-out');
    modal.style.display = 'none';
    appToEdit = null;
}

async function saveEditApp() {
    if (!appToEdit) return;
    const originalName = appToEdit;
    const name   = document.getElementById('edit-app-name-input').value.trim();
    const gitUrl = document.getElementById('edit-app-git-input').value.trim() || null;
    const btn    = document.getElementById('edit-app-save-btn');
    const errEl  = document.getElementById('edit-app-alert-error');
    const sucEl  = document.getElementById('edit-app-alert-success');

    if (!name) {
        errEl.textContent = 'Please enter an application name.';
        errEl.style.display = 'block';
        sucEl.style.display = 'none';
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Saving...';
    errEl.style.display = 'none';
    sucEl.style.display = 'none';

    try {
        const res  = await fetch(`/api/apps/${encodeURIComponent(originalName)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, gitUrl }),
        });
        const data = await res.json();

        if (data.success) {
            sucEl.style.display = 'block';
            setTimeout(() => {
                const modal = document.getElementById('edit-app-modal');
                modal.classList.add('fading-out');
                modal.addEventListener('transitionend', () => {
                    modal.style.display = 'none';
                    modal.classList.remove('fading-out');
                    appToEdit = null;

                    // Update DOM: rename the app item in place
                    const container = document.getElementById('app-list-container');
                    const item = Array.from(container.querySelectorAll('.app-item'))
                        .find(el => el.dataset.app === originalName);
                    if (item) {
                        item.dataset.app = name;
                        item.querySelector('.app-item-name').textContent = name;
                        // Re-bind click with updated name
                        item.replaceWith(createAppItem(name));
                    }

                    if (selectedApp === originalName) {
                        selectedApp = name;
                    }
                }, { once: true });
            }, 800);
        } else {
            errEl.textContent = data.message || 'Failed to update application.';
            errEl.style.display = 'block';
            btn.disabled = false;
            btn.textContent = 'Save';
        }
    } catch (e) {
        errEl.textContent = 'A network error occurred.';
        errEl.style.display = 'block';
        btn.disabled = false;
        btn.textContent = 'Save';
    }
}

// ── WebSocket ────────────────────────────────────────────────────────────────

const appFiringLists       = {};   // { appName: AnalyzeFiringRecord[] }  newest-first
const appSelectedFiringIdx = {};   // { appName: number }
const appCommitLists       = {};   // { appName: CodeReviewRecord[] }    newest-first
const appSelectedCommitIdx = {};   // { appName: number }
const appCodeRiskLists     = {};   // { appName: CodeRiskRecord[] }       newest-first
const appSelectedRiskIdx   = {};   // { appName: number }
let stompClient = null;

// ── Analysis Running Indicator ────────────────────────────────────────────────

let _analysisStartTime    = null;
let _analysisElapsedTimer = null;
let _analysisTimeoutTimer = null;
const ANALYSIS_INDICATOR_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes

function _getOrCreateAnalysisIndicator() {
    let el = document.getElementById('analysis-running-indicator');
    if (!el) {
        el = document.createElement('div');
        el.id = 'analysis-running-indicator';
        el.className = 'analysis-running-indicator';
        el.innerHTML = `
            <div class="analysis-running-title">
                Code risk analysis in progress...
                <span class="analysis-running-elapsed" id="analysis-running-elapsed">(00:00)</span>
            </div>
            <div class="analysis-running-msg" id="analysis-running-msg"></div>`;
        document.body.appendChild(el);
    }
    el.classList.remove('fading-out');
    return el;
}

function _startAnalysisElapsedTimer() {
    if (_analysisElapsedTimer) clearInterval(_analysisElapsedTimer);
    _analysisElapsedTimer = setInterval(() => {
        const el = document.getElementById('analysis-running-elapsed');
        if (!el || !_analysisStartTime) return;
        const elapsed = Math.floor((Date.now() - _analysisStartTime) / 1000);
        const mm = String(Math.floor(elapsed / 60)).padStart(2, '0');
        const ss = String(elapsed % 60).padStart(2, '0');
        el.textContent = `(${mm}:${ss})`;
    }, 1000);
}

function _resetAnalysisIndicatorTimeout() {
    if (_analysisTimeoutTimer) clearTimeout(_analysisTimeoutTimer);
    _analysisTimeoutTimer = setTimeout(_hideAnalysisIndicator, ANALYSIS_INDICATOR_TIMEOUT_MS);
}

function _hideAnalysisIndicator() {
    if (_analysisElapsedTimer) { clearInterval(_analysisElapsedTimer); _analysisElapsedTimer = null; }
    if (_analysisTimeoutTimer) { clearTimeout(_analysisTimeoutTimer); _analysisTimeoutTimer = null; }
    _analysisStartTime = null;
    const el = document.getElementById('analysis-running-indicator');
    if (!el) return;
    el.classList.add('fading-out');
    el.addEventListener('transitionend', () => el.remove(), { once: true });
}

function _updateAnalysisIndicator(text) {
    if (!_analysisStartTime) _analysisStartTime = Date.now();
    _getOrCreateAnalysisIndicator();
    const msgEl = document.getElementById('analysis-running-msg');
    if (msgEl) msgEl.textContent = text;
    if (!_analysisElapsedTimer) _startAnalysisElapsedTimer();
    _resetAnalysisIndicatorTimeout();
}

function connectWebSocket() {
    // Disconnect existing client before creating a new one to prevent duplicate subscriptions
    if (stompClient !== null) {
        try { stompClient.disconnect(); } catch (_) {}
        stompClient = null;
    }
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;
    stompClient.connect({}, function () {
        stompClient.subscribe('/topic/firing', function (message) {
            handleFiringRecord(JSON.parse(message.body));
        });
        stompClient.subscribe('/topic/commit', function (message) {
            handleCommitRecord(JSON.parse(message.body));
        });
        stompClient.subscribe('/topic/analysis/status', function (message) {
            showAnalysisStatus(message.body);
        });
        stompClient.subscribe('/topic/analysis/result', function (message) {
            handleCodeRiskRecord(JSON.parse(message.body));
        });
    }, function () {
        stompClient = null;
        setTimeout(connectWebSocket, 5000);
    });
}

function showAnalysisStatus(text) {
    _updateAnalysisIndicator(text);
}

async function handleFiringRecord(record) {
    const appName = record.application;

    // API에서 최신 목록을 가져온 후 렌더링
    await loadFiringList(appName);
    appSelectedFiringIdx[appName] = 0;

    // Always show notification
    showNotification(appName);

    const container = document.getElementById('app-list-container');
    const existing = Array.from(container.querySelectorAll('.app-item'))
        .find(el => el.dataset.app === appName);

    if (existing) {
        if (selectedApp === appName) {
            // 이미 선택된 앱: 레이어와 목록만 in-place 업데이트
            const layersEl = document.getElementById('exception-layers');
            if (layersEl) layersEl.innerHTML = renderAnalysisLayers(appName, record);
            const tbody = document.getElementById('firing-list-body');
            if (tbody) tbody.innerHTML = renderFiringListRows(appName);
        } else {
            // 다른 앱이 선택 중: 렌더링 완료 후 앱 활성화
            renderAppDetailFromLocal(appName);
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            existing.classList.add('active');
            selectedApp = appName;
        }
    } else {
        // 신규 앱: 추가 후 깜빡임이 끝나면 렌더링 완료 후 활성화
        const item = addAppToList(appName);
        item.addEventListener('animationend', () => {
            renderAppDetailFromLocal(appName);
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            selectedApp = appName;
        }, { once: true });
    }
}

async function handleCommitRecord(record) {
    const appName = record.application;

    // API에서 최신 목록을 가져온 후 렌더링
    await loadCommitList(appName);
    appSelectedCommitIdx[appName] = 0;

    // Always show notification
    showCommitNotification(appName);

    const container = document.getElementById('app-list-container');
    const existing = Array.from(container.querySelectorAll('.app-item'))
        .find(el => el.dataset.app === appName);

    const switchToDefaultTab = (appName) => {
        switchToTab('codereview');
        const content = document.getElementById('codereview-content');
        if (content) { content.innerHTML = buildCodeReviewHtml(appName); applyHighlighting(content); }
    };

    if (existing) {
        if (selectedApp === appName) {
            // 이미 선택된 앱: 기본 탭으로 전환 후 in-place 업데이트
            switchToDefaultTab(appName);
        } else {
            // 다른 앱이 선택 중: 렌더링 완료 후 기본 탭으로 전환 및 앱 활성화
            renderAppDetailFromLocal(appName);
            switchToDefaultTab(appName);
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            existing.classList.add('active');
            selectedApp = appName;
        }
    } else {
        // 신규 앱: 추가 후 깜빡임이 끝나면 렌더링 완료 후 기본 탭으로 전환 및 활성화
        const item = addAppToList(appName);
        item.addEventListener('animationend', () => {
            renderAppDetailFromLocal(appName);
            switchToDefaultTab(appName);
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            selectedApp = appName;
        }, { once: true });
    }
}

async function handleCodeRiskRecord(record) {
    const appName = record.application;

    closeAnalysisStartedModal();
    _hideAnalysisIndicator();

    await loadCodeRiskList(appName);
    appSelectedRiskIdx[appName] = 0;

    const branch = record.branch || 'default';
    if (record.isSuccess) {
        showAnalysisNotification(`Code risk analysis of '${branch}' branch for ${appName} has completed.`);
    } else {
        const errorMessage = record['analyzedResult']
        showAnalysisNotification(`Code risk analysis for ${appName} failed.\n${errorMessage}`);
    }

    const container = document.getElementById('app-list-container');
    const existing = Array.from(container.querySelectorAll('.app-item'))
        .find(el => el.dataset.app === appName);

    const switchToCodeRiskTab = () => {
        const tabBtn = document.getElementById('tab-btn-coderisk');
        if (tabBtn) tabBtn.style.display = '';
        switchToTab('coderisk');
        renderCodeRiskContent(appName);
    };

    if (existing) {
        if (selectedApp === appName) {
            switchToCodeRiskTab();
        } else {
            renderAppDetailFromLocal(appName);
            switchToCodeRiskTab();
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            existing.classList.add('active');
            selectedApp = appName;
        }
    } else {
        const item = addAppToList(appName);
        item.addEventListener('animationend', () => {
            renderAppDetailFromLocal(appName);
            switchToCodeRiskTab();
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            selectedApp = appName;
        }, { once: true });
    }
}

// ── App Detail / Tabs ────────────────────────────────────────────────────────

async function loadFiringList(appName) {
    try {
        const res  = await fetch(`/api/firing/${encodeURIComponent(appName)}/list`);
        const data = await res.json();
        appFiringLists[appName] = data.firings || [];
    } catch (_) {
        appFiringLists[appName] = appFiringLists[appName] || [];
    }
}

async function loadCommitList(appName) {
    try {
        const res  = await fetch(`/api/commit/${encodeURIComponent(appName)}/list`);
        const data = await res.json();
        appCommitLists[appName] = data.commits || [];
    } catch (_) {
        appCommitLists[appName] = appCommitLists[appName] || [];
    }
}

function formatOccupiedAt(dateStr) {
    if (!dateStr) return '—';
    const s = String(dateStr);
    const tIdx = s.indexOf('T');
    if (tIdx === -1) return s;
    return s.substring(0, tIdx) + ' ' + s.substring(tIdx + 1, tIdx + 9);
}

function lokiLevelClass(level) {
    if (!level) return 'loki-level-other';
    const l = level.toLowerCase();
    if (l === 'error' || l === 'critical' || l === 'fatal') return 'loki-level-error';
    if (l === 'warn'  || l === 'warning')                   return 'loki-level-warn';
    if (l === 'info')                                       return 'loki-level-info';
    if (l === 'debug')                                      return 'loki-level-debug';
    if (l === 'trace')                                      return 'loki-level-trace';
    return 'loki-level-other';
}

function renderLokiLog(log) {
    if (!log) {
        return `<div class="loki-empty">No log data.</div>`;
    }
    const results = log?.data?.result;
    if (!results || !Array.isArray(results) || results.length === 0) {
        return `<div class="loki-empty">No log entries found.</div>`;
    }
    return `<div class="loki-log-viewer">${results.map(stream => {
        const meta  = stream.stream || {};
        const level = meta.detected_level || meta.level || '';
        const logger = meta.logger || meta.service_name || '';
        const values = stream.values || [];
        const headerParts = [
            `<span class="loki-level-badge ${lokiLevelClass(level)}">${escHtml(level || '?')}</span>`,
            logger ? `<span class="loki-logger">${escHtml(logger)}</span>` : '',
        ].filter(Boolean).join('');
        const entries = values.map(([, line]) =>
            `<div class="loki-entry">${escHtml(line)}</div>`
        ).join('');
        return `<div class="loki-stream-header">${headerParts}</div>${entries}`;
    }).join('')}</div>`;
}

function renderAnalysisLayers(appName, record) {
    if (!record) {
        return `<div class="info-message">
            Please register <code>/webhook/grafana/${escHtml(appName)}</code>
            as the Webhook URL in your Grafana Alerting Contact Point.
        </div>`;
    }
    return `
        <div class="analysis-layer">
            <div class="layer-header">Metric Firing</div>
            <pre class="json-block">${syntaxHighlightJson(JSON.stringify(record.alertingMessage, null, 2))}</pre>
        </div>
        <div class="analysis-layer">
            <div class="layer-header">Log</div>
            ${renderLokiLog(record.log)}
        </div>
        <div class="analysis-layer">
            <div class="layer-header">AI Analysis<span class="layer-header-disclaimer">* AI-generated results may not always be accurate.</span></div>
            <div class="analysis-text markdown-body">${renderMarkdown(record.analyzeResults)}</div>
        </div>`;
}

function extractLogStatus(log) {
    if (!log) return '—';
    const results = log?.data?.result;
    if (!results || !Array.isArray(results) || results.length === 0) return '—';

    // ERROR 레벨 스트림을 우선 탐색
    for (const stream of results) {
        const meta  = stream.stream || {};
        const level = (meta.detected_level || meta.level || '').toLowerCase();
        if (level !== 'error' && level !== 'critical' && level !== 'fatal') continue;

        // 로그 라인에서 XxxException / XxxError 패턴 추출
        for (const [, line] of (stream.values || [])) {
            const m = line.match(/\b([A-Z][a-zA-Z0-9]*(?:Exception|Error))\b/);
            if (m) return m[1];
        }

        // logger 메타데이터의 마지막 세그먼트 사용 (e.g. c.w.l.a.c.GlobalExceptionHandler → GlobalExceptionHandler)
        const logger = meta.logger || meta.service_name || '';
        if (logger) return logger.split('.').pop();

        return 'ERROR';
    }

    // ERROR 스트림 없으면 전체 라인에서 Exception/Error 탐색
    for (const stream of results) {
        for (const [, line] of (stream.values || [])) {
            const m = line.match(/\b([A-Z][a-zA-Z0-9]*(?:Exception|Error))\b/);
            if (m) return m[1];
        }
    }

    return '—';
}

function renderFiringListRows(appName) {
    const list        = appFiringLists[appName] || [];
    const selectedIdx = appSelectedFiringIdx[appName] ?? 0;
    if (list.length === 0) {
        return `<tr><td colspan="3" style="padding:14px;color:#999;text-align:center;">No firing records yet.</td></tr>`;
    }
    return list.map((rec, idx) =>
        `<tr class="${idx === selectedIdx ? 'active' : ''}" data-idx="${idx}">
            <td>${idx + 1}</td>
            <td class="firing-status-cell">${escHtml(extractLogStatus(rec.log))}</td>
            <td>${formatOccupiedAt(rec.occupiedAt)}</td>
        </tr>`
    ).join('');
}

function renderFiringListSection(appName) {
    return `
        <div class="firing-list-section">
            <div class="layer-header">Firing List</div>
            <table class="firing-table">
                <thead><tr><th>#</th><th>Status</th><th>Occurred At</th></tr></thead>
                <tbody id="firing-list-body">${renderFiringListRows(appName)}</tbody>
            </table>
        </div>`;
}

function selectFiringRecord(appName, idx) {
    appSelectedFiringIdx[appName] = idx;
    const record   = (appFiringLists[appName] || [])[idx];
    const layersEl = document.getElementById('exception-layers');
    if (layersEl) { layersEl.innerHTML = renderAnalysisLayers(appName, record); applyHighlighting(layersEl); }
    const tbody = document.getElementById('firing-list-body');
    if (tbody) tbody.innerHTML = renderFiringListRows(appName);
}

// ── Code Review ─────────────────────────────────────────────────────────────

function fileStatusLabel(status) {
    if (!status) return 'modify';
    const s = status.toLowerCase();
    if (s === 'added' || s === 'copied') return 'new';
    if (s === 'deleted' || s === 'removed') return 'removed';
    return 'modify';
}

function fileStatusClass(status) {
    if (!status) return 'modify';
    const s = status.toLowerCase();
    if (s === 'added' || s === 'copied') return 'new';
    if (s === 'deleted' || s === 'removed') return 'removed';
    return 'modify';
}

function renderDiffPatch(patch) {
    if (!patch) return '<span class="diff-meta">No diff available</span>';
    return patch.split('\n').map(line => {
        const esc = line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        if (line.startsWith('+'))  return `<span class="diff-add">${esc}</span>`;
        if (line.startsWith('-'))  return `<span class="diff-remove">${esc}</span>`;
        if (line.startsWith('@@')) return `<span class="diff-hunk">${esc}</span>`;
        if (line.startsWith('\\')) return `<span class="diff-meta">${esc}</span>`;
        return `<span class="diff-context">${esc}</span>`;
    }).join('\n');
}

function toggleFileDiff(headerEl) {
    const row   = headerEl.closest('.file-row');
    const diff  = row.querySelector('.file-diff');
    const arrow = headerEl.querySelector('.file-row-expand');
    const open  = diff.classList.toggle('expanded');
    arrow.textContent = open ? '▼' : '▶';
}

function detectGitProvider(url) {
    if (!url) return 'GITHUB';
    return url.toLowerCase().includes('gitlab') ? 'GITLAB' : 'GITHUB';
}

function renderCommitUrlSection(record) {
    if (!record) return '';
    const commits = record.commitSummaries;
    const provider = detectGitProvider(record.githubUrl);
    const providerLabel = provider === 'GITLAB' ? 'VIEW ON GITLAB' : 'VIEW ON GITHUB';
    const branch = record.branch || '-';

    if (commits && commits.length > 0) {
        const compareUrl = escHtml(record.githubUrl || '');
        const compareLink = compareUrl
            ? `<a class="compare-link" href="${compareUrl}" target="_blank" rel="noopener noreferrer">&#128279; ${providerLabel}</a>`
            : '';
        const rows = commits.map(c => {
            const msg = escHtml((c.message || '—').split('\n')[0]); // 첫 번째 줄만 표시
            const url = escHtml(c.url || '');
            const sha = escHtml((c.id || c.sha || '').substring(0, 7));
            const linkAttr = url ? `href="${url}" target="_blank" rel="noopener noreferrer"` : '';
            return `<a class="commit-link" ${linkAttr}>
                <span class="commit-sha">${sha}</span>
                <span class="commit-message-text">${msg}</span>
                ${url ? '<span class="commit-ext-icon">&#8599;</span>' : ''}
            </a>`;
        }).join('');
        return `
            <div class="commit-url-section">
                <div class="layer-header" style="display:flex;align-items:center;justify-content:space-between;">
                    <span>Commits (${commits.length})</span>
                    <span style="display:flex;align-items:center;gap:12px;">
                        <span class="branch-badge">&#9135; ${escHtml(branch)}</span>
                        ${compareLink}
                    </span>
                </div>
                ${rows}
            </div>`;
    }
    // fallback: old records without commitSummaries
    const msg = escHtml(record.commitMessage || record.githubUrl || '—');
    const url = escHtml(record.githubUrl || '');
    return `
        <div class="commit-url-section">
            <div class="layer-header" style="display:flex;align-items:center;justify-content:space-between;">
                <span>Commit</span>
                <span class="branch-badge">&#9135; ${escHtml(branch)}</span>
            </div>
            <a class="commit-link" href="${url}" target="_blank" rel="noopener noreferrer">
                <span class="commit-message-text">${msg}</span>
                <span class="commit-url-text">${url}</span>
            </a>
        </div>`;
}

function renderChangedFilesSection(record) {
    if (!record || !record.changedFiles || record.changedFiles.length === 0) return '';
    const rows = record.changedFiles.map((file, idx) => {
        const label = fileStatusLabel(file.status);
        const cls   = fileStatusClass(file.status);
        const stats = `<span class="diff-stat-add">+${file.additions}</span>&nbsp;<span class="diff-stat-remove">-${file.deletions}</span>`;
        const diff  = renderDiffPatch(file.patch);
        return `
            <div class="file-row" data-file-idx="${idx}">
                <div class="file-row-header" onclick="toggleFileDiff(this)">
                    <span class="file-row-expand">▶</span>
                    <span class="file-row-name">${escHtml(file.filename || '')}</span>
                    <span class="file-row-stats">${stats}</span>
                    <span class="file-badge file-badge-${cls}">${label}</span>
                </div>
                <div class="file-diff">
                    <pre class="diff-block">${diff}</pre>
                </div>
            </div>`;
    }).join('');
    return `
        <div class="changed-files-section">
            <div class="layer-header">Changed Files (${record.changedFiles.length})</div>
            <div class="file-list">${rows}</div>
        </div>`;
}

function renderCodeReviewSection(record) {
    if (!record) return '';
    const reviewResult = (record.reviewResult || '').trim();
    const isCredentialError = reviewResult.startsWith('[CREDENTIAL_ERROR]')
        || reviewResult.includes('access token is not configured');
    if (isCredentialError) {
        const msg = escHtml(reviewResult.replace('[CREDENTIAL_ERROR]', '').trim());
        return `
        <div class="analysis-layer">
            <div class="layer-header">AI Code Review<span class="layer-header-disclaimer">* AI-generated results may not always be accurate.</span></div>
            <div class="credential-error-msg">&#9888; ${msg}</div>
        </div>`;
    }
    return `
        <div class="analysis-layer">
            <div class="layer-header">AI Code Review<span class="layer-header-disclaimer">* AI-generated results may not always be accurate.</span></div>
            <div class="analysis-text markdown-body">${renderMarkdown(reviewResult)}</div>
        </div>`;
}

function renderCommitListRows(appName) {
    const list        = appCommitLists[appName] || [];
    const selectedIdx = appSelectedCommitIdx[appName] ?? 0;
    if (list.length === 0) {
        return `<tr><td colspan="3" style="padding:14px;color:#999;text-align:center;">No commit records yet.</td></tr>`;
    }
    return list.map((rec, idx) => {
        const msg = escHtml((rec.commitMessage || '—').substring(0, 60));
        return `<tr class="${idx === selectedIdx ? 'active' : ''}" data-idx="${idx}">
            <td>${idx + 1}</td>
            <td class="commit-msg-cell">${msg}</td>
            <td>${formatOccupiedAt(rec.pushedAt)}</td>
        </tr>`;
    }).join('');
}

function renderCommitListSection(appName) {
    return `
        <div class="firing-list-section">
            <div class="layer-header">Commit History</div>
            <table class="commit-table">
                <thead><tr><th>#</th><th>Message</th><th>Pushed At</th></tr></thead>
                <tbody id="commit-list-body">${renderCommitListRows(appName)}</tbody>
            </table>
        </div>`;
}

function buildCodeReviewHtml(appName) {
    const list   = appCommitLists[appName] || [];
    const idx    = appSelectedCommitIdx[appName] ?? 0;
    const record = list[idx] || null;
    const layers = record
        ? renderCommitUrlSection(record) + renderChangedFilesSection(record) + renderCodeReviewSection(record)
        : `<div class="info-message">Please register <code>/webhook/git/${escHtml(appName)}</code> as the Webhook URL triggered on push events in your GitHub/GitLab Repository.</div>`;
    return layers + renderCommitListSection(appName);
}

function selectCommitRecord(appName, idx) {
    appSelectedCommitIdx[appName] = idx;
    const content = document.getElementById('codereview-content');
    if (content) { content.innerHTML = buildCodeReviewHtml(appName); applyHighlighting(content); }
}

async function loadAndRenderCodeReview(appName) {
    if (!appName) return;
    const content = document.getElementById('codereview-content');
    if (!content) return;
    showPanelLoading('codereview-content');
    await loadCommitList(appName);
    appSelectedCommitIdx[appName] = appSelectedCommitIdx[appName] ?? 0;
    content.innerHTML = buildCodeReviewHtml(appName);
    applyHighlighting(content);
}

function showNotification(appName) {
    let container = document.getElementById('notification-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'notification-container';
        container.className = 'notification-container';
        document.body.appendChild(container);
    }
    const notif = document.createElement('div');
    notif.className = 'notification';
    notif.textContent = `New Firing Alert has occurred in ${appName}.`;
    container.appendChild(notif);
    setTimeout(() => notif.remove(), 4500);
}

function showAnalysisNotification(text) {
    let container = document.getElementById('notification-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'notification-container';
        container.className = 'notification-container';
        document.body.appendChild(container);
    }
    const notif = document.createElement('div');
    notif.className = 'notification notification-analysis';
    notif.textContent = text;
    container.appendChild(notif);
    setTimeout(() => notif.remove(), 4500);
}

function showCommitNotification(appName) {
    let container = document.getElementById('notification-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'notification-container';
        container.className = 'notification-container';
        document.body.appendChild(container);
    }
    const notif = document.createElement('div');
    notif.className = 'notification notification-commit';
    notif.textContent = `Code Review completed for ${appName}.`;
    container.appendChild(notif);
    setTimeout(() => notif.remove(), 4500);
}

function buildAppDetailHtml(appName) {
    const list   = appFiringLists[appName] || [];
    const record = list[appSelectedFiringIdx[appName] ?? 0] || null;
    const hasRisk = (appCodeRiskLists[appName] || []).length > 0;
    return `
        <div class="app-detail-header-row">
            <span class="app-detail-title">${escHtml(appName)}</span>
            <button class="btn-run-analysis" onclick="openRunAnalysisModal()">Run Code Risk Analysis</button>
        </div>
        <div class="tab-bar">
            <button class="tab-btn" data-tab="coderisk" id="tab-btn-coderisk" style="${hasRisk ? '' : 'display:none;'}">Code Risk</button>
            <button class="tab-btn active" data-tab="codereview">Code Review</button>
            <button class="tab-btn"        data-tab="exception">Incident Intelligence</button>
        </div>
        <div class="tab-content" id="tab-pane-codereview">
            <div id="codereview-content">
                <div class="list-placeholder">Loading commit records...</div>
            </div>
        </div>
        <div class="tab-content" id="tab-pane-exception" style="display:none;">
            <div id="exception-layers">${renderAnalysisLayers(appName, record)}</div>
            ${renderFiringListSection(appName)}
        </div>
        <div class="tab-content" id="tab-pane-coderisk" style="display:none;">
            <div id="coderisk-content">
                <div class="list-placeholder">Loading...</div>
            </div>
        </div>
    `;
}

// 수동 클릭 시: API에서 최신 목록을 가져온 후 렌더링
function showPanelLoading(elementId) {
    const el = document.getElementById(elementId);
    if (el) el.innerHTML = `
        <div class="loading-overlay">
            <div class="progress-bar-track"><div class="progress-bar-fill"></div></div>
            <span>Loading...</span>
        </div>`;
}

async function renderAppDetail(appName) {
    showPanelLoading('app-detail-panel');
    await Promise.all([
        loadFiringList(appName),
        loadCodeRiskList(appName),
    ]);
    appSelectedFiringIdx[appName] = 0;
    appSelectedRiskIdx[appName] = appSelectedRiskIdx[appName] ?? 0;
    const panel = document.getElementById('app-detail-panel');
    panel.innerHTML = buildAppDetailHtml(appName);
    applyHighlighting(panel);
    if ((appCodeRiskLists[appName] || []).length > 0) {
        switchToTab('coderisk');
        renderCodeRiskContent(appName);
    } else {
        await loadAndRenderCodeReview(appName);
    }
}

// WebSocket 수신 시: 로컬 상태로 즉시 렌더링 (API 호출 없음)
function renderAppDetailFromLocal(appName) {
    const panel = document.getElementById('app-detail-panel');
    panel.innerHTML = buildAppDetailHtml(appName);
    applyHighlighting(panel);
}

document.addEventListener('click', async function (e) {
    // Tab switching
    const btn = e.target.closest('.tab-btn');
    if (btn) {
        const tabName = btn.dataset.tab;
        if (tabName === 'codereview') {
            switchToTab(tabName);
            await loadAndRenderCodeReview(selectedApp);
            return;
        }
        if (tabName === 'coderisk') {
            switchToTab(tabName);
            renderCodeRiskContent(selectedApp);
            return;
        }
        switchToTab(tabName);
        return;
    }

    // Firing list row selection
    const firingRow = e.target.closest('.firing-table tbody tr[data-idx]');
    if (firingRow && selectedApp) {
        selectFiringRecord(selectedApp, parseInt(firingRow.dataset.idx, 10));
        return;
    }

    // Commit history row selection
    const commitRow = e.target.closest('.commit-table tbody tr[data-idx]');
    if (commitRow && selectedApp) {
        selectCommitRecord(selectedApp, parseInt(commitRow.dataset.idx, 10));
        return;
    }

    // Code Risk history row selection
    const riskRow = e.target.closest('.code-risk-history-table tbody tr[data-idx]');
    if (riskRow && selectedApp) {
        selectCodeRiskRecord(selectedApp, parseInt(riskRow.dataset.idx, 10));
    }
});

function switchToTab(tabName) {
    document.querySelectorAll('.tab-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.tab === tabName);
    });
    ['exception', 'codereview', 'coderisk'].forEach(t => {
        const pane = document.getElementById('tab-pane-' + t);
        if (pane) pane.style.display = (t === tabName ? 'block' : 'none');
    });
}

// ── Code Risk ─────────────────────────────────────────────────────────────────

async function loadCodeRiskList(appName) {
    try {
        const res  = await fetch(`/api/code-risk/${encodeURIComponent(appName)}/list`, {
            headers: { 'X-CSRF-Token': CSRF_TOKEN },
        });
        const data = await res.json();
        appCodeRiskLists[appName] = data.records || [];
    } catch (_) {
        appCodeRiskLists[appName] = appCodeRiskLists[appName] || [];
    }
}

function renderCodeRiskHistoryRows(appName) {
    const list        = appCodeRiskLists[appName] || [];
    const selectedIdx = appSelectedRiskIdx[appName] ?? 0;
    if (list.length === 0) {
        return `<tr><td colspan="4" style="padding:14px;color:#999;text-align:center;">No analysis records yet.</td></tr>`;
    }
    return list.map((rec, idx) =>
        `<tr class="${idx === selectedIdx ? 'active' : ''}" data-idx="${idx}">
            <td>${idx + 1}</td>
            <td>${escHtml(rec.branch || 'default')}</td>
            <td>${rec.issues?.length ?? '—'}</td>
            <td>${formatOccupiedAt(rec.analyzedAt)}</td>
        </tr>`
    ).join('');
}

function severityClass(severity) {
    if (!severity) return 'low';
    const s = severity.toLowerCase();
    if (s === 'high')   return 'high';
    if (s === 'medium') return 'medium';
    return 'low';
}

function severityIcon(severity) {
    if (!severity) return '🟡';
    const s = severity.toLowerCase();
    if (s === 'high')   return '🔴';
    if (s === 'medium') return '🟠';
    return '🟡';
}

function toggleRiskFile(headerEl) {
    const row  = headerEl.closest('.risk-file-row');
    const list = row.querySelector('.risk-issue-list');
    const arrow = headerEl.querySelector('.risk-file-expand');
    const open = list.classList.toggle('expanded');
    arrow.textContent = open ? '▼' : '▶';
}

function toggleRiskSnippet(btn) {
    const snippet = btn.closest('.risk-issue-body').querySelector('.risk-code-snippet');
    if (!snippet) return;
    const open = snippet.classList.toggle('expanded');
    btn.textContent = open ? 'Hide source' : 'Show source';
    if (open) {
        const codeEl = snippet.querySelector('code');
        if (codeEl && !codeEl.dataset.highlighted) {
            hljs.highlightElement(codeEl);
        }
    }
}

function snippetLanguage(file) {
    if (!file) return '';
    const ext = (file.split('.').pop() || '').toLowerCase();
    const map = {
        kt: 'kotlin', kts: 'kotlin', java: 'java',
        js: 'javascript', ts: 'typescript', tsx: 'typescript', jsx: 'javascript',
        py: 'python', go: 'go', rb: 'ruby', cs: 'csharp', php: 'php',
        sql: 'sql', yml: 'yaml', yaml: 'yaml', xml: 'xml', json: 'json',
    };
    return map[ext] || '';
}

function buildSnippetHtml(issue) {
    if (!issue.codeSnippet) return '';
    const lang = snippetLanguage(issue.file);
    return `
        <div class="risk-code-snippet">
            <pre class="risk-snippet-pre"><code class="${lang ? 'language-' + lang : ''}">${escHtml(issue.codeSnippet)}</code></pre>
        </div>`;
}

function renderRiskIssuesSection(issues) {
    if (!issues || issues.length === 0) return '';

    // Group by file
    const byFile = {};
    for (const issue of issues) {
        const key = issue.file || '(unknown)';
        if (!byFile[key]) byFile[key] = [];
        byFile[key].push(issue);
    }

    const fileRows = Object.entries(byFile).map(([file, fileIssues]) => {
        const items = fileIssues.map(issue => {
            const sev = severityClass(issue.severity);
            const lineText = issue.line ? `Line ${escHtml(issue.line)}` : '';
            const snippetHtml = buildSnippetHtml(issue);
            const showSourceBtn = snippetHtml
                ? `<button class="risk-show-source-btn" onclick="toggleRiskSnippet(this)">Show source</button>`
                : '';
            return `
                <div class="risk-issue-item">
                    <div class="risk-issue-meta">
                        <span class="risk-severity-badge risk-severity-${sev}">${severityIcon(issue.severity)} ${escHtml(issue.severity || 'Low')}</span>
                        ${lineText ? `<span class="risk-issue-line">${lineText}</span>` : ''}
                    </div>
                    <div class="risk-issue-body">
                        <div class="risk-issue-description markdown-body">${renderMarkdown(issue.description || '')}</div>
                        ${issue.recommendation ? `<div class="risk-issue-recommendation markdown-body">${renderMarkdown(issue.recommendation)}</div>` : ''}
                        ${showSourceBtn}
                        ${snippetHtml}
                    </div>
                </div>`;
        }).join('');

        return `
            <div class="risk-file-row">
                <div class="risk-file-header" onclick="toggleRiskFile(this)">
                    <span class="risk-file-expand">▶</span>
                    <span class="risk-file-name">${escHtml(file)}</span>
                    <span class="risk-file-count">${fileIssues.length} issue${fileIssues.length > 1 ? 's' : ''}</span>
                </div>
                <div class="risk-issue-list">${items}</div>
            </div>`;
    }).join('');

    const highCount   = issues.filter(i => i.severity?.toLowerCase() === 'high').length;
    const mediumCount = issues.filter(i => i.severity?.toLowerCase() === 'medium').length;
    const lowCount    = issues.filter(i => i.severity?.toLowerCase() === 'low').length;

    return `
        <div class="analysis-layer">
            <div class="layer-header">Issues by File (${issues.length} total)<span class="layer-header-severity-counts">🔴 HIGH : ${highCount} 🟠 MEDIUM : ${mediumCount} 🟡 LOW : ${lowCount}</span></div>
            <div style="padding:10px 12px;">${fileRows}</div>
        </div>`;
}

function renderCodeRiskContent(appName) {
    const content = document.getElementById('coderisk-content');
    if (!content) return;
    const list = appCodeRiskLists[appName] || [];
    const idx  = appSelectedRiskIdx[appName] ?? 0;
    const rec  = list[idx] || null;

    if (!rec) {
        content.innerHTML = `<div class="info-message">No code risk analysis has been performed for this application.</div>`;
        return;
    }

    content.innerHTML = `
        <div class="analysis-layer">
            <div class="layer-header">Git Repository</div>
            <div style="padding:10px 14px; font-size:0.88rem; word-break:break-all;">
                <a href="${escHtml(rec.githubUrl || '')}" target="_blank" rel="noopener noreferrer" style="color:inherit; text-decoration:none;" onmouseover="this.style.textDecoration='underline'" onmouseout="this.style.textDecoration='none'">${escHtml(rec.githubUrl || '—')}</a>
            </div>
        </div>
        <div class="analysis-layer">
            <div class="layer-header">Branch</div>
            <div style="padding:10px 14px; font-size:0.88rem;">
                ${escHtml(rec.branch || 'default')}
                <span style="color:#999; margin-left:8px;">(${formatOccupiedAt(rec.analyzedAt)})</span>
            </div>
        </div>
        ${renderRiskIssuesSection(rec.issues)}
        <div class="analysis-layer">
            <div class="layer-header">AI Analysis Result<span class="layer-header-disclaimer">* AI-generated results may not always be accurate. Results may vary between analyses.</span></div>
            <div class="analysis-text markdown-body" style="padding:14px;">${renderMarkdown(rec.analyzedResult || '')}</div>
        </div>
        <div class="analysis-layer">
            <div class="layer-header">Analysis History</div>
            <table class="code-risk-history-table">
                <thead><tr><th>#</th><th>Branch</th><th>Issues</th><th>Analyzed At</th></tr></thead>
                <tbody id="code-risk-history-body">${renderCodeRiskHistoryRows(appName)}</tbody>
            </table>
        </div>
    `;
    applyHighlighting(content);
}

function selectCodeRiskRecord(appName, idx) {
    appSelectedRiskIdx[appName] = idx;
    renderCodeRiskContent(appName);
}

// ── Run Static Analysis Modal ─────────────────────────────────────────────────

async function openRunAnalysisModal() {
    if (!selectedApp) return;
    document.getElementById('run-analysis-branch').value = '';
    document.getElementById('run-analysis-alert-error').style.display = 'none';
    document.getElementById('run-analysis-no-git').style.display = 'none';
    document.getElementById('run-analysis-form').style.display = 'block';
    const btn = document.getElementById('run-analysis-submit-btn');
    btn.disabled = false;
    btn.textContent = 'Run Analysis';

    // Check if git URL is configured
    try {
        const res  = await fetch(`/api/apps/${encodeURIComponent(selectedApp)}`);
        const data = await res.json();
        if (!data.gitUrl) {
            document.getElementById('run-analysis-form').style.display = 'none';
            document.getElementById('run-analysis-no-git').style.display = 'block';
        }
    } catch (_) {
        document.getElementById('run-analysis-form').style.display = 'none';
        document.getElementById('run-analysis-no-git').style.display = 'block';
    }

    document.getElementById('run-analysis-modal').style.display = 'flex';
    setTimeout(() => document.getElementById('run-analysis-branch').focus(), 50);
}

function closeRunAnalysisModal() {
    document.getElementById('run-analysis-modal').style.display = 'none';
}

function openAnalysisStartedModal() {
    document.getElementById('analysis-started-modal').style.display = 'flex';
}

function closeAnalysisStartedModal() {
    document.getElementById('analysis-started-modal').style.display = 'none';
}

async function submitRunAnalysis() {
    if (!selectedApp) return;
    const branch = document.getElementById('run-analysis-branch').value.trim();
    const btn    = document.getElementById('run-analysis-submit-btn');
    const errEl  = document.getElementById('run-analysis-alert-error');

    btn.disabled = true;
    btn.textContent = 'Starting...';
    errEl.style.display = 'none';

    try {
        const res  = await fetch('/api/code-risk', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': CSRF_TOKEN },
            body: JSON.stringify({ appName: selectedApp, branch }),
        });
        const data = await res.json();

        closeRunAnalysisModal();

        if (data.success) {
            _analysisStartTime = Date.now();
            openAnalysisStartedModal();
        } else {
            // Show error inline
            document.getElementById('run-analysis-form').style.display = 'block';
            btn.disabled = false;
            btn.textContent = 'Run Analysis';
            errEl.textContent = data.message || 'Analysis failed. Please try again.';
            errEl.style.display = 'block';
            document.getElementById('run-analysis-modal').style.display = 'flex';
        }
    } catch (e) {
        closeRunAnalysisModal();
    }
}

// ── Git Remote Modal ──────────────────────────────────────────────────────────

let _gitRemoteStatusCache = null;

/**
 * Determines which action to take based on the git remote configuration status.
 * Called after Loki is confirmed or on init.
 */
async function checkGitRemoteAndProceed() {
    try {
        const res  = await fetch('/api/github/config/status');
        const data = await res.json();
        _gitRemoteStatusCache = data;

        const ghOk = data.githubTokenConfigured || data.githubPropertyConfigured;
        const glOk = data.gitlabTokenConfigured  || data.gitlabPropertyConfigured;

        if (ghOk || glOk) {
            renderGitRemoteStatus(data);
            showMainSection();
            return;
        }

        // Nothing configured → must setup
        openGitRemoteModal(data, false);
    } catch (_) {
        showMainSection();
    }
}

function openGitRemoteModal(statusData, closeable) {
    _gitRemoteStatusCache = statusData;

    // Close button visibility
    const closeBtn = document.getElementById('git-remote-modal-close');
    closeBtn.style.display = closeable ? 'block' : 'none';

    // Description
    const desc = document.getElementById('git-remote-modal-desc');
    desc.textContent = closeable
        ? 'Both providers are configured. Select the one you want to use and confirm your credentials.'
        : 'Select your Git provider and enter credentials to enable AI Code Review.';

    // Build provider radio buttons dynamically from server-rendered enum values
    const group = document.getElementById('git-remote-provider-group');
    group.innerHTML = '';
    GIT_REMOTE_PROVIDERS.forEach((p, idx) => {
        const lname = p.name.toLowerCase();
        const displayName = lname.charAt(0).toUpperCase() + lname.slice(1);
        const div = document.createElement('div');
        div.className = 'radio-option';
        div.innerHTML = `
            <input type="radio" name="git-remote-provider" id="git-remote-${lname}" value="${p.name}" ${idx === 0 ? 'checked' : ''}>
            <label for="git-remote-${lname}">
                <img src="/images/${lname}.svg" class="provider-logo" alt="${displayName}" onerror="this.style.display='none'">
                ${displayName}
            </label>`;
        group.appendChild(div);
    });

    // If only GitLab is configured, pre-select GitLab; otherwise default to first (GitHub)
    if (statusData) {
        const ghOk = statusData.githubTokenConfigured || statusData.githubPropertyConfigured;
        const glOk = statusData.gitlabTokenConfigured  || statusData.gitlabPropertyConfigured;
        if (!ghOk && glOk) {
            const glRadio = group.querySelector('input[value="GITLAB"]');
            if (glRadio) glRadio.checked = true;
        }
    }

    // Update URL/token fields when provider changes
    group.querySelectorAll('input[name="git-remote-provider"]').forEach(radio => {
        radio.addEventListener('change', updateGitRemoteUrlPlaceholder);
    });
    updateGitRemoteUrlPlaceholder();

    document.getElementById('git-remote-alert-error').style.display = 'none';
    document.getElementById('git-remote-save-btn').disabled = false;
    document.getElementById('git-remote-save-btn').textContent = 'Save & Connect';

    document.getElementById('git-remote-modal').style.display = 'flex';
}

function updateGitRemoteUrlPlaceholder() {
    const selected      = document.querySelector('input[name="git-remote-provider"]:checked');
    if (!selected) return;
    const providerInfo  = GIT_REMOTE_PROVIDERS.find(p => p.name === selected.value);
    const closeBtn      = document.getElementById('git-remote-modal-close');
    const isReconfigure = closeBtn && closeBtn.style.display !== 'none';
    const urlInput      = document.getElementById('git-remote-url-input');
    const tokenInput    = document.getElementById('git-remote-token-input');

    // URL: show saved value for the selected provider; placeholder = default API base URL
    if (providerInfo) urlInput.placeholder = providerInfo.apiUrl;
    if (_gitRemoteStatusCache) {
        const savedUrl = selected.value === 'GITHUB'
            ? _gitRemoteStatusCache.githubUrl
            : _gitRemoteStatusCache.gitlabUrl;
        urlInput.value = savedUrl || '';
    } else {
        urlInput.value = '';
    }

    // Token: show masked sentinel if configured; otherwise show format hint
    const hasToken = !!(_gitRemoteStatusCache && (selected.value === 'GITHUB'
        ? (_gitRemoteStatusCache.githubTokenConfigured || _gitRemoteStatusCache.githubPropertyConfigured)
        : (_gitRemoteStatusCache.gitlabTokenConfigured || _gitRemoteStatusCache.gitlabPropertyConfigured)));
    if (isReconfigure && hasToken) {
        tokenInput.value = '';
        tokenInput.placeholder = 'Token already saved — leave blank to keep the existing token';
    } else {
        tokenInput.value = '';
        tokenInput.placeholder = selected.value === 'GITHUB' ? 'ghp_...' : (selected.value === 'GITLAB' ? 'glpat-...' : '');
    }
}

function closeGitRemoteModal() {
    document.getElementById('git-remote-modal').style.display = 'none';
}

async function openGitRemoteModalForReconfigure() {
    try {
        const res  = await fetch('/api/github/config/status');
        const data = await res.json();
        openGitRemoteModal(data, true);
    } catch (_) {
        openGitRemoteModal(null, true);
    }
}

async function saveGitRemoteConfig() {
    const providerEl   = document.querySelector('input[name="git-remote-provider"]:checked');
    const urlInput     = document.getElementById('git-remote-url-input');
    const token        = document.getElementById('git-remote-token-input').value.trim();
    const btn          = document.getElementById('git-remote-save-btn');
    const errEl        = document.getElementById('git-remote-alert-error');
    const isReconfigure = document.getElementById('git-remote-modal-close').style.display !== 'none';

    if (!providerEl) {
        errEl.textContent = 'Please select a provider.';
        errEl.style.display = 'block';
        return;
    }
    if (!token && !isReconfigure) {
        errEl.textContent = 'Please enter an access token.';
        errEl.style.display = 'block';
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Saving...';
    errEl.style.display = 'none';

    const provider = providerEl.value;
    // Use typed URL or fall back to placeholder (default URL)
    const url = urlInput.value.trim() || urlInput.placeholder;

    try {
        const res  = await fetch('/api/github/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ provider, token, url }),
        });
        const data = await res.json();

        if (data.success) {
            closeGitRemoteModal();
            // Re-fetch to reflect the updated state of all providers
            try {
                const statusRes  = await fetch('/api/github/config/status');
                const statusData = await statusRes.json();
                _gitRemoteStatusCache = statusData;
                renderGitRemoteStatus(statusData);
            } catch (_) { /* status cell stays as-is */ }
            showMainSection();
        } else {
            errEl.textContent = data.message || 'Failed to save. Please try again.';
            errEl.style.display = 'block';
            btn.disabled = false;
            btn.textContent = 'Save & Connect';
        }
    } catch (e) {
        errEl.textContent = 'A network error occurred.';
        errEl.style.display = 'block';
        btn.disabled = false;
        btn.textContent = 'Save & Connect';
    }
}

// ── Utility ──────────────────────────────────────────────────────────────────

function syntaxHighlightJson(json) {
    const s = json
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return s.replace(
        /("(\\u[a-fA-F0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g,
        function (match) {
            if (/^"/.test(match)) {
                return `<span class="${/:$/.test(match) ? 'json-key' : 'json-string'}">${match}</span>`;
            }
            if (/true|false/.test(match)) return `<span class="json-boolean">${match}</span>`;
            if (/null/.test(match))       return `<span class="json-null">${match}</span>`;
            return `<span class="json-number">${match}</span>`;
        }
    );
}

function escHtml(str) {
    const d = document.createElement('div');
    d.appendChild(document.createTextNode(String(str)));
    return d.innerHTML;
}

function renderInline(text) {
    return text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
}

function renderMarkdown(text) {
    if (!text) return '';
    const lines = text.split('\n');
    let html = '';
    let inUl = false;
    let inOl = false;
    let inNestedUl = false;
    let inCode = false;
    let codeLang = '';
    let codeLines = [];
    let inTable = false;
    let tableRows = [];

    const closeList = () => {
        if (inNestedUl) { html += '</ul>'; inNestedUl = false; }
        if (inUl) { html += '</ul>'; inUl = false; }
        if (inOl) { html += '</ol>'; inOl = false; }
    };

    const flushTable = () => {
        if (!inTable) return;
        inTable = false;
        if (tableRows.length === 0) { tableRows = []; return; }
        let t = '<table class="md-table">';
        let isHeader = true;
        for (const row of tableRows) {
            if (/^\|[-| :]+\|$/.test(row.trim())) { isHeader = false; continue; }
            const cells = row.trim().replace(/^\||\|$/g, '').split('|');
            const tag = isHeader ? 'th' : 'td';
            t += '<tr>' + cells.map(c => `<${tag}>${renderInline(c.trim())}</${tag}>`).join('') + '</tr>';
            isHeader = false;
        }
        t += '</table>';
        html += t;
        tableRows = [];
    };

    for (let i = 0; i < lines.length; i++) {
        const raw = lines[i];
        const line = raw.trimEnd();
        const trimmed = line.trimStart();

        // Fenced code block (support leading whitespace up to 3 spaces)
        if (/^`{3,}/.test(trimmed)) {
            if (!inCode) {
                closeList();
                flushTable();
                inCode = true;
                codeLang = trimmed.replace(/^`+/, '').trim();
                codeLines = [];
            } else {
                inCode = false;
                const escaped = codeLines.join('\n')
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
                const langAttr = codeLang ? ` class="language-${escHtml(codeLang)}"` : '';
                html += `<pre class="md-code-block"><code${langAttr}>${escaped}</code></pre>`;
                codeLines = []; codeLang = '';
            }
            continue;
        }
        if (inCode) { codeLines.push(raw); continue; }

        // Table row
        if (/^\s*\|/.test(line)) {
            closeList();
            inTable = true;
            tableRows.push(line);
            continue;
        } else {
            flushTable();
        }

        if (!line.trim()) {
            // OL 내 blank line: 다음 non-blank 줄이 번호 항목이면 OL을 유지
            if (inOl) {
                let j = i + 1;
                while (j < lines.length && !lines[j].trim()) j++;
                if (j < lines.length && /^\d+\. /.test(lines[j])) {
                    if (inNestedUl) { html += '</ul>'; inNestedUl = false; }
                    continue;
                }
            }
            closeList();
            continue;
        }

        // Horizontal rule
        if (/^[-*]{3,}$/.test(line.trim())) {
            closeList();
            html += '<hr>';
            continue;
        }

        // Headings
        if (line.startsWith('#### ')) {
            closeList();
            html += `<h4>${renderInline(line.slice(5))}</h4>`;
        } else if (line.startsWith('### ')) {
            closeList();
            html += `<h3>${renderInline(line.slice(4))}</h3>`;
        } else if (line.startsWith('## ')) {
            closeList();
            html += `<h2>${renderInline(line.slice(3))}</h2>`;
        } else if (line.startsWith('# ')) {
            closeList();
            html += `<h1>${renderInline(line.slice(2))}</h1>`;
        // Blockquote
        } else if (line.startsWith('> ')) {
            closeList();
            html += `<blockquote><p>${renderInline(line.slice(2))}</p></blockquote>`;
        // Indented unordered list (nested inside ordered list)
        } else if (/^\s+- /.test(line) && inOl) {
            if (!inNestedUl) { html += '<ul>'; inNestedUl = true; }
            html += `<li>${renderInline(line.replace(/^\s+- /, ''))}</li>`;
        // Unordered list
        } else if (/^- /.test(line)) {
            if (inNestedUl) { html += '</ul>'; inNestedUl = false; }
            if (inOl) { html += '</ol>'; inOl = false; }
            if (!inUl) { html += '<ul>'; inUl = true; }
            html += `<li>${renderInline(line.slice(2))}</li>`;
        // Ordered list
        } else if (/^\d+\. /.test(line)) {
            if (inNestedUl) { html += '</ul>'; inNestedUl = false; }
            if (inUl) { html += '</ul>'; inUl = false; }
            if (!inOl) { html += '<ol>'; inOl = true; }
            html += `<li>${renderInline(line.replace(/^\d+\. /, ''))}</li>`;
        } else {
            closeList();
            html += `<p>${renderInline(line)}</p>`;
        }
    }
    closeList();
    flushTable();
    if (inCode) {
        const escaped = codeLines.join('\n')
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        const langAttr = codeLang ? ` class="language-${escHtml(codeLang)}"` : '';
        html += `<pre class="md-code-block"><code${langAttr}>${escaped}</code></pre>`;
    }
    return html;
}

function applyHighlighting(containerEl) {
    if (!containerEl || typeof hljs === 'undefined') return;
    containerEl.querySelectorAll('pre.md-code-block code').forEach(el => hljs.highlightElement(el));
}

// ── Init ─────────────────────────────────────────────────────────────────────


function renderGitRemoteStatus(statusData) {
    const cell = document.getElementById('status-git-remote');
    if (!cell || !statusData) return;

    const providers = [];
    if (statusData.githubTokenConfigured || statusData.githubPropertyConfigured) providers.push('GITHUB');
    if (statusData.gitlabTokenConfigured  || statusData.gitlabPropertyConfigured) providers.push('GITLAB');
    if (providers.length === 0) return;

    const badges = providers.map(p => {
        const lp = p.toLowerCase();
        return `<img src="/images/${lp}.svg" class="provider-logo provider-logo-badge" alt="${p}" onerror="this.style.display='none'">
                <span class="badge badge-up">${p}</span>`;
    }).join(' ');

    cell.innerHTML = `
        <div style="display:flex; align-items:center; justify-content:space-between;">
            <div>
                ${badges}
                <span style="color:#3c763d; font-weight:600; margin-left:8px;">&#10003; Connected</span>
            </div>
            <button class="btn-secondary" style="margin-top:0;" onclick="openGitRemoteModalForReconfigure()">Reconfigure</button>
        </div>
    `;
}

function renderLokiStatus(lokiUrl) {
    _statusLokiUrl = lokiUrl || '';
    renderObservabilityStatus();
}

function renderPrometheusStatus(prometheusUrl) {
    _statusPrometheusUrl = prometheusUrl || '';
    renderObservabilityStatus();
}

function renderLlmStatus(provider) {
    const cell = document.getElementById('status-llm-provider');
    if (!cell || !provider) return;
    const lp = provider.toLowerCase();
    cell.innerHTML = `
        <div style="display:flex; align-items:center; justify-content:space-between;">
            <div>
                <img src="/images/${lp}.svg" class="provider-logo provider-logo-badge" alt="${lp}" onerror="this.style.display='none'">
                <span class="badge badge-up">${lp.toUpperCase()}</span>
                <span style="color:#3c763d; font-weight:600; margin-left:8px;">&#10003; Connected</span>
            </div>
            <button class="btn-secondary" style="margin-top:0;" onclick="openLlmModal(true)">Reconfigure</button>
        </div>
    `;
}

async function proceedAfterLlm() {
    // Step 2: check Observability (Loki + Prometheus)
    await checkObservabilityAndProceed();
}

async function init() {
    // Step 1: LLM not configured → branch by state
    if (!_llmConfigured) {
        if (LLM_SELECT_PROVIDER) {
            openLlmSelectProviderModal();
        } else {
            openLlmModal(false);
        }
        return;
    }

    await proceedAfterLlm();
}

document.addEventListener('DOMContentLoaded', init);

