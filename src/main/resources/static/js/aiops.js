// Mutable LLM state — updated after save so re-opens work without page reload
let _llmConfigured     = !!LLM_CONFIGURED;
let _currentLlm        = CURRENT_LLM;
let _llmBothConfigured = !!LLM_SELECT_PROVIDER;
let _savedLlmProviders = [];  // provider keys that have a stored API key (loaded from /api/llm/status)

// Registered application names — populated by loadApps(), used for Prometheus metrics query
let _appNames = [];

// CSRF token embedded by the server at page load time
const CSRF_TOKEN = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content') || '';

// ── LLM Status Fetch ──────────────────────────────────────────────────────────

async function fetchLlmStatus() {
    try {
        const res  = await fetch('/api/llm/status');
        const data = await res.json();
        _llmConfigured     = !!data.configured;
        _currentLlm        = data.usageLlm || '';
        _savedLlmProviders = Array.isArray(data.savedProviders) ? data.savedProviders : [];
        updateLlmKeyBadges();
    } catch (_) {}
}

function updateLlmKeyBadges() {
    LLM_PROVIDERS.forEach(p => {
        const badge = document.getElementById(`llm-key-badge-${p.key}`);
        if (badge) badge.style.display = _savedLlmProviders.includes(p.key) ? '' : 'none';
    });
}

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
    return _savedLlmProviders.includes(provider);
}

function updateLlmKeyInput() {
    const selected = document.querySelector('input[name="llm-provider"]:checked');
    if (!selected) return;
    const apiKeyInput  = document.getElementById('llm-api-key');
    const saveKeyBtn   = document.getElementById('llm-save-key-only-btn');
    const saveConnBtn  = document.getElementById('llm-save-btn');
    const isReconfigure = document.getElementById('llm-modal-close').style.display !== 'none';

    const keyAlreadySaved = hasLlmKeyConfigured(selected.value);
    const isCurrentProvider = selected.value === _currentLlm;

    if (keyAlreadySaved) {
        apiKeyInput.value = '';
        apiKeyInput.placeholder = 'API key already saved — leave blank to keep the existing key';
        if (isReconfigure) {
            // If already the active provider, only show "Save Key" to update the key
            // If not active, show both buttons
            saveConnBtn.textContent = isCurrentProvider ? 'Update Key &amp; Reconnect' : 'Set as Active';
            saveConnBtn.innerHTML   = isCurrentProvider ? 'Update Key &amp; Reconnect' : 'Set as Active';
            saveKeyBtn.style.display = isCurrentProvider ? 'none' : '';
        } else {
            saveConnBtn.innerHTML = 'Save &amp; Connect';
            saveKeyBtn.style.display = 'none';
        }
    } else {
        apiKeyInput.value = '';
        apiKeyInput.placeholder = selected.value === 'anthropic' ? 'sk-ant-...' : 'sk-...';
        saveConnBtn.innerHTML = 'Save &amp; Connect';
        saveKeyBtn.style.display = 'none';
    }
}

function openLlmModal(isReconfigure) {
    const closeBtn = document.getElementById('llm-modal-close');
    const btn      = document.getElementById('llm-save-btn');

    btn.disabled = false;

    fetchLlmStatus().then(() => {
        if (isReconfigure && _currentLlm) {
            const radio = document.querySelector(`input[name="llm-provider"][value="${_currentLlm}"]`);
            if (radio) radio.checked = true;
            document.getElementById('llm-modal-desc').textContent =
                'Update the API key for any provider, or switch to a different one.';
            closeBtn.style.display = 'block';
        } else {
            const firstRadio = document.querySelector('input[name="llm-provider"]');
            if (firstRadio) firstRadio.checked = true;
            document.getElementById('llm-modal-desc').textContent =
                'Select your LLM provider and enter an API key to get started.';
            closeBtn.style.display = 'none';
        }

        document.querySelectorAll('input[name="llm-provider"]').forEach(radio => {
            radio.onchange = updateLlmKeyInput;
        });
        updateLlmKeyInput();
        hideLlmAlerts();
        document.getElementById('llm-modal').style.display = 'flex';
    });
}

function closeLlmModal() {
    document.getElementById('llm-modal').style.display = 'none';
}

async function saveLlmConfig() {
    const llm    = document.querySelector('input[name="llm-provider"]:checked').value;
    const apiKey = document.getElementById('llm-api-key').value.trim();
    const btn    = document.getElementById('llm-save-btn');

    if (!apiKey && !hasLlmKeyConfigured(llm)) {
        showLlmAlert('error', 'Please enter an API key.');
        return;
    }

    btn.disabled = true;
    const origLabel = btn.innerHTML;
    btn.innerHTML = 'Connecting...';
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
            await fetchLlmStatus();
            setTimeout(async () => {
                closeLlmModal();
                _currentLlm    = llm;
                _llmConfigured = true;
                renderLlmStatus(llm);
                const isReconfigure = origLabel.includes('Reconnect') || origLabel.includes('Active');
                if (!isReconfigure) await proceedAfterLlm();
            }, 800);
        } else {
            showLlmAlert('error', data.message || 'An error occurred. Please try again.');
            btn.disabled = false;
            btn.innerHTML = origLabel;
        }
    } catch (e) {
        showLlmAlert('error', 'A network error occurred.');
        btn.disabled = false;
        btn.innerHTML = origLabel;
    }
}

async function saveLlmKeyOnly() {
    const llm    = document.querySelector('input[name="llm-provider"]:checked').value;
    const apiKey = document.getElementById('llm-api-key').value.trim();
    const btn    = document.getElementById('llm-save-key-only-btn');

    if (!apiKey) {
        showLlmAlert('error', 'Please enter an API key to save.');
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Saving...';
    hideLlmAlerts();

    try {
        // Save key by calling /config with the provider (does not change active usage from server perspective,
        // but configure() sets USAGE_LLM — we restore it afterwards by switching back)
        const currentProvider = _currentLlm;
        const res = await fetch('/api/llm/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ llm, apiKey }),
        });
        const data = await res.json();

        if (res.ok && data.status === 'SUCCESS') {
            // Restore active provider if it was different
            if (currentProvider && currentProvider !== llm) {
                await fetch('/api/llm/select-provider', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ llm: currentProvider }),
                });
            }
            await fetchLlmStatus();
            showLlmAlert('info', `API key for ${llm.toUpperCase()} saved successfully.`);
            document.getElementById('llm-api-key').value = '';
            document.getElementById('llm-api-key').placeholder = 'API key already saved — leave blank to keep the existing key';
            updateLlmKeyInput();
        } else {
            showLlmAlert('error', data.message || 'Failed to save key.');
        }
        btn.disabled = false;
        btn.textContent = 'Save Key Only';
    } catch (e) {
        showLlmAlert('error', 'A network error occurred.');
        btn.disabled = false;
        btn.textContent = 'Save Key Only';
    }
}

function showLlmAlert(type, msg) {
    hideLlmAlerts();
    const ids = { success: 'llm-alert-success', info: 'llm-alert-info', warning: 'llm-alert-warning', error: 'llm-alert-error' };
    const el  = document.getElementById(ids[type]);
    if (!el) return;
    if (type !== 'success') el.textContent = msg;
    el.style.display = 'block';
}

function hideLlmAlerts() {
    ['llm-alert-success', 'llm-alert-info', 'llm-alert-warning', 'llm-alert-error'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.style.display = 'none';
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
        const lname       = escHtml(p.name.toLowerCase());
        const pNameEsc    = escHtml(p.name);
        const displayEsc  = escHtml(p.displayName);
        const div = document.createElement('div');
        div.className = 'radio-option';
        div.innerHTML = `
            <input type="radio" name="observability-provider" id="obs-${lname}" value="${pNameEsc}" ${idx === 0 ? 'checked' : ''}>
            <label for="obs-${lname}">
                <img src="/images/${lname}.svg" class="provider-logo" alt="${displayEsc}" onerror="this.style.display='none'">
                ${displayEsc}
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
let prometheusAutoRefreshEnabled = true;
let prometheusAutoRefreshTimer = null;

async function loadApps() {
    const container = document.getElementById('app-list-container');
    try {
        const res  = await fetch('/api/apps');
        const apps = await res.json();
        _appNames = Array.isArray(apps) ? apps : [];
        renderAppList(apps);
        if (!selectedApp) renderDefaultAppDetail();
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

function renderDefaultAppDetail() {
    selectedApp = null;
    document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
    startPrometheusAutoRefresh();
    const panel = document.getElementById('app-detail-panel');
    if (!panel) return;
    panel.innerHTML = `
        <div class="prometheus-dashboard-loading">
            <span>Loading Prometheus application metrics...</span>
        </div>
    `;
    loadPrometheusApplicationMetrics();
}

function refreshPrometheusApplicationMetrics() {
    if (selectedApp) return;
    startPrometheusAutoRefresh();
    const table = document.querySelector('#app-detail-panel .prometheus-metrics-table');
    if (!table) {
        renderDefaultAppDetail();
        return;
    }
    table.classList.remove('metrics-refreshing');
    void table.offsetWidth;
    table.classList.add('metrics-refreshing');
    loadPrometheusApplicationMetrics(true);
}

function startPrometheusAutoRefresh() {
    stopPrometheusAutoRefresh();
    if (!prometheusAutoRefreshEnabled || selectedApp) return;
    prometheusAutoRefreshTimer = setInterval(() => {
        if (!selectedApp && prometheusAutoRefreshEnabled) {
            refreshPrometheusApplicationMetrics();
        }
    }, 15000);
}

function stopPrometheusAutoRefresh() {
    if (!prometheusAutoRefreshTimer) return;
    clearInterval(prometheusAutoRefreshTimer);
    prometheusAutoRefreshTimer = null;
}

function togglePrometheusAutoRefresh(enabled) {
    prometheusAutoRefreshEnabled = enabled;
    if (enabled) {
        startPrometheusAutoRefresh();
    } else {
        stopPrometheusAutoRefresh();
    }
}

function renderDefaultEmptyState() {
    stopPrometheusAutoRefresh();
    const panel = document.getElementById('app-detail-panel');
    if (!panel) return;
    panel.innerHTML = `
        <div class="empty-state">
            <span>Select an application from the left panel to view details.</span>
        </div>
    `;
}

async function loadPrometheusApplicationMetrics(refreshTableOnly = false) {
    const panel = document.getElementById('app-detail-panel');
    try {
        const query = _appNames.map(name => `apps=${encodeURIComponent(name)}`).join('&');
        const url = `/api/prometheus/application-metrics${query ? '?' + query : ''}`;
        const res = await fetch(url);
        const data = await res.json();
        if (selectedApp) return;
        if (!data.configured || data.errorMessage || !Array.isArray(data.applications) || data.applications.length === 0) {
            renderDefaultEmptyState();
            return;
        }
        if (refreshTableOnly) {
            const table = panel.querySelector('.prometheus-metrics-table');
            if (table) {
                table.outerHTML = buildPrometheusMetricsTableHtml(data, true);
                return;
            }
        }
        panel.innerHTML = buildPrometheusDashboardHtml(data);
    } catch (_) {
        if (!selectedApp) renderDefaultEmptyState();
    }
}

function buildPrometheusDashboardHtml(data) {
    return `
        <div class="prometheus-dashboard">
            <div class="prometheus-dashboard-header">
                <div>
                    <div class="prometheus-dashboard-title">Prometheus Application Metrics</div>
                    <div class="prometheus-dashboard-subtitle">Last 1 hour by registered application</div>
                </div>
                <div class="prometheus-dashboard-actions">
                    <label class="auto-refresh-toggle">
                        <input type="checkbox" ${prometheusAutoRefreshEnabled ? 'checked' : ''} onchange="togglePrometheusAutoRefresh(this.checked)">
                        <span class="auto-refresh-switch"></span>
                        <span>Auto 15s</span>
                    </label>
                    <button class="btn-secondary" onclick="refreshPrometheusApplicationMetrics()">Refresh</button>
                </div>
            </div>
            ${buildPrometheusMetricsTableHtml(data)}
        </div>
    `;
}

function buildPrometheusMetricsTableHtml(data, refreshing = false) {
    const rows = data.applications.map(buildPrometheusAppRowHtml).join('');
    return `
        <div class="prometheus-metrics-table${refreshing ? ' metrics-refreshing' : ''}">
            <div class="prometheus-metrics-head">
                <span>Application</span>
                <span>Memory</span>
                <span>Uptime</span>
                <span>Open Files</span>
                <span>CPU Usage</span>
                <span>Avg Latency</span>
                <span>Responses</span>
            </div>
            ${rows}
        </div>
    `;
}

function buildPrometheusAppRowHtml(app) {
    return `
        <div class="prometheus-metrics-row">
            <div class="prometheus-app-name">${escHtml(app.applicationName || '-')}</div>
            <div>${renderMemoryGauge(app)}</div>
            <div>${renderUptimeMetric(app.uptime)}</div>
            <div>${renderDashboardSparkline(app.openFiles, '#6db33f', formatMetricValue(latestPointValue(app.openFiles)))}</div>
            <div>${renderMultiSeriesSparkline(app.cpuUsage, 'percent')}</div>
            <div>${renderDashboardSparkline(app.averageLatency, '#1a56c4', formatLatencyValue(latestPointValue(app.averageLatency)))}</div>
            <div>${renderMultiSeriesSparkline(app.httpStatus, 'count')}</div>
        </div>
    `;
}

function renderMemoryGauge(app) {
    const percent = app?.memoryUsedPercent;
    if (!Number.isFinite(percent)) return '<div class="metric-empty-inline">No data</div>';
    const value = Math.max(0, Math.min(100, percent));
    const angle = -90 + (value * 1.8);
    const allocated = formatMemoryMb(app?.memoryAllocatedMb);
    const used = formatMemoryMb(app?.memoryUsedMb);
    return `
        <div class="memory-gauge" style="--gauge-angle:${angle}deg;">
            <div class="memory-gauge-arc"></div>
            <div class="memory-gauge-needle"></div>
            <div class="memory-gauge-value">${value.toFixed(1)}%</div>
        </div>
        <div class="memory-mb">
            <span>Allocated ${allocated}</span>
            <span>Used ${used}</span>
        </div>
    `;
}

function renderUptimeMetric(uptime) {
    if (!uptime || !Number.isFinite(uptime.uptimeSeconds)) return '<div class="metric-empty-inline">No data</div>';
    return `
        <div class="uptime-metric">
            <div class="uptime-start">${formatDateTime(uptime.startedAt)}</div>
            <div class="uptime-elapsed">${formatDuration(uptime.uptimeSeconds)}</div>
        </div>
    `;
}

function renderDashboardSparkline(points, color, label) {
    if (!Array.isArray(points) || points.length === 0) return '<div class="metric-empty-inline">No data</div>';
    const svg = buildSparklineSvg(points, [{ name: 'value', color, points }]);
    return `<div class="dashboard-sparkline">${svg}<div class="sparkline-value">${escHtml(label)}</div></div>`;
}

function renderMultiSeriesSparkline(series, valueType) {
    const available = (series || []).filter(s => Array.isArray(s.points) && s.points.length > 0);
    if (available.length === 0) return '<div class="metric-empty-inline">No data</div>';
    const palette = ['#6db33f', '#1a56c4', '#8a6d3b'];
    const svgSeries = available.map((s, idx) => ({ name: s.name, color: palette[idx % palette.length], points: s.points }));
    const svg = buildSparklineSvg(available.flatMap(s => s.points), svgSeries);
    const legend = svgSeries.map(s => {
        const value = latestPointValue(s.points);
        const label = valueType === 'percent' ? `${formatMetricValue(value * 100)}%` : formatMetricValue(value);
        return `<span><i style="background:${s.color};"></i>${escHtml(s.name)} ${label}</span>`;
    }).join('');
    return `<div class="dashboard-sparkline">${svg}<div class="sparkline-legend">${legend}</div></div>`;
}

function buildSparklineSvg(allPoints, series) {
    const width = 150;
    const height = 54;
    const pad = 5;
    const timestamps = allPoints.map(p => p.timestamp);
    const values = allPoints.map(p => p.value);
    const xMin = Math.min(...timestamps);
    const xMax = Math.max(...timestamps);
    const yMin = Math.min(...values);
    const yMax = Math.max(...values);
    const xSpan = xMax - xMin || 1;
    const ySpan = yMax - yMin || 1;
    const paths = series.map(s => {
        const d = s.points.map((p, idx) => {
            const x = pad + ((p.timestamp - xMin) / xSpan) * (width - pad * 2);
            const y = height - pad - ((p.value - yMin) / ySpan) * (height - pad * 2);
            return `${idx === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
        }).join(' ');
        return `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`;
    }).join('');
    return `<svg viewBox="0 0 ${width} ${height}" role="img" aria-hidden="true">${paths}</svg>`;
}

function latestPointValue(points) {
    if (!Array.isArray(points) || points.length === 0) return null;
    return points[points.length - 1]?.value ?? null;
}

function formatLatencyValue(value) {
    if (!Number.isFinite(value)) return '—';
    if (value < 1) return `${Number((value * 1000).toFixed(2)).toLocaleString()} ms`;
    return `${Number(value.toFixed(3)).toLocaleString()} s`;
}

function formatMemoryMb(value) {
    if (!Number.isFinite(value)) return '—';
    return `${Number(value.toFixed(1)).toLocaleString()} MB`;
}

function formatDateTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return escHtml(date.toLocaleString('en-US', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    }));
}

function formatDuration(seconds) {
    if (!Number.isFinite(seconds)) return '—';
    const total = Math.max(0, Math.floor(seconds));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    if (days > 0) return `${days}d ${hours}h`;
    if (hours > 0) return `${hours}h ${minutes}m`;
    return `${minutes}m`;
}

// ── Add Application Modal ────────────────────────────────────────────────────

function openAddAppModal() {
    document.getElementById('add-app-input').value = '';
    document.getElementById('add-app-git-input').value = '';
    document.getElementById('add-app-branch-input').value = '';
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
    const name         = document.getElementById('add-app-input').value.trim();
    const gitUrl       = document.getElementById('add-app-git-input').value.trim() || null;
    const deployBranch = document.getElementById('add-app-branch-input').value.trim() || null;
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
            body: JSON.stringify({ name, gitUrl, deployBranch }),
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
                    if (!selectedApp) renderDefaultAppDetail();
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
            renderDefaultAppDetail();
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
    document.getElementById('edit-app-branch-input').value = '';
    document.getElementById('edit-app-alert-success').style.display = 'none';
    document.getElementById('edit-app-alert-error').style.display   = 'none';
    document.getElementById('edit-app-save-btn').disabled = false;
    document.getElementById('edit-app-save-btn').textContent = 'Save';
    document.getElementById('edit-app-modal').style.display = 'flex';

    try {
        const res  = await fetch(`/api/apps/${encodeURIComponent(name)}`);
        const data = await res.json();
        document.getElementById('edit-app-git-input').value    = data.gitUrl       || '';
        document.getElementById('edit-app-branch-input').value = data.deployBranch || '';
    } catch (_) {
        // Proceed with empty fields if fetch fails
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
    const name         = document.getElementById('edit-app-name-input').value.trim();
    const gitUrl       = document.getElementById('edit-app-git-input').value.trim() || null;
    const deployBranch = document.getElementById('edit-app-branch-input').value.trim() || null;
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
            body: JSON.stringify({ name, gitUrl, deployBranch }),
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
let activeSourceSuggestion = null;
const appCommitLists       = {};   // { appName: CodeReviewRecord[] }    newest-first
const appSelectedCommitIdx = {};   // { appName: number }
const appCodeRiskLists     = {};   // { appName: CodeRiskRecord[] }       newest-first
const appSelectedRiskIdx   = {};   // { appName: number }
const appRiskFilePage      = {};   // { appName: number }  current file-page (0-based)
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
        stompClient.subscribe('/topic/alert', function (message) {
            showAlertLayer(JSON.parse(message.body));
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

    // Fetch the latest lists from the API, then render.
    await Promise.all([
        loadFiringList(appName),
        loadCodeRiskList(appName),
    ]);
    appSelectedFiringIdx[appName] = 0;

    // Always show notification
    showNotification(appName);

    const container = document.getElementById('app-list-container');
    const existing = Array.from(container.querySelectorAll('.app-item'))
        .find(el => el.dataset.app === appName);

    if (existing) {
        if (selectedApp === appName) {
            // Selected app: update only the layers and list in place.
            syncCodeRiskTabVisibility(appName);
            const layersEl = document.getElementById('exception-layers');
            if (layersEl) {
                layersEl.innerHTML = renderAnalysisLayers(appName, record);
                applyHighlighting(layersEl);
            } else {
                renderAppDetailFromLocal(appName);
            }
            const tbody = document.getElementById('firing-list-body');
            if (tbody) tbody.innerHTML = renderFiringListRows(appName);
            switchToTab('exception');
        } else {
            // Different app selected: render, then activate this app.
            renderAppDetailFromLocal(appName);
            switchToTab('exception');
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            existing.classList.add('active');
            selectedApp = appName;
        }
    } else {
        // New app: add it, then render and activate it after the highlight animation.
        const item = addAppToList(appName);
        item.addEventListener('animationend', () => {
            renderAppDetailFromLocal(appName);
            switchToTab('exception');
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            selectedApp = appName;
        }, { once: true });
    }
}

async function handleCommitRecord(record) {
    const appName = record.application;

    // Fetch the latest list from the API, then render.
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
            // Selected app: switch to the default tab and update in place.
            switchToDefaultTab(appName);
        } else {
            // Different app selected: render, switch to the default tab, and activate this app.
            renderAppDetailFromLocal(appName);
            switchToDefaultTab(appName);
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            existing.classList.add('active');
            selectedApp = appName;
        }
    } else {
        // New app: add it, then render, switch to the default tab, and activate it after the highlight animation.
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
    appRiskFilePage[appName] = 0;

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
        appCommitLists[appName] = sortByDateDesc(data.commits || [], 'pushedAt');
    } catch (_) {
        appCommitLists[appName] = appCommitLists[appName] || [];
    }
}

function sortByDateDesc(items, field) {
    return [...items]
        .map((item, index) => ({ item, index, time: Date.parse(item?.[field] || '') }))
        .sort((a, b) => {
            const aValid = Number.isFinite(a.time);
            const bValid = Number.isFinite(b.time);
            if (aValid && bValid && a.time !== b.time) return b.time - a.time;
            if (aValid !== bValid) return aValid ? -1 : 1;
            return a.index - b.index;
        })
        .map(({ item }) => item);
}

function formatOccupiedAt(dateStr) {
    if (!dateStr) return '—';
    const s = String(dateStr);
    const tIdx = s.indexOf('T');
    const formatted = tIdx === -1 ? s : s.substring(0, tIdx) + ' ' + s.substring(tIdx + 1, tIdx + 9);
    return escHtml(formatted);
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

function renderPrometheusMetrics(metrics) {
    if (!metrics) {
        return `<div class="metric-empty">No metric data.</div>`;
    }
    const results = metrics?.data?.result;
    if (!Array.isArray(results) || results.length === 0) {
        return `<div class="metric-empty">No metric series found.</div>`;
    }

    const trendSeries = [];
    const tableSeries = [];
    let omittedTrendCount = 0;
    let omittedTableCount = 0;
    results.forEach(series => {
        const samples = metricSamples(series);
        if (samples.length > 1) {
            const stats = metricStats(samples);
            if (isZeroMetricStats(stats)) {
                omittedTrendCount += 1;
            } else {
                trendSeries.push({ series, samples });
            }
        } else if (samples[0]?.value === 0) {
            omittedTableCount += 1;
        } else {
            tableSeries.push({ series, samples });
        }
    });

    return `
        <div class="metric-viewer">
            <div class="metric-summary">
                <span>${escHtml(metrics.data?.resultType || 'unknown')}</span>
                <span>${results.length} series</span>
                <span>${trendSeries.length} trends</span>
                <span>${tableSeries.length} snapshots</span>
                <span>${omittedTrendCount + omittedTableCount} omitted</span>
            </div>
            ${renderMetricTrendGroups(trendSeries)}
            ${renderMetricTable(tableSeries)}
        </div>`;
}

function metricSamples(series) {
    const values = Array.isArray(series?.values) ? series.values : (Array.isArray(series?.value) ? [series.value] : []);
    return values
        .map(([timestamp, value]) => ({ timestamp: Number(timestamp), value: Number(value) }))
        .filter(sample => Number.isFinite(sample.timestamp) && Number.isFinite(sample.value));
}

function metricName(series) {
    return series?.metric?.__name__ || '(unnamed metric)';
}

function metricLabelSummary(metric) {
    const labels = Object.entries(metric || {})
        .filter(([key]) => key !== '__name__' && key !== 'application' && key !== 'job' && key !== 'instance');
    if (labels.length === 0) return 'default';
    return labels.map(([key, value]) => `${key}=${value}`).join(', ');
}

function metricStats(samples) {
    if (!samples.length) return { latest: null, min: null, max: null };
    const values = samples.map(sample => sample.value);
    return {
        latest: values[values.length - 1],
        min: Math.min(...values),
        max: Math.max(...values),
    };
}

function isZeroMetricStats(stats) {
    return stats.latest === 0 && stats.min === 0 && stats.max === 0;
}

function formatMetricValue(value) {
    if (value === null || value === undefined || !Number.isFinite(value)) return '—';
    const abs = Math.abs(value);
    if (abs !== 0 && (abs >= 1_000_000 || abs < 0.001)) return value.toExponential(3);
    return Number(value.toFixed(4)).toLocaleString();
}

function formatMetricTime(timestamp) {
    if (!Number.isFinite(timestamp)) return '—';
    return new Date(timestamp * 1000).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function renderMetricSparkline(samples) {
    const width = 260;
    const height = 64;
    const pad = 8;
    const xMin = samples[0].timestamp;
    const xMax = samples[samples.length - 1].timestamp;
    const yValues = samples.map(sample => sample.value);
    const yMin = Math.min(...yValues);
    const yMax = Math.max(...yValues);
    const xSpan = xMax - xMin || 1;
    const ySpan = yMax - yMin || 1;
    const points = samples.map(sample => {
        const x = pad + ((sample.timestamp - xMin) / xSpan) * (width - pad * 2);
        const y = yMax === yMin
            ? height / 2
            : height - pad - ((sample.value - yMin) / ySpan) * (height - pad * 2);
        return `${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(' ');

    return `<svg class="metric-sparkline" viewBox="0 0 ${width} ${height}" role="img" aria-label="Metric trend">
        <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" class="metric-axis"></line>
        <polyline points="${points}" class="metric-line"></polyline>
    </svg>`;
}

function renderMetricTrendGroups(trendSeries) {
    if (trendSeries.length === 0) return '';
    const groups = trendSeries.reduce((acc, item) => {
        const name = metricName(item.series);
        if (!acc.has(name)) acc.set(name, []);
        acc.get(name).push(item);
        return acc;
    }, new Map());

    return `<div class="metric-trend-groups">${Array.from(groups.entries()).map(([name, items], idx) => `
        <details class="metric-group" ${idx < 3 ? 'open' : ''}>
            <summary>${escHtml(name)} <span>${items.length} series</span></summary>
            <div class="metric-series-list">
                ${items.map(item => renderMetricTrendRow(item.series, item.samples)).join('')}
            </div>
        </details>
    `).join('')}</div>`;
}

function renderMetricTrendRow(series, samples) {
    const stats = metricStats(samples);
    return `<div class="metric-series-row">
        <div class="metric-series-chart">${renderMetricSparkline(samples)}</div>
        <div class="metric-series-meta">
            <div class="metric-labels">${escHtml(metricLabelSummary(series.metric))}</div>
            <div class="metric-range">${escHtml(formatMetricTime(samples[0].timestamp))} - ${escHtml(formatMetricTime(samples[samples.length - 1].timestamp))}</div>
        </div>
        <div class="metric-values">
            <span><b>Latest</b>${escHtml(formatMetricValue(stats.latest))}</span>
            <span><b>Min</b>${escHtml(formatMetricValue(stats.min))}</span>
            <span><b>Max</b>${escHtml(formatMetricValue(stats.max))}</span>
        </div>
    </div>`;
}

function renderMetricTable(tableSeries) {
    if (tableSeries.length === 0) return '';
    return `<div class="metric-table-wrap">
        <table class="metric-table">
            <thead><tr><th>Metric</th><th>Labels</th><th>Time</th><th>Value</th></tr></thead>
            <tbody>${tableSeries.map(({ series, samples }) => {
                const sample = samples[0] || {};
                return `<tr>
                    <td>${escHtml(metricName(series))}</td>
                    <td>${escHtml(metricLabelSummary(series.metric))}</td>
                    <td>${escHtml(formatMetricTime(sample.timestamp))}</td>
                    <td>${escHtml(formatMetricValue(sample.value))}</td>
                </tr>`;
            }).join('')}</tbody>
        </table>
    </div>`;
}

function renderAnalysisLayers(appName, record) {
    if (!record) {
        return `<div class="info-message">
            Please register <code>/webhook/grafana/${escHtml(appName)}</code>
            as the Webhook URL in your Grafana Alerting Contact Point.
        </div>`;
    }
    return `
        <details class="analysis-layer collapsible-layer">
            <summary class="layer-header">Metric Firing<span class="layer-toggle-icon"></span></summary>
            <pre class="json-block">${syntaxHighlightJson(JSON.stringify(record.alertingMessage, null, 2))}</pre>
        </details>
        <div class="analysis-layer">
            <div class="layer-header metric-layer-header">Metric<span class="layer-header-disclaimer">* Zero-value trend metrics and zero-value table rows are omitted.</span></div>
            ${renderPrometheusMetrics(record.metrics)}
        </div>
        <div class="analysis-layer">
            <div class="layer-header">Log</div>
            ${renderLokiLog(record.log)}
        </div>
        <div class="analysis-layer">
            <div class="layer-header">AI Analysis<span class="layer-header-disclaimer">* AI-generated results may not always be accurate.</span></div>
            <div class="analysis-text markdown-body">${renderMarkdown(record.analyzeResults)}</div>
            ${renderSourceCodeSuggestions(record.sourceCodeSuggestions)}
        </div>`;
}

function renderSourceCodeSuggestions(suggestions) {
    const items = Array.isArray(suggestions) ? suggestions.filter(Boolean) : [];
    if (items.length === 0) return '';

    return `<div class="source-suggestion-list">
        <div class="source-suggestion-list-title">Source Code Suggestions</div>
        ${items.map((suggestion, idx) => {
            const filePath = suggestion.filePath || 'Unknown file';
            const line = suggestion.lineNumber == null ? '' : `:${suggestion.lineNumber}`;
            const description = suggestion.description ? `<div class="source-suggestion-summary">${escHtml(suggestion.description)}</div>` : '';
            return `<div class="source-suggestion-item">
                <button class="source-suggestion-link" data-suggestion-idx="${idx}" title="${escHtml(filePath)}">
                    ${escHtml(filePath)}${escHtml(line)}
                </button>
                ${description}
            </div>`;
        }).join('')}
    </div>`;
}

function extractLogStatus(log) {
    if (!log) return '—';
    const results = log?.data?.result;
    if (!results || !Array.isArray(results) || results.length === 0) return '—';

    // Prefer streams with ERROR-level signals.
    for (const stream of results) {
        const meta  = stream.stream || {};
        const level = (meta.detected_level || meta.level || '').toLowerCase();
        if (level !== 'error' && level !== 'critical' && level !== 'fatal') continue;

        // Extract XxxException / XxxError patterns from log lines.
        for (const [, line] of (stream.values || [])) {
            const m = line.match(/\b([A-Z][a-zA-Z0-9]*(?:Exception|Error))\b/);
            if (m) return m[1];
        }

        // Use the last logger metadata segment (e.g. c.w.l.a.c.GlobalExceptionHandler -> GlobalExceptionHandler).
        const logger = meta.logger || meta.service_name || '';
        if (logger) return logger.split('.').pop();

        return 'ERROR';
    }

    // If no ERROR stream exists, scan all lines for Exception/Error.
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
    const commits = record.commitSummaries ? sortByDateDesc(record.commitSummaries, 'timestamp') : record.commitSummaries;
    const provider = detectGitProvider(record.githubUrl);
    const providerLabel = provider === 'GITLAB' ? 'VIEW ON GITLAB' : 'VIEW ON GITHUB';
    const branch = record.branch || '-';

    if (commits && commits.length > 0) {
        const compareUrl = escHtml(record.githubUrl || '');
        const compareLink = compareUrl
            ? `<a class="compare-link" href="${compareUrl}" target="_blank" rel="noopener noreferrer">&#128279; ${providerLabel}</a>`
            : '';
        const rows = commits.map(c => {
            const msg = escHtml((c.message || '—').split('\n')[0]); // Show only the first line.
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

function showAlertLayer(alert) {
    const content = buildAlertLayerContent(alert);
    const modal = document.getElementById('websocket-alert-modal');
    modal.classList.remove('alert-layer-warning', 'alert-layer-danger');
    if (content.tone) {
        modal.classList.add(`alert-layer-${content.tone}`);
    }
    document.getElementById('websocket-alert-title').textContent = content.title;
    document.getElementById('websocket-alert-message').textContent = content.body;
    modal.style.display = 'flex';
}

function closeAlertLayer() {
    document.getElementById('websocket-alert-modal').style.display = 'none';
}

function buildAlertLayerContent(alert) {
    const type = alert?.type || '';
    const appName = alert?.applicationName || 'the application';
    const exceptionMessage = alert?.exceptionMessage || 'No exception message was provided.';

    if (type === 'INVALID_DEPLOY_BRANCH_FALLBACK') {
        const branch = alert?.deployBranch || '(blank)';
        return {
            title: '⚠️ Invalid deploy branch',
            tone: 'warning',
            body:
                `The saved deploy branch "${branch}" for ${appName} is invalid.\n` +
                'The source code was checked out from the repository default branch instead, and the saved deploy branch has been cleared.\n\n' +
                `Exception: ${exceptionMessage}`,
        };
    }

    if (type === 'SOURCE_CHECKOUT_FAILED') {
        return {
            title: '⚠️ Source code checkout failed',
            tone: 'danger',
            body:
                `Source code checkout failed for ${appName}.\n` +
                'Check that the Git repository URL and branch are correct, the GitHub/GitLab token is valid, and the application has disk permissions.\n\n' +
                `${exceptionMessage}`,
        };
    }

    return {
        title: 'Alert',
        tone: '',
        body: exceptionMessage,
    };
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

// Manual click: fetch the latest lists from the API, then render.
function showPanelLoading(elementId) {
    const el = document.getElementById(elementId);
    if (el) el.innerHTML = `
        <div class="loading-overlay">
            <div class="progress-bar-track"><div class="progress-bar-fill"></div></div>
            <span>Loading...</span>
        </div>`;
}

async function renderAppDetail(appName) {
    stopPrometheusAutoRefresh();
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

// WebSocket receive: render immediately from local state without an API call.
function renderAppDetailFromLocal(appName) {
    stopPrometheusAutoRefresh();
    const panel = document.getElementById('app-detail-panel');
    panel.innerHTML = buildAppDetailHtml(appName);
    applyHighlighting(panel);
}

document.addEventListener('click', async function (e) {
    const sourceSuggestionLink = e.target.closest('.source-suggestion-link[data-suggestion-idx]');
    if (sourceSuggestionLink) {
        openSourceSuggestionModal(parseInt(sourceSuggestionLink.dataset.suggestionIdx, 10));
        return;
    }

    // Risk file pagination — uses data-* to avoid JS string injection in onclick
    const riskPageBtn = e.target.closest('.risk-page-btn[data-app]');
    if (riskPageBtn && !riskPageBtn.disabled) {
        goToRiskFilePage(riskPageBtn.dataset.app, parseInt(riskPageBtn.dataset.page, 10));
        return;
    }

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

function getSelectedFiringRecord() {
    if (!selectedApp) return null;
    const list = appFiringLists[selectedApp] || [];
    return list[appSelectedFiringIdx[selectedApp] ?? 0] || null;
}

function openSourceSuggestionModal(index) {
    const record = getSelectedFiringRecord();
    const suggestions = Array.isArray(record?.sourceCodeSuggestions) ? record.sourceCodeSuggestions : [];
    const suggestion = suggestions[index];
    if (!suggestion) return;

    activeSourceSuggestion = suggestion;

    const filePath = suggestion.filePath || 'Unknown file';
    const line = suggestion.lineNumber == null ? '' : `:${suggestion.lineNumber}`;
    const originalCode = normalizeSourceSuggestionCode(suggestion.originalCode || '');
    const suggestionCode = normalizeSourceSuggestionCode(suggestion.suggestionCode || '');
    const language = inferSourceLanguage(filePath);

    document.getElementById('source-suggestion-title').textContent = `${filePath}${line}`;
    document.getElementById('source-suggestion-description').textContent = suggestion.description || '';

    const originalCodeEl = document.getElementById('source-suggestion-original-code');
    const suggestedCodeEl = document.getElementById('source-suggestion-suggested-code');
    originalCodeEl.removeAttribute('data-highlighted');
    suggestedCodeEl.removeAttribute('data-highlighted');
    originalCodeEl.className = language ? `language-${language}` : '';
    suggestedCodeEl.className = language ? `language-${language}` : '';
    originalCodeEl.textContent = originalCode || '(No original code provided)';
    suggestedCodeEl.textContent = suggestionCode || '(No suggested code provided)';
    delete originalCodeEl.dataset.highlighted;
    delete suggestedCodeEl.dataset.highlighted;

    const copyBtn = document.getElementById('source-suggestion-copy-btn');
    copyBtn.textContent = 'Copy';
    copyBtn.disabled = !suggestionCode;

    document.getElementById('source-suggestion-modal').style.display = 'flex';
    if (typeof hljs !== 'undefined') {
        try {
            hljs.highlightElement(originalCodeEl);
            hljs.highlightElement(suggestedCodeEl);
        } catch (_) {
            originalCodeEl.textContent = originalCode || '(No original code provided)';
            suggestedCodeEl.textContent = suggestionCode || '(No suggested code provided)';
        }
    }
}

function closeSourceSuggestionModal() {
    activeSourceSuggestion = null;
    document.getElementById('source-suggestion-modal').style.display = 'none';
}

function normalizeSourceSuggestionCode(code) {
    const text = String(code || '');
    const hasRealLineBreak = /\r|\n/.test(text);
    const hasEscapedLineBreak = /\\r\\n|\\n|\\r/.test(text);
    if (!hasEscapedLineBreak || hasRealLineBreak) {
        return text;
    }

    return text
        .replace(/\\r\\n/g, '\n')
        .replace(/\\n/g, '\n')
        .replace(/\\r/g, '\n')
        .replace(/\\t/g, '    ')
        .replace(/\\"/g, '"');
}

async function copySourceSuggestionCode() {
    const code = normalizeSourceSuggestionCode(activeSourceSuggestion?.suggestionCode || '');
    if (!code) return;

    const copyBtn = document.getElementById('source-suggestion-copy-btn');
    try {
        await writeClipboardText(code);
        copyBtn.textContent = 'Copied';
        setTimeout(() => {
            if (document.getElementById('source-suggestion-modal').style.display === 'flex') {
                copyBtn.textContent = 'Copy';
            }
        }, 1200);
    } catch (e) {
        copyBtn.textContent = 'Copy failed';
        setTimeout(() => {
            if (document.getElementById('source-suggestion-modal').style.display === 'flex') {
                copyBtn.textContent = 'Copy';
            }
        }, 1400);
    }
}

async function writeClipboardText(text) {
    if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return;
    }

    const textArea = document.createElement('textarea');
    textArea.value = text;
    textArea.setAttribute('readonly', '');
    textArea.style.position = 'fixed';
    textArea.style.left = '-9999px';
    textArea.style.top = '0';
    document.body.appendChild(textArea);
    textArea.select();
    try {
        document.execCommand('copy');
    } finally {
        document.body.removeChild(textArea);
    }
}

function inferSourceLanguage(filePath) {
    const lower = String(filePath || '').toLowerCase();
    if (lower.endsWith('.kt') || lower.endsWith('.kts')) return 'kotlin';
    if (lower.endsWith('.java')) return 'java';
    if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript';
    if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript';
    if (lower.endsWith('.py')) return 'python';
    if (lower.endsWith('.go')) return 'go';
    if (lower.endsWith('.rb')) return 'ruby';
    if (lower.endsWith('.cs')) return 'csharp';
    if (lower.endsWith('.xml')) return 'xml';
    if (lower.endsWith('.json')) return 'json';
    if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
    return '';
}

function switchToTab(tabName) {
    document.querySelectorAll('.tab-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.tab === tabName);
    });
    ['exception', 'codereview', 'coderisk'].forEach(t => {
        const pane = document.getElementById('tab-pane-' + t);
        if (pane) pane.style.display = (t === tabName ? 'block' : 'none');
    });
}

function syncCodeRiskTabVisibility(appName) {
    const tabBtn = document.getElementById('tab-btn-coderisk');
    if (!tabBtn) return;
    tabBtn.style.display = (appCodeRiskLists[appName] || []).length > 0 ? '' : 'none';
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

const RISK_FILE_PAGE_SIZE = 10;

function renderRiskIssuesSection(issues, appName) {
    if (!issues || issues.length === 0) return '';

    // Group by file
    const byFile = {};
    for (const issue of issues) {
        const key = issue.file || '(unknown)';
        if (!byFile[key]) byFile[key] = [];
        byFile[key].push(issue);
    }

    const allFileEntries = Object.entries(byFile);
    const totalFiles = allFileEntries.length;
    const totalPages = Math.ceil(totalFiles / RISK_FILE_PAGE_SIZE);
    const currentPage = Math.min(appRiskFilePage[appName] ?? 0, totalPages - 1);
    const pageStart = currentPage * RISK_FILE_PAGE_SIZE;
    const pageEntries = allFileEntries.slice(pageStart, pageStart + RISK_FILE_PAGE_SIZE);

    const fileRows = pageEntries.map(([file, fileIssues]) => {
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

    const paginationHtml = totalPages > 1 ? `
        <div class="risk-file-pagination">
            <button class="risk-page-btn" data-app="${escHtml(appName)}" data-page="${currentPage - 1}" ${currentPage === 0 ? 'disabled' : ''}>&#8249;</button>
            <span class="risk-page-info">${pageStart + 1}–${Math.min(pageStart + RISK_FILE_PAGE_SIZE, totalFiles)} / ${totalFiles} files</span>
            <button class="risk-page-btn" data-app="${escHtml(appName)}" data-page="${currentPage + 1}" ${currentPage >= totalPages - 1 ? 'disabled' : ''}>&#8250;</button>
        </div>` : '';

    return `
        <div class="analysis-layer">
            <div class="layer-header">Issues by File (${totalFiles} files · ${issues.length} total)<span class="layer-header-severity-counts">🔴 HIGH : ${highCount} 🟠 MEDIUM : ${mediumCount} 🟡 LOW : ${lowCount}</span></div>
            <div style="padding:10px 12px;">${fileRows}</div>
            ${paginationHtml}
        </div>`;
}

function goToRiskFilePage(appName, page) {
    appRiskFilePage[appName] = page;
    renderCodeRiskContent(appName);
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
        ${renderRiskIssuesSection(rec.issues, appName)}
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
    appRiskFilePage[appName] = 0;
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

    // Check if git URL is configured; pre-fill deploy branch if set
    try {
        const res  = await fetch(`/api/apps/${encodeURIComponent(selectedApp)}`);
        const data = await res.json();
        if (!data.gitUrl) {
            document.getElementById('run-analysis-form').style.display = 'none';
            document.getElementById('run-analysis-no-git').style.display = 'block';
        } else if (data.deployBranch) {
            document.getElementById('run-analysis-branch').value = data.deployBranch;
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
        const lname       = escHtml(p.name.toLowerCase());
        const displayName = escHtml(p.name.charAt(0).toUpperCase() + p.name.slice(1).toLowerCase());
        const pNameEsc    = escHtml(p.name);
        const div = document.createElement('div');
        div.className = 'radio-option';
        div.innerHTML = `
            <input type="radio" name="git-remote-provider" id="git-remote-${lname}" value="${pNameEsc}" ${idx === 0 ? 'checked' : ''}>
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
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function renderInline(text) {
    const esc = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    return esc
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
            // Blank line inside an OL: keep the OL if the next non-blank line is numbered.
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
        const lp    = escHtml(p.toLowerCase());
        const pEsc  = escHtml(p);
        return `<img src="/images/${lp}.svg" class="provider-logo provider-logo-badge" alt="${pEsc}" onerror="this.style.display='none'">
                <span class="badge badge-up">${pEsc}</span>`;
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
    const lp    = escHtml(provider.toLowerCase());
    const lpUpper = escHtml(provider.toUpperCase());
    cell.innerHTML = `
        <div style="display:flex; align-items:center; justify-content:space-between;">
            <div>
                <img src="/images/${lp}.svg" class="provider-logo provider-logo-badge" alt="${lp}" onerror="this.style.display='none'">
                <span class="badge badge-up">${lpUpper}</span>
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
    // Sync LLM status from server (provider key save state, current usage, etc.)
    await fetchLlmStatus();

    // Reflect current LLM in the System Status panel immediately
    if (_llmConfigured && _currentLlm) {
        renderLlmStatus(_currentLlm);
    }

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
