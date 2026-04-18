// Mutable LLM state — updated after save so re-opens work without page reload
let _llmConfigured     = !!LLM_CONFIGURED;
let _currentLlm        = CURRENT_LLM;
let _llmBothConfigured = !!LLM_SELECT_PROVIDER;

// ── Select Provider Modal ─────────────────────────────────────────────────────

function openLlmSelectProviderModal() {
    document.getElementById('select-provider-alert-error').style.display = 'none';
    document.getElementById('select-provider-btn').disabled = false;
    document.getElementById('select-provider-btn').textContent = 'Select';
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

// ── Loki Modal ───────────────────────────────────────────────────────────────

async function saveLokiUrl() {
    const url   = document.getElementById('loki-url-input').value.trim();
    const btn   = document.getElementById('loki-save-btn');
    const errEl = document.getElementById('loki-alert-error');

    if (!url) {
        errEl.textContent = 'Please enter a Loki URL.';
        errEl.style.display = 'block';
        return;
    }

    btn.disabled = true;
    errEl.style.display = 'none';

    try {
        const res = await fetch('/api/loki/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url }),
        });
        if (res.ok) {
            document.getElementById('loki-modal').style.display = 'none';
            renderLokiStatus(url);
            await checkGitRemoteAndProceed();
        } else {
            errEl.textContent = 'Failed to save Loki URL. Please try again.';
            errEl.style.display = 'block';
            btn.disabled = false;
        }
    } catch (e) {
        errEl.textContent = 'A network error occurred.';
        errEl.style.display = 'block';
        btn.disabled = false;
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

function createAppItem(name) {
    const item = document.createElement('div');
    item.className = 'app-item';
    item.dataset.app = name;
    item.innerHTML = `
        <span class="app-item-name">${escHtml(name)}</span>
        <button class="app-item-delete" title="Delete">&#10005;</button>
    `;
    item.addEventListener('click', async function (e) {
        if (e.target.closest('.app-item-delete')) {
            openConfirmDeleteModal(name);
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
    } else if (document.getElementById('llm-modal').style.display === 'flex') {
        saveLlmConfig();
    } else if (document.getElementById('git-remote-modal').style.display === 'flex') {
        saveGitRemoteConfig();
    } else if (document.getElementById('loki-modal').style.display === 'flex') {
        saveLokiUrl();
    } else if (document.getElementById('select-provider-modal').style.display === 'flex') {
        submitSelectProvider();
    }
});

async function saveApp() {
    const name  = document.getElementById('add-app-input').value.trim();
    const btn   = document.getElementById('add-app-save-btn');
    const errEl = document.getElementById('add-app-alert-error');
    const sucEl = document.getElementById('add-app-alert-success');

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
            body: JSON.stringify({ name }),
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

// ── WebSocket ────────────────────────────────────────────────────────────────

const appFiringLists       = {};   // { appName: AnalyzeFiringRecord[] }  newest-first
const appSelectedFiringIdx = {};   // { appName: number }
const appCommitLists       = {};   // { appName: CodeReviewRecord[] }    newest-first
const appSelectedCommitIdx = {};   // { appName: number }
let stompClient = null;

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
    }, function () {
        stompClient = null;
        setTimeout(connectWebSocket, 5000);
    });
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

    if (existing) {
        if (selectedApp === appName) {
            // 이미 선택된 앱: Code Review 탭으로 전환 후 in-place 업데이트
            switchToTab('codereview');
            const content = document.getElementById('codereview-content');
            if (content) { content.innerHTML = buildCodeReviewHtml(appName); applyHighlighting(content); }
        } else {
            // 다른 앱이 선택 중: 렌더링 완료 후 Code Review 탭으로 전환 및 앱 활성화
            renderAppDetailFromLocal(appName);
            switchToTab('codereview');
            const content = document.getElementById('codereview-content');
            if (content) { content.innerHTML = buildCodeReviewHtml(appName); applyHighlighting(content); }
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            existing.classList.add('active');
            selectedApp = appName;
        }
    } else {
        // 신규 앱: 추가 후 깜빡임이 끝나면 렌더링 완료 후 Code Review 탭으로 전환 및 활성화
        const item = addAppToList(appName);
        item.addEventListener('animationend', () => {
            renderAppDetailFromLocal(appName);
            switchToTab('codereview');
            const content = document.getElementById('codereview-content');
            if (content) { content.innerHTML = buildCodeReviewHtml(appName); applyHighlighting(content); }
            document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            selectedApp = appName;
        }, { once: true });
    }
}

async function selectApp(item, appName) {
    document.querySelectorAll('.app-item').forEach(el => el.classList.remove('active'));
    item.classList.add('active');
    selectedApp = appName;
    await renderAppDetail(appName);
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
            <div class="layer-header">AI Analysis</div>
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
            <div class="layer-header">AI Code Review</div>
            <div class="credential-error-msg">&#9888; ${msg}</div>
        </div>`;
    }
    return `
        <div class="analysis-layer">
            <div class="layer-header">AI Code Review</div>
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
    return `
        <div class="app-detail-header">${escHtml(appName)}</div>
        <div class="tab-bar">
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
    await loadFiringList(appName);
    appSelectedFiringIdx[appName] = 0;
    const panel = document.getElementById('app-detail-panel');
    panel.innerHTML = buildAppDetailHtml(appName);
    applyHighlighting(panel);
    // Code Review is the default active tab — load its content immediately
    await loadAndRenderCodeReview(appName);
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
    }
});

function switchToTab(tabName) {
    document.querySelectorAll('.tab-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.tab === tabName);
    });
    ['exception', 'codereview'].forEach(t => {
        const pane = document.getElementById('tab-pane-' + t);
        if (pane) pane.style.display = (t === tabName ? 'block' : 'none');
    });
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

async function openLokiModal() {
    document.getElementById('loki-modal-close').style.display = '';
    document.getElementById('loki-alert-error').style.display = 'none';
    document.getElementById('loki-save-btn').disabled = false;

    // Pre-fill existing URL if configured
    const urlInput = document.getElementById('loki-url-input');
    urlInput.value = '';
    try {
        const res  = await fetch('/api/loki/status');
        const data = await res.json();
        if (data.lokiUrl) urlInput.value = data.lokiUrl;
    } catch (_) { /* proceed without pre-fill */ }

    document.getElementById('loki-modal').style.display = 'flex';
}

function closeLokiModal() {
    document.getElementById('loki-modal').style.display = 'none';
}

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
    const cell = document.getElementById('status-loki-url');
    if (lokiUrl) {
        cell.innerHTML = `
            <div style="display:flex; align-items:center; justify-content:space-between;">
                <div>
                    <img src="/images/loki.svg" class="provider-logo provider-logo-badge" alt="Loki">
                    <span class="badge badge-up">LOKI</span>
                    <span style="color:#3c763d; font-weight:600; margin-left:8px;">&#10003; Connected (${escHtml(lokiUrl)})</span>
                </div>
                <button class="btn-secondary" style="margin-top:0;" onclick="openLokiModal()">Reconfigure</button>
            </div>
        `;
    }
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
    // Step 2: check Loki
    try {
        const res  = await fetch('/api/loki/status');
        const data = await res.json();
        if (!data.isConfigured) {
            document.getElementById('loki-modal').style.display = 'flex';
            return;
        }
        renderLokiStatus(data.lokiUrl);
    } catch (_) {
        // If check fails, proceed anyway
    }

    // Step 3: check Git Remote → show modal if needed, or render status and proceed
    await checkGitRemoteAndProceed();
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

