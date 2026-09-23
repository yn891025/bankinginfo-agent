const BATCH_STATUS = {
    OK: {label: '정상', cls: 'ok', icon: '✓'},
    RUNNING: {label: '수행중', cls: 'running', icon: '▶'},
    ERROR: {label: '오류', cls: 'error', icon: '!'},
    WAIT: {label: '대기', cls: 'wait', icon: '…'}
};
const STATUS_ORDER = ['OK', 'RUNNING', 'ERROR', 'WAIT'];
const FAVORITE_KEY = 'batchFavoriteJobs';
const MAX_FAVORITES = 5;
const NODE_W = 188;
const NODE_H = 62;
const COL_GAP = 70;
const ROW_GAP = 26;
const SVG_NS = 'http://www.w3.org/2000/svg';

const batchState = {
    page: 'dashboard',
    initialized: false,
    dashFilter: 'MAIN',
    dashData: null,
    favorites: loadFavorites(),
    owners: [],
    persons: new Set(['홍길동']),
    flow: null,
    positions: new Map(),
    highlight: null,
    summaryMode: false,
    selected: new Set(),
    logData: null,
    levels: new Set(['ERROR', 'WARN', 'INFO'])
};

/* ---------- 공통 ---------- */

function todayInputValue() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function toOdate(value) {
    return (value || todayInputValue()).replaceAll('-', '');
}

function toInputDate(odate) {
    return `${odate.slice(0, 4)}-${odate.slice(4, 6)}-${odate.slice(6, 8)}`;
}

function timeOf(dateTime) {
    return dateTime ? dateTime.slice(11) : '-';
}

function formatDuration(seconds) {
    if (seconds == null) return '-';
    const rounded = Math.round(seconds);
    return `${Math.floor(rounded / 60)}분 ${String(rounded % 60).padStart(2, '0')}초`;
}

function statusBadge(status) {
    const meta = BATCH_STATUS[status];
    return `<span class="status-badge status-${meta.cls}"><i aria-hidden="true">${meta.icon}</i>${meta.label}</span>`;
}

async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error('배치 데이터를 불러오지 못했습니다.');
    return response.json();
}

function loadFavorites() {
    try {
        const saved = JSON.parse(localStorage.getItem(FAVORITE_KEY));
        return Array.isArray(saved) ? saved.slice(0, MAX_FAVORITES) : [];
    } catch {
        return [];
    }
}

function saveFavorites() {
    try {
        localStorage.setItem(FAVORITE_KEY, JSON.stringify(batchState.favorites));
    } catch {
        // 저장소를 사용할 수 없는 환경에서는 현재 화면에서만 유지합니다.
    }
}

function loadBatchView() {
    if (batchState.initialized) return;
    batchState.initialized = true;
    ['#dashOdate', '#flowOdate', '#logOdate'].forEach((selector) => { $(selector).value = todayInputValue(); });
    loadBatchDashboard();
    loadOwners();
}

function showBatchPage(page) {
    batchState.page = page;
    $$('.batch-tab').forEach((tab) => tab.classList.toggle('active', tab.dataset.batchPage === page));
    const pages = {dashboard: '#batchDashboardPage', flow: '#batchFlowPage', log: '#batchLogPage'};
    Object.entries(pages).forEach(([key, selector]) => $(selector).classList.toggle('active', key === page));
    if (page === 'flow' && !batchState.flow) loadFlow();
    if (page === 'log') ensureLogJobOptions();
}

$$('.batch-tab').forEach((tab) => tab.addEventListener('click', () => showBatchPage(tab.dataset.batchPage)));

function openJobLog(jobName, odate) {
    showBatchPage('log');
    $('#logOdate').value = toInputDate(odate);
    $('#logFrom').value = '';
    $('#logTo').value = '';
    ensureLogJobOptions().then(() => {
        $('#logJob').value = jobName;
        loadLogs();
    });
    window.scrollTo({top: 0, behavior: 'smooth'});
}

/* ---------- PAGE 1. DASH BOARD ---------- */

async function loadBatchDashboard(isRefresh = false) {
    try {
        batchState.dashData = await fetchJson(`/api/batch/jobs?odate=${toOdate($('#dashOdate').value)}`);
        renderBatchDashboard();
        if (isRefresh) showToast('실시간 배치 상황을 갱신했습니다.');
    } catch (error) {
        showToast(error.message);
    }
}

$('#dashSearch').addEventListener('click', () => loadBatchDashboard());
$('#dashRefresh').addEventListener('click', () => loadBatchDashboard(true));

function renderBatchDashboard() {
    const {summary, jobs, refreshedAt, odate} = batchState.dashData;
    $('#dashTotal').textContent = `ODATE ${odate} · 전체 ${jobs.length}건`;
    $('#dashRefreshedAt').textContent = `최종 갱신 ${refreshedAt.slice(11)}`;

    const filters = [['MAIN', '주요 작업', mainJobs().length], ['ALL', '전체', jobs.length],
        ...STATUS_ORDER.map((status) => [status, BATCH_STATUS[status].label, summary[status]])];
    $('#statusFilter').innerHTML = filters.map(([key, label, count]) => `
        <button class="status-tab ${BATCH_STATUS[key] ? 'status-' + BATCH_STATUS[key].cls : ''} ${batchState.dashFilter === key ? 'active' : ''}" data-filter="${key}" role="tab" aria-selected="${batchState.dashFilter === key}">
            <span>${label}</span><strong>${count}</strong>
        </button>`).join('');

    const max = Math.max(1, ...STATUS_ORDER.map((status) => summary[status]));
    $('#statusChart').innerHTML = STATUS_ORDER.map((status) => {
        const meta = BATCH_STATUS[status];
        const count = summary[status];
        const percent = jobs.length ? Math.round(count / jobs.length * 100) : 0;
        return `<button class="status-row ${batchState.dashFilter === status ? 'active' : ''}" data-filter="${status}" title="${meta.label} ${count}건 (${percent}%)">
            <span class="status-row-label"><i class="dot status-${meta.cls}" aria-hidden="true"></i>${meta.label}</span>
            <span class="status-row-track"><i class="status-${meta.cls}" style="width:${count / max * 100}%"></i></span>
            <strong>${count}건</strong><small>${percent}%</small>
        </button>`;
    }).join('');

    $$('#statusFilter [data-filter], #statusChart [data-filter]').forEach((button) => button.addEventListener('click', () => {
        batchState.dashFilter = button.dataset.filter;
        renderBatchDashboard();
    }));
    renderJobTable();
}

function mainJobs() {
    return batchState.dashData.jobs.filter((job) => batchState.favorites.includes(job.jobName)
        || job.status === 'ERROR' || job.status === 'WAIT');
}

function renderJobTable() {
    const filter = batchState.dashFilter;
    const titles = {MAIN: ['주요 작업', '즐겨찾기 · 오류 · 대기 작업'], ALL: ['전체 작업', 'ODATE 기준 전체 배치 작업']};
    const [title, desc] = titles[filter] || [`${BATCH_STATUS[filter].label} 작업`, `상태가 '${BATCH_STATUS[filter].label}'인 작업`];
    $('#jobListTitle').textContent = title;
    $('#jobListDesc').textContent = desc;
    $('#favoriteCount').textContent = `★ ${batchState.favorites.length}/${MAX_FAVORITES}`;

    let jobs = filter === 'MAIN' ? mainJobs() : filter === 'ALL' ? batchState.dashData.jobs
        : batchState.dashData.jobs.filter((job) => job.status === filter);
    const priority = {ERROR: 0, RUNNING: 1, WAIT: 2, OK: 3};
    jobs = [...jobs].sort((a, b) => Number(isFavorite(b)) - Number(isFavorite(a)) || priority[a.status] - priority[b.status]);

    if (!jobs.length) {
        $('#jobTableBody').innerHTML = '<tr><td colspan="7" class="empty">해당 상태의 작업이 없습니다.</td></tr>';
        return;
    }
    $('#jobTableBody').innerHTML = jobs.map((job) => `
        <tr class="${job.status === 'ERROR' ? 'row-error' : ''}">
            <td class="star-col"><button class="star-button ${isFavorite(job) ? 'on' : ''}" data-favorite="${escapeHtml(job.jobName)}" aria-label="즐겨찾기" aria-pressed="${isFavorite(job)}">${isFavorite(job) ? '★' : '☆'}</button></td>
            <td><strong class="job-name">${escapeHtml(job.jobName)}</strong><small class="job-desc">${escapeHtml(job.description)}</small></td>
            <td>${statusBadge(job.status)}</td>
            <td class="mono">${timeOf(job.startTime)}</td>
            <td class="mono">${timeOf(job.endTime)}</td>
            <td class="mono">${job.status === 'RUNNING' ? formatDuration(job.durationSec) + ' 경과' : formatDuration(job.durationSec)}</td>
            <td><button class="log-button" data-log="${escapeHtml(job.jobName)}">로그</button></td>
        </tr>`).join('');

    $$('#jobTableBody [data-favorite]').forEach((button) => button.addEventListener('click', () => toggleFavorite(button.dataset.favorite)));
    $$('#jobTableBody [data-log]').forEach((button) => button.addEventListener('click', () => openJobLog(button.dataset.log, batchState.dashData.odate)));
}

function isFavorite(job) {
    return batchState.favorites.includes(job.jobName);
}

function toggleFavorite(jobName) {
    const favorites = batchState.favorites;
    if (favorites.includes(jobName)) {
        favorites.splice(favorites.indexOf(jobName), 1);
    } else if (favorites.length >= MAX_FAVORITES) {
        showToast(`즐겨찾기는 최대 ${MAX_FAVORITES}개까지 등록할 수 있습니다.`);
        return;
    } else {
        favorites.push(jobName);
    }
    saveFavorites();
    renderBatchDashboard();
}

/* ---------- PAGE 2. FLOW CHART ---------- */

async function loadOwners() {
    try {
        batchState.owners = await fetchJson('/api/batch/owners');
        renderPersonChips();
    } catch (error) {
        showToast(error.message);
    }
}

function renderPersonChips() {
    $('#personChips').innerHTML = batchState.owners.map((person) => `
        <button class="person-chip ${batchState.persons.has(person) ? 'active' : ''}" data-person="${escapeHtml(person)}" aria-pressed="${batchState.persons.has(person)}">${escapeHtml(person)}</button>`).join('');
    $$('#personChips [data-person]').forEach((chip) => chip.addEventListener('click', () => {
        const person = chip.dataset.person;
        if (batchState.persons.has(person)) batchState.persons.delete(person); else batchState.persons.add(person);
        renderPersonChips();
    }));
}

$('#flowSearch').addEventListener('click', () => loadFlow());

async function loadFlow() {
    if (!batchState.persons.size) {
        showToast('담당자를 한 명 이상 선택해 주세요.');
        return;
    }
    const params = new URLSearchParams({odate: toOdate($('#flowOdate').value)});
    batchState.persons.forEach((person) => params.append('persons', person));
    $$('.role-checks input:checked').forEach((input) => params.append('roles', input.value));
    try {
        batchState.flow = await fetchJson(`/api/batch/flow?${params}`);
        batchState.highlight = null;
        batchState.selected.clear();
        renderFlow();
        renderSummary();
    } catch (error) {
        showToast(error.message);
    }
}

function layoutFlow(nodes, edges) {
    const names = nodes.map((node) => node.job.jobName);
    const preds = new Map(names.map((name) => [name, []]));
    edges.forEach((edge) => preds.get(edge.to).push(edge.from));

    const level = new Map();
    const levelOf = (name) => {
        if (!level.has(name)) level.set(name, preds.get(name).length ? Math.max(...preds.get(name).map(levelOf)) + 1 : 0);
        return level.get(name);
    };
    names.forEach(levelOf);

    const columns = [];
    names.forEach((name) => (columns[level.get(name)] ||= []).push(name));
    const positions = new Map();
    columns.forEach((column, col) => {
        // 선행 노드의 평균 위치 순으로 정렬해 선이 덜 교차하도록 배치합니다.
        const weight = (name) => {
            const ys = preds.get(name).map((pred) => positions.get(pred).y);
            return ys.length ? ys.reduce((sum, y) => sum + y, 0) / ys.length : 0;
        };
        column.sort((a, b) => weight(a) - weight(b));
        column.forEach((name, row) => positions.set(name, {x: 24 + col * (NODE_W + COL_GAP), y: 24 + row * (NODE_H + ROW_GAP)}));
    });
    const width = 48 + columns.length * NODE_W + (columns.length - 1) * COL_GAP;
    const height = 48 + Math.max(...columns.map((column) => column.length)) * (NODE_H + ROW_GAP) - ROW_GAP;
    return {positions, preds, width, height};
}

function svgEl(tag, attrs = {}, text) {
    const element = document.createElementNS(SVG_NS, tag);
    Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, value));
    if (text != null) element.textContent = text;
    return element;
}

function renderFlow() {
    const svg = $('#flowSvg');
    svg.innerHTML = '';
    const {nodes, edges} = batchState.flow;
    if (!nodes.length) {
        svg.setAttribute('width', 400);
        svg.setAttribute('height', 80);
        svg.append(svgEl('text', {x: 24, y: 44, class: 'flow-empty'}, '조회 조건에 해당하는 배치 작업이 없습니다.'));
        return;
    }
    const layout = layoutFlow(nodes, edges);
    batchState.positions = layout.positions;
    batchState.preds = layout.preds;
    svg.setAttribute('width', layout.width);
    svg.setAttribute('height', layout.height);

    const defs = svgEl('defs');
    const marker = svgEl('marker', {id: 'flowArrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse'});
    marker.append(svgEl('path', {d: 'M0,0 L10,5 L0,10 z', class: 'flow-arrow'}));
    defs.append(marker);
    svg.append(defs);

    const edgeLayer = svgEl('g', {class: 'edge-layer'});
    edges.forEach((edge) => {
        const from = layout.positions.get(edge.from);
        const to = layout.positions.get(edge.to);
        const x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2, x2 = to.x - 2, y2 = to.y + NODE_H / 2;
        const mid = (x1 + x2) / 2;
        edgeLayer.append(svgEl('path', {d: `M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2},${y2}`, class: 'flow-edge', 'data-from': edge.from, 'data-to': edge.to, 'marker-end': 'url(#flowArrow)'}));
    });
    svg.append(edgeLayer);

    const nodeLayer = svgEl('g', {class: 'node-layer'});
    nodes.forEach(({job, external}) => {
        const {x, y} = layout.positions.get(job.jobName);
        const meta = BATCH_STATUS[job.status];
        const group = svgEl('g', {class: `flow-node status-${meta.cls}${external ? ' external' : ''}`, transform: `translate(${x},${y})`, 'data-job': job.jobName, tabindex: 0});
        group.append(svgEl('rect', {class: 'node-box', width: NODE_W, height: NODE_H, rx: 8}));
        group.append(svgEl('rect', {class: 'node-stripe', width: 5, height: NODE_H - 2, x: 1, y: 1, rx: 2}));
        group.append(svgEl('text', {class: 'node-title', x: 16, y: 23}, job.jobName));
        group.append(svgEl('text', {class: 'node-desc', x: 16, y: 43}, `${job.description} · ${job.owner}`));
        group.append(svgEl('text', {class: 'node-status', x: NODE_W - 12, y: 23, 'text-anchor': 'end'}, `${meta.icon} ${meta.label}`));
        const check = svgEl('g', {class: 'node-check', transform: `translate(${NODE_W - 24},${NODE_H - 24})`});
        check.append(svgEl('rect', {width: 16, height: 16, rx: 3}));
        check.append(svgEl('path', {d: 'M4 8.5 L7 11.5 L12.5 5'}));
        group.append(check);
        nodeLayer.append(group);
    });
    svg.append(nodeLayer);
    svg.append(svgEl('rect', {class: 'drag-box hidden', id: 'dragBox'}));
    applyFlowClasses();
}

function jobByName(name) {
    return batchState.flow.nodes.find((node) => node.job.jobName === name).job;
}

function ancestorsOf(name) {
    const result = new Set([name]);
    const stack = [name];
    while (stack.length) {
        batchState.preds.get(stack.pop()).forEach((pred) => {
            if (!result.has(pred)) {
                result.add(pred);
                stack.push(pred);
            }
        });
    }
    return result;
}

function applyFlowClasses() {
    const svg = $('#flowSvg');
    const highlight = batchState.highlight;
    svg.classList.toggle('highlighting', Boolean(highlight));
    svg.classList.toggle('summary-mode', batchState.summaryMode);
    svg.querySelectorAll('.flow-node').forEach((node) => {
        node.classList.toggle('hl', Boolean(highlight && highlight.has(node.dataset.job)));
        node.classList.toggle('selected', batchState.selected.has(node.dataset.job));
    });
    svg.querySelectorAll('.flow-edge').forEach((edge) => {
        edge.classList.toggle('hl', Boolean(highlight && highlight.has(edge.dataset.from) && highlight.has(edge.dataset.to)));
    });
}

let flowClickTimer = null;
let dragStart = null;

$('#flowSvg').addEventListener('click', (event) => {
    const node = event.target.closest('.flow-node');
    clearTimeout(flowClickTimer);
    flowClickTimer = setTimeout(() => {
        if (!node) {
            if (!batchState.summaryMode && batchState.highlight) {
                batchState.highlight = null;
                applyFlowClasses();
            }
            return;
        }
        const name = node.dataset.job;
        if (batchState.summaryMode) {
            if (batchState.selected.has(name)) batchState.selected.delete(name); else batchState.selected.add(name);
            renderSummary();
        } else if (jobByName(name).status === 'ERROR') {
            const same = batchState.highlight && batchState.highlight.has(name) && batchState.highlightRoot === name;
            batchState.highlight = same ? null : ancestorsOf(name);
            batchState.highlightRoot = same ? null : name;
            if (!same) showToast(`${name}의 선행 작업 ${batchState.highlight.size - 1}건을 원인 구간으로 강조했습니다.`);
        } else {
            batchState.highlight = null;
        }
        applyFlowClasses();
    }, 220);
});

$('#flowSvg').addEventListener('dblclick', (event) => {
    const node = event.target.closest('.flow-node');
    if (!node) return;
    clearTimeout(flowClickTimer);
    openJobLog(node.dataset.job, batchState.flow.odate);
});

$('#flowSvg').addEventListener('keydown', (event) => {
    const node = event.target.closest('.flow-node');
    if (node && event.key === 'Enter') openJobLog(node.dataset.job, batchState.flow.odate);
});

$('#flowSvg').addEventListener('mousemove', (event) => {
    const node = event.target.closest('.flow-node');
    const tooltip = $('#flowTooltip');
    if (!node || dragStart) {
        tooltip.classList.add('hidden');
        return;
    }
    const job = jobByName(node.dataset.job);
    tooltip.innerHTML = `
        <strong>${escapeHtml(job.jobName)}</strong><span class="tooltip-desc">${escapeHtml(job.description)} · ${escapeHtml(job.group)}</span>
        <dl>
            <dt>상태</dt><dd>${statusBadge(job.status)}</dd>
            <dt>담당자</dt><dd>${escapeHtml(job.owner)}</dd>
            <dt>부담당자</dt><dd>${escapeHtml(job.subOwner)}</dd>
            <dt>책임자</dt><dd>${escapeHtml(job.manager)}</dd>
            <dt>수행 시작</dt><dd>${timeOf(job.startTime)}</dd>
            <dt>수행 종료</dt><dd>${timeOf(job.endTime)}</dd>
            <dt>수행시간</dt><dd>${formatDuration(job.durationSec)}</dd>
            <dt>평균 수행</dt><dd>${formatDuration(job.avgDurationSec)}</dd>
            <dt>선행 작업</dt><dd>${job.predecessors.length ? job.predecessors.map(escapeHtml).join('<br>') : '없음'}</dd>
        </dl>`;
    tooltip.classList.remove('hidden');
    const offset = 16;
    const left = Math.min(event.clientX + offset, window.innerWidth - tooltip.offsetWidth - 12);
    const top = Math.min(event.clientY + offset, window.innerHeight - tooltip.offsetHeight - 12);
    tooltip.style.left = `${Math.max(12, left)}px`;
    tooltip.style.top = `${Math.max(12, top)}px`;
});

$('#flowSvg').addEventListener('mouseleave', () => $('#flowTooltip').classList.add('hidden'));

function svgPoint(event) {
    const rect = $('#flowSvg').getBoundingClientRect();
    return {x: event.clientX - rect.left, y: event.clientY - rect.top};
}

$('#flowSvg').addEventListener('mousedown', (event) => {
    if (!batchState.summaryMode || event.button !== 0 || event.target.closest('.flow-node')) return;
    event.preventDefault();
    dragStart = svgPoint(event);
});

window.addEventListener('mousemove', (event) => {
    if (!dragStart) return;
    const point = svgPoint(event);
    const box = $('#dragBox');
    box.classList.remove('hidden');
    box.setAttribute('x', Math.min(point.x, dragStart.x));
    box.setAttribute('y', Math.min(point.y, dragStart.y));
    box.setAttribute('width', Math.abs(point.x - dragStart.x));
    box.setAttribute('height', Math.abs(point.y - dragStart.y));
});

window.addEventListener('mouseup', (event) => {
    if (!dragStart) return;
    const end = svgPoint(event);
    const [x1, x2] = [Math.min(dragStart.x, end.x), Math.max(dragStart.x, end.x)];
    const [y1, y2] = [Math.min(dragStart.y, end.y), Math.max(dragStart.y, end.y)];
    dragStart = null;
    $('#dragBox').classList.add('hidden');
    if (x2 - x1 < 6 && y2 - y1 < 6) return;
    batchState.positions.forEach((pos, name) => {
        if (pos.x < x2 && pos.x + NODE_W > x1 && pos.y < y2 && pos.y + NODE_H > y1) batchState.selected.add(name);
    });
    renderSummary();
});

$('#flowSummaryToggle').addEventListener('click', () => {
    batchState.summaryMode = !batchState.summaryMode;
    batchState.highlight = null;
    $('#flowSummaryToggle').classList.toggle('active', batchState.summaryMode);
    $('#flowSummaryToggle').textContent = batchState.summaryMode ? '요약 닫기' : '요약';
    $('#flowSummary').classList.toggle('hidden', !batchState.summaryMode);
    $('#flowHint').textContent = batchState.summaryMode
        ? '노드 클릭: 체크 선택 · 빈 영역 드래그: 범위 선택'
        : '오류 노드 클릭: 원인 구간 강조 · 더블 클릭: 실행 로그';
    if (!batchState.summaryMode) batchState.selected.clear();
    renderSummary();
});

$('#summaryClear').addEventListener('click', () => {
    batchState.selected.clear();
    renderSummary();
});

function renderSummary() {
    if (batchState.flow) applyFlowClasses();
    const jobs = [...batchState.selected].map(jobByName);
    const total = jobs.reduce((sum, job) => sum + job.avgDurationSec, 0);
    $('#summaryCount').textContent = `${jobs.length}건`;
    $('#summaryAvg').textContent = jobs.length ? formatDuration(total / jobs.length) : '-';
    $('#summaryTotal').textContent = jobs.length ? formatDuration(total) : '-';
    $('#summaryList').innerHTML = jobs.length
        ? jobs.map((job) => `<li><div><strong>${escapeHtml(job.jobName)}</strong><small>${escapeHtml(job.description)}</small></div><span>평균 ${formatDuration(job.avgDurationSec)}</span></li>`).join('')
        : '<li class="empty">선택된 작업이 없습니다.</li>';
}

/* ---------- PAGE 3. LOG ANALYTICS ---------- */

async function ensureLogJobOptions() {
    if ($('#logJob').options.length) return;
    try {
        const data = batchState.dashData || await fetchJson(`/api/batch/jobs?odate=${toOdate($('#logOdate').value)}`);
        $('#logJob').innerHTML = data.jobs.map((job) => `<option value="${escapeHtml(job.jobName)}">${escapeHtml(job.jobName)} · ${escapeHtml(job.description)}</option>`).join('');
    } catch (error) {
        showToast(error.message);
    }
}

$('#logSearch').addEventListener('click', () => loadLogs());

$$('#levelFilter .level-chip').forEach((chip) => chip.addEventListener('click', () => {
    const level = chip.dataset.level;
    if (batchState.levels.has(level)) batchState.levels.delete(level); else batchState.levels.add(level);
    chip.classList.toggle('active', batchState.levels.has(level));
    if (batchState.logData) renderLogs();
}));

async function loadLogs() {
    const jobName = $('#logJob').value;
    if (!jobName) return;
    $('#aiResult').innerHTML = '<div class="ai-loading"><div class="loader"><span></span><span></span><span></span></div><p>AI가 로그를 분석하고 있습니다.</p></div>';
    try {
        const params = new URLSearchParams({odate: toOdate($('#logOdate').value), jobName});
        batchState.logData = await fetchJson(`/api/batch/logs?${params}`);
        renderLogs();
        await new Promise((resolve) => setTimeout(resolve, 700));
        renderAnalysis();
    } catch (error) {
        $('#aiResult').innerHTML = '<p class="empty">분석 결과를 불러오지 못했습니다.</p>';
        showToast(error.message);
    }
}

function renderLogs() {
    const {job, lines} = batchState.logData;
    const from = $('#logFrom').value;
    const to = $('#logTo').value;
    const visible = lines.filter((line) => {
        const time = line.time.slice(11);
        return batchState.levels.has(line.level) && (!from || time >= from.padEnd(8, ':00')) && (!to || time <= to.padEnd(8, ':59'));
    });
    $('#logTitle').textContent = job.jobName;
    $('#logMeta').innerHTML = `${escapeHtml(job.description)} · ${statusBadge(job.status)} · ${timeOf(job.startTime)} ~ ${timeOf(job.endTime)}`;
    $('#logCount').textContent = `${visible.length} / ${lines.length}줄`;
    $('#logViewer').innerHTML = visible.length
        ? visible.map((line) => `<div class="log-line level-${line.level.toLowerCase()}"><time>${escapeHtml(line.time)}</time><b>${line.level}</b><span>${escapeHtml(line.message)}</span></div>`).join('')
        : '<p class="empty">선택한 조건에 해당하는 로그가 없습니다.</p>';
}

function renderAnalysis() {
    const {analysis} = batchState.logData;
    const severity = {HIGH: ['높음', 'error'], MEDIUM: ['보통', 'warn'], LOW: ['낮음', 'info'], NONE: ['이상 없음', 'ok']}[analysis.severity];
    $('#aiResult').innerHTML = `
        <div class="ai-severity sev-${severity[1]}"><span>위험도</span><strong>${severity[0]}</strong></div>
        <div class="ai-block"><h4>분석 요약</h4><p>${escapeHtml(analysis.summary)}</p></div>
        <div class="ai-block"><h4>원인 추정</h4><p>${escapeHtml(analysis.cause)}</p></div>
        <div class="ai-block"><h4>조치 가이드</h4><ol>${analysis.actions.map((action) => `<li>${escapeHtml(action)}</li>`).join('')}</ol></div>
        ${analysis.similarCases.length ? `<div class="ai-block"><h4>유사 조치 사례</h4>${analysis.similarCases.map((item) => `
            <article class="case-card"><div><strong>${escapeHtml(item.title)}</strong><br><small>${escapeHtml(item.id)}</small></div><span class="match">${escapeHtml(item.similarity)} 일치</span></article>`).join('')}</div>` : ''}`;
}
