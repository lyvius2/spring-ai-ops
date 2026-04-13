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

function openLlmModal(isReconfigure) {
    const closeBtn = document.getElementById('llm-modal-close');
    if (isReconfigure && CURRENT_LLM) {
        const radio = document.querySelector(`input[name="llm-provider"][value="${CURRENT_LLM}"]`);
        if (radio) radio.checked = true;
        document.getElementById('llm-modal-desc').textContent =
            'Update your LLM provider or API key.';
        document.getElementById('llm-save-btn').textContent = 'Update & Reconnect';
        closeBtn.style.display = 'block';
    } else {
        closeBtn.style.display = 'none';
    }
    hideLlmAlerts();
    document.getElementById('llm-api-key').value = '';
    document.getElementById('llm-modal').style.display = 'flex';
}

function closeLlmModal() {
    document.getElementById('llm-modal').style.display = 'none';
}

async function saveLlmConfig() {
    const llm    = document.querySelector('input[name="llm-provider"]:checked').value;
    const apiKey = document.getElementById('llm-api-key').value.trim();
    const btn    = document.getElementById('llm-save-btn');

    if (!apiKey) {
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
            setTimeout(() => location.reload(), 1200);
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
            showMainSection();
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
    if (e.key === 'Enter' && document.getElementById('add-app-modal').style.display === 'flex') {
        saveApp();
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
        setTimeout(connectWebSocket, 5000);
    });
}

function handleFiringRecord(record) {
    const appName = record.application;

    // Prepend to local list and reset selection to newest
    if (!appFiringLists[appName]) appFiringLists[appName] = [];
    appFiringLists[appName].unshift(record);
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

function handleCommitRecord(record) {
    const appName = record.application;

    // Prepend to local list and reset selection to newest
    if (!appCommitLists[appName]) appCommitLists[appName] = [];
    appCommitLists[appName].unshift(record);
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

function renderFiringListRows(appName) {
    const list        = appFiringLists[appName] || [];
    const selectedIdx = appSelectedFiringIdx[appName] ?? 0;
    if (list.length === 0) {
        return `<tr><td colspan="2" style="padding:14px;color:#999;text-align:center;">No firing records yet.</td></tr>`;
    }
    return list.map((rec, idx) =>
        `<tr class="${idx === selectedIdx ? 'active' : ''}" data-idx="${idx}">
            <td>${idx + 1}</td>
            <td>${formatOccupiedAt(rec.occupiedAt)}</td>
        </tr>`
    ).join('');
}

function renderFiringListSection(appName) {
    return `
        <div class="firing-list-section">
            <div class="layer-header">Firing List</div>
            <table class="firing-table">
                <thead><tr><th>#</th><th>Occurred At</th></tr></thead>
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

function renderCommitUrlSection(record) {
    if (!record) return '';
    const commits = record.commitSummaries;
    if (commits && commits.length > 0) {
        const rows = commits.map(c => {
            const msg = escHtml(c.message || '—');
            const url = escHtml(c.url || '');
            const sha = escHtml((c.id || '').substring(0, 7));
            return `<a class="commit-link" href="${url}" target="_blank" rel="noopener noreferrer">
                <span class="commit-sha">${sha}</span>
                <span class="commit-message-text">${msg}</span>
            </a>`;
        }).join('');
        return `
            <div class="commit-url-section">
                <div class="layer-header">Commits (${commits.length})</div>
                ${rows}
            </div>`;
    }
    // fallback: old records without commitSummaries
    const msg = escHtml(record.commitMessage || record.githubUrl || '—');
    const url = escHtml(record.githubUrl || '');
    return `
        <div class="commit-url-section">
            <div class="layer-header">Commit</div>
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
    return `
        <div class="analysis-layer">
            <div class="layer-header">AI Code Review</div>
            <div class="analysis-text markdown-body">${renderMarkdown(record.reviewResult || '')}</div>
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
        : `<div class="info-message">Please register <code>/webhook/github/${escHtml(appName)}</code> as the Webhook URL triggered on push events in your GitHub Repository.</div>`;
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
            <button class="tab-btn active" data-tab="exception">Exception Analysis</button>
            <button class="tab-btn"        data-tab="codereview">Code Review</button>
        </div>
        <div class="tab-content" id="tab-pane-exception">
            <div id="exception-layers">${renderAnalysisLayers(appName, record)}</div>
            ${renderFiringListSection(appName)}
        </div>
        <div class="tab-content" id="tab-pane-codereview" style="display:none;">
            <div id="codereview-content">
                <div class="list-placeholder">Loading commit records...</div>
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
    await loadFiringList(appName);
    appSelectedFiringIdx[appName] = 0;
    const panel = document.getElementById('app-detail-panel');
    panel.innerHTML = buildAppDetailHtml(appName);
    applyHighlighting(panel);
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
            const allowed = await checkGithubToken();
            if (!allowed) return;
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

// ── GitHub Token Modal ────────────────────────────────────────────────────────

async function checkGithubToken() {
    try {
        const res  = await fetch('/api/github/token/status');
        const data = await res.json();
        if (data.isConfigured) return true;
    } catch (_) {}
    openGithubTokenModal();
    return false;
}

function openGithubTokenModal() {
    document.getElementById('github-token-input').value = '';
    document.getElementById('github-token-alert-error').style.display = 'none';
    document.getElementById('github-token-save-btn').disabled = false;
    document.getElementById('github-token-save-btn').textContent = 'Save';
    document.getElementById('github-token-modal').style.display = 'flex';
    setTimeout(() => document.getElementById('github-token-input').focus(), 50);
}

function closeGithubTokenModal() {
    document.getElementById('github-token-modal').style.display = 'none';
    // 저장 없이 닫으면 Exception Analysis 탭으로 복귀
    switchToTab('exception');
}

async function saveGithubToken() {
    const token = document.getElementById('github-token-input').value.trim();
    const btn   = document.getElementById('github-token-save-btn');
    const errEl = document.getElementById('github-token-alert-error');

    if (!token) {
        errEl.textContent = 'Please enter a GitHub Access Token.';
        errEl.style.display = 'block';
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Saving...';
    errEl.style.display = 'none';

    try {
        const res  = await fetch('/api/github/token', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token }),
        });
        const data = await res.json();

        if (data.success) {
            document.getElementById('github-token-modal').style.display = 'none';
            switchToTab('codereview');
        } else {
            errEl.textContent = data.message || 'Failed to save token.';
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
    let inCode = false;
    let codeLang = '';
    let codeLines = [];
    let inTable = false;
    let tableRows = [];

    const closeList = () => {
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

    for (const raw of lines) {
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

        if (!line.trim()) { closeList(); continue; }

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
        // Unordered list
        } else if (/^- /.test(line)) {
            if (inOl) { html += '</ol>'; inOl = false; }
            if (!inUl) { html += '<ul>'; inUl = true; }
            html += `<li>${renderInline(line.slice(2))}</li>`;
        // Ordered list
        } else if (/^\d+\. /.test(line)) {
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

function renderLokiStatus(lokiUrl) {
    const cell = document.getElementById('status-loki-url');
    if (lokiUrl) {
        cell.innerHTML = `<span style="font-weight:600;">${escHtml(lokiUrl)}</span>&nbsp;<span class="badge badge-up">Connected</span>`;
    }
}

async function init() {
    // Step 1: LLM not configured → 상태에 따라 분기
    if (!LLM_CONFIGURED) {
        if (LLM_SELECT_PROVIDER) {
            openLlmSelectProviderModal();
        } else {
            openLlmModal(false);
        }
        return;
    }

    // Step 2: LLM configured → check Loki
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

    // Both configured → fade in main section
    showMainSection();
}

document.addEventListener('DOMContentLoaded', init);
