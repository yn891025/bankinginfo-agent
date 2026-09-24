const BATCH_STATUS = {
    OK: {label: '정상', cls: 'ok', icon: '✓'},
    RUNNING: {label: '수행중', cls: 'running', icon: '▶'},
    ERROR: {label: '오류', cls: 'error', icon: '!'},
    WAIT: {label: '대기', cls: 'wait', icon: '…'},
    NONE: {label: '미수행', cls: 'none', icon: '–'}
};
const STATUS_ORDER = ['OK', 'RUNNING', 'ERROR', 'WAIT'];
const FAVORITE_KEY = 'batchFavoriteJobs';
const MAX_FAVORITES = 5;
const NODE_W = 250;
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
    ownersLoading: null,
    persons: new Set(),
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

/** ODATE와 수행 일자가 다르면(익일 수행) 월-일을 함께 표시합니다. */
function runTimeOf(dateTime, odate) {
    if (!dateTime) return '-';
    return dateTime.slice(0, 10).replaceAll('-', '') === odate ? dateTime.slice(11) : `${dateTime.slice(5, 10)} ${dateTime.slice(11)}`;
}

function formatDuration(seconds) {
    if (seconds == null) return '-';
    const rounded = Math.round(seconds);
    return `${Math.floor(rounded / 60)}분 ${String(rounded % 60).padStart(2, '0')}초`;
}

function statusBadge(status, title) {
    const meta = BATCH_STATUS[status];
    const tip = title ? ` title="${escapeHtml(title)}"` : '';
    return `<span class="status-badge status-${meta.cls}"${tip}><i aria-hidden="true">${meta.icon}</i>${meta.label}</span>`;
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
    ['#dashOdate', '#flowOdate'].forEach((selector) => { $(selector).value = todayInputValue(); });
    loadBatchDashboard();
    batchState.ownersLoading = loadOwners();
}

function showBatchPage(page) {
    batchState.page = page;
    $$('.batch-tab').forEach((tab) => tab.classList.toggle('active', tab.dataset.batchPage === page));
    const pages = {dashboard: '#batchDashboardPage', flow: '#batchFlowPage', log: '#batchLogPage'};
    Object.entries(pages).forEach(([key, selector]) => $(selector).classList.toggle('active', key === page));
    if (page === 'flow' && !batchState.flow) loadFlow();
}

$$('.batch-tab').forEach((tab) => tab.addEventListener('click', () => showBatchPage(tab.dataset.batchPage)));

/* ---------- PAGE 1. DASH BOARD ---------- */

async function loadBatchDashboard(isRefresh = false) {
    try {
        batchState.dashData = await fetchJson(`/api/batch/jobs?odate=${toOdate($('#dashOdate').value)}`);
        pruneFavorites(batchState.dashData.definedJobs);
        renderBatchDashboard();
        if (isRefresh) showToast('실시간 배치 상황을 갱신했습니다.');
    } catch (error) {
        showToast(error.message);
    }
}

$('#dashSearch').addEventListener('click', () => loadBatchDashboard());
$('#dashRefresh').addEventListener('click', () => loadBatchDashboard(true));

function renderBatchDashboard() {
    const {summary, jobs, refreshedAt, odate, source} = batchState.dashData;
    const live = source?.mode === 'MCP';
    $('#dashTotal').textContent = `ODATE ${odate} · 전체 ${jobs.length}건 · Control-M 수행 이력 기준 (${live ? 'MCP 실시간' : 'CSV 파일'})`;
    const refreshed = $('#dashRefreshedAt');
    refreshed.textContent = live && source.error ? `MCP 연결 실패 · 마지막 데이터 표시 중` : `최종 갱신 ${refreshedAt.slice(11)}`;
    refreshed.title = live && source.error ? source.error : '';
    refreshed.classList.toggle('warn', Boolean(live && source.error));

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

/** 주요 작업: 즐겨찾기 · 오류 · 선행 작업 오류로 보류된 대기 작업 (선행조건 대기는 정상 상태이므로 제외) */
function mainJobs() {
    const errors = new Set(batchState.dashData.jobs.filter((job) => job.status === 'ERROR').map((job) => job.jobName));
    return batchState.dashData.jobs.filter((job) => isFavorite(job) || job.status === 'ERROR'
        || (job.status === 'WAIT' && job.predecessors.some((pred) => errors.has(pred))));
}

/** 더 이상 등록되지 않은 작업의 즐겨찾기를 정리합니다. */
function pruneFavorites(definedJobs) {
    const kept = batchState.favorites.filter((name) => definedJobs.includes(name));
    if (kept.length === batchState.favorites.length) return;
    batchState.favorites = kept;
    saveFavorites();
}

function renderJobTable() {
    const filter = batchState.dashFilter;
    const titles = {MAIN: ['주요 작업', '즐겨찾기 · 오류 · 선행 오류로 보류된 작업'], ALL: ['전체 작업', 'ODATE 기준 전체 배치 작업']};
    const [title, desc] = titles[filter] || [`${BATCH_STATUS[filter].label} 작업`, `상태가 '${BATCH_STATUS[filter].label}'인 작업`];
    $('#jobListTitle').textContent = title;
    $('#jobListDesc').textContent = desc;
    $('#favoriteCount').textContent = `★ ${batchState.favorites.length}/${MAX_FAVORITES}`;

    let jobs = filter === 'MAIN' ? mainJobs() : filter === 'ALL' ? batchState.dashData.jobs
        : batchState.dashData.jobs.filter((job) => job.status === filter);
    const priority = {ERROR: 0, RUNNING: 1, WAIT: 2, OK: 3};
    // 수행 시작 시각 오름차순(먼저 시작한 작업이 위), 미시작 작업은 아래에 상태 우선순위 순으로 둡니다.
    jobs = [...jobs].sort((a, b) => (a.startTime == null) - (b.startTime == null)
        || (a.startTime ?? '').localeCompare(b.startTime ?? '') || priority[a.status] - priority[b.status]);

    if (!jobs.length) {
        const {dataOdates} = batchState.dashData;
        const message = batchState.dashData.jobs.length
            ? (filter === 'MAIN' ? '오류나 보류된 작업이 없습니다. ☆를 눌러 즐겨찾기를 등록하면 여기에 표시됩니다.' : '해당 상태의 작업이 없습니다.')
            : `ODATE ${batchState.dashData.odate}의 수행 이력이 없습니다. (이력 보유 ODATE: ${dataOdates.join(', ') || '없음'})`;
        $('#jobTableBody').innerHTML = `<tr><td colspan="7" class="empty">${escapeHtml(message)}</td></tr>`;
        return;
    }
    const {odate} = batchState.dashData;
    $('#jobTableBody').innerHTML = jobs.map((job) => `
        <tr class="${job.status === 'ERROR' ? 'row-error' : ''}">
            <td class="star-col"><button class="star-button ${isFavorite(job) ? 'on' : ''}" data-favorite="${escapeHtml(job.jobName)}" aria-label="즐겨찾기" aria-pressed="${isFavorite(job)}">${isFavorite(job) ? '★' : '☆'}</button></td>
            <td><strong class="job-name">${escapeHtml(job.jobName)}</strong><small class="job-desc">${escapeHtml(job.description)}</small></td>
            <td>${statusBadge(job.status, job.ctmState && `Control-M: ${job.ctmState}`)}</td>
            <td class="mono">${runTimeOf(job.startTime, odate)}</td>
            <td class="mono">${runTimeOf(job.endTime, odate)}</td>
            <td class="mono">${job.status === 'RUNNING' ? formatDuration(job.durationSec) + ' 경과' : formatDuration(job.durationSec)}<small class="job-desc">평균 ${formatDuration(job.avgDurationSec)}</small></td>
            <td class="mono">${job.runCount ?? '-'}회</td>
        </tr>`).join('');

    $$('#jobTableBody [data-favorite]').forEach((button) => button.addEventListener('click', () => toggleFavorite(button.dataset.favorite)));
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
        // 목록에 없는 담당자 선택은 지우고, 선택이 없으면 첫 번째(정 담당자)를 기본 선택합니다.
        batchState.persons = new Set([...batchState.persons].filter((person) => batchState.owners.includes(person)));
        if (!batchState.persons.size && batchState.owners.length) batchState.persons.add(batchState.owners[0]);
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
    await batchState.ownersLoading;
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

/** SVG 텍스트가 maxWidth(px)를 넘으면 실제 렌더링 폭 기준으로 말줄임합니다. */
function fitText(element, maxWidth) {
    const full = element.textContent;
    if (element.getComputedTextLength() <= maxWidth) return;
    let low = 0, high = full.length;
    while (low < high) {
        const mid = Math.ceil((low + high) / 2);
        element.textContent = `${full.slice(0, mid)}…`;
        if (element.getComputedTextLength() <= maxWidth) low = mid; else high = mid - 1;
    }
    element.textContent = `${full.slice(0, low)}…`;
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
    const markerHl = svgEl('marker', {id: 'flowArrowHl', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 6, markerHeight: 6, orient: 'auto-start-reverse'});
    markerHl.append(svgEl('path', {d: 'M0,0 L10,5 L0,10 z', class: 'flow-arrow-hl'}));
    defs.append(marker, markerHl);
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
        const title = svgEl('text', {class: 'node-title', x: 16, y: 23}, job.jobName);
        const desc = svgEl('text', {class: 'node-desc', x: 16, y: 43}, job.owner === '-' ? job.description : `${job.description} · ${job.owner}`);
        const status = svgEl('text', {class: 'node-status', x: NODE_W - 12, y: 23, 'text-anchor': 'end'}, `${meta.icon} ${meta.label}`);
        group.append(title, desc, status);
        const check = svgEl('g', {class: 'node-check', transform: `translate(${NODE_W - 24},${NODE_H - 24})`});
        check.append(svgEl('rect', {width: 16, height: 16, rx: 3}));
        check.append(svgEl('path', {d: 'M4 8.5 L7 11.5 L12.5 5'}));
        group.append(check);
        nodeLayer.append(group);
    });
    svg.append(nodeLayer);
    // 글자 폭은 DOM에 붙은 뒤에 측정할 수 있습니다. 작업명은 상태 표시 앞까지, 설명은 체크박스 앞까지로 제한합니다.
    nodeLayer.querySelectorAll('.flow-node').forEach((node) => {
        const statusLeft = NODE_W - 12 - node.querySelector('.node-status').getComputedTextLength();
        fitText(node.querySelector('.node-title'), statusLeft - 8 - 16);
        fitText(node.querySelector('.node-desc'), NODE_W - 32 - 16);
    });
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
    svg.classList.toggle('highlight-error', Boolean(highlight && jobByName(batchState.highlightRoot).status === 'ERROR'));
    svg.classList.toggle('summary-mode', batchState.summaryMode);
    svg.querySelectorAll('.flow-node').forEach((node) => {
        node.classList.toggle('hl', Boolean(highlight && highlight.has(node.dataset.job)));
        node.classList.toggle('hl-root', Boolean(highlight && batchState.highlightRoot === node.dataset.job));
        node.classList.toggle('selected', batchState.selected.has(node.dataset.job));
    });
    svg.querySelectorAll('.flow-edge').forEach((edge) => {
        const on = Boolean(highlight && highlight.has(edge.dataset.from) && highlight.has(edge.dataset.to));
        edge.classList.toggle('hl', on);
        edge.setAttribute('marker-end', on ? 'url(#flowArrowHl)' : 'url(#flowArrow)');
        if (on) edge.parentNode.append(edge); // 강조선을 다른 선 위로 올립니다.
    });
}

let dragStart = null;

function selectFlowNode(node) {
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
    } else {
        // 모든 작업: 선행 작업 경로(노드·연결선)를 강조, 같은 노드를 다시 클릭하면 해제합니다.
        const same = batchState.highlight && batchState.highlightRoot === name;
        batchState.highlight = same ? null : ancestorsOf(name);
        batchState.highlightRoot = same ? null : name;
        if (!same) {
            const count = batchState.highlight.size - 1;
            const label = jobByName(name).status === 'ERROR' ? ' 원인 구간으로' : '';
            showToast(count ? `${name}의 선행 작업 ${count}건을${label} 강조했습니다.` : `${name}은 선행 작업이 없습니다.`);
        }
    }
    applyFlowClasses();
}

$('#flowSvg').addEventListener('click', (event) => selectFlowNode(event.target.closest('.flow-node')));

$('#flowSvg').addEventListener('keydown', (event) => {
    const node = event.target.closest('.flow-node');
    if (node && event.key === 'Enter') selectFlowNode(node);
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
            <dt>수행 시작</dt><dd>${runTimeOf(job.startTime, batchState.flow.odate)}</dd>
            <dt>수행 종료</dt><dd>${runTimeOf(job.endTime, batchState.flow.odate)}</dd>
            <dt>Control-M</dt><dd>${escapeHtml(job.ctmState ?? (job.status === 'NONE' ? '해당 ODATE 수행 이력 없음' : '-'))}${job.runCount ? ` · ${job.runCount}회` : ''}</dd>
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
        : '노드 클릭: 선행 작업 경로 강조';
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
    const total = jobs.reduce((sum, job) => sum + (job.avgDurationSec ?? 0), 0);
    $('#summaryCount').textContent = `${jobs.length}건`;
    $('#summaryAvg').textContent = jobs.length ? formatDuration(total / jobs.length) : '-';
    $('#summaryTotal').textContent = jobs.length ? formatDuration(total) : '-';
    $('#summaryList').innerHTML = jobs.length
        ? jobs.map((job) => `<li><div><strong>${escapeHtml(job.jobName)}</strong><small>${escapeHtml(job.description)}</small></div><span>평균 ${formatDuration(job.avgDurationSec)}</span></li>`).join('')
        : '<li class="empty">선택된 작업이 없습니다.</li>';
}

/* ---------- PAGE 3. LOG ANALYTICS ---------- */

const LOG_TIME = /(\d{4}[-/.]?\d{2}[-/.]?\d{2}[ T]?)?(\d{2}:\d{2}:\d{2})/;
const LOG_LEVEL = /\b(FATAL|ERROR|ERR|SEVERE|WARN|WARNING|INFO|DEBUG|TRACE)\b/i;
const LEVEL_ALIAS = {FATAL: 'ERROR', ERR: 'ERROR', SEVERE: 'ERROR', WARNING: 'WARN', DEBUG: 'INFO', TRACE: 'INFO'};

/**
 * 로그 텍스트를 줄 단위로 해석합니다. 레벨이 없는 줄 중 시각도 없는 줄(스택트레이스 등)은 앞 줄에 이어 붙이고,
 * 레벨을 찾지 못한 줄은 INFO로 봅니다.
 */
function parseLogText(text) {
    const lines = [];
    text.split(/\r?\n/).forEach((raw) => {
        if (!raw.trim()) return;
        const time = raw.match(LOG_TIME);
        const level = raw.match(LOG_LEVEL);
        const previous = lines[lines.length - 1];
        if (!time && !level && previous) {
            previous.message += `\n${raw}`;
            return;
        }
        const name = level ? level[1].toUpperCase() : 'INFO';
        lines.push({time: time ? time[0].trim() : '', clock: time ? time[2] : '', level: LEVEL_ALIAS[name] || name, message: raw});
    });
    return lines;
}

$('#logFile').addEventListener('change', async (event) => {
    const file = event.target.files[0];
    if (!file) return;
    $('#logText').value = await file.text();
    $('#logFileName').textContent = file.name;
    if (!$('#logJobName').value) $('#logJobName').value = file.name.replace(/\.[^.]+$/, '');
    event.target.value = '';
    analyzeLogs();
});

$('#logAnalyze').addEventListener('click', () => analyzeLogs());

$('#logClear').addEventListener('click', () => {
    $('#logText').value = '';
    $('#logFileName').textContent = '';
    batchState.logData = null;
    $('#logTitle').textContent = '실행 로그';
    $('#logMeta').textContent = '로그를 입력한 뒤 분석해 주세요.';
    $('#logCount').textContent = '';
    $('#logViewer').innerHTML = '';
    $('#aiResult').innerHTML = '<p class="empty">로그를 분석하면 결과가 표시됩니다.</p>';
});

['#logFrom', '#logTo'].forEach((selector) => $(selector).addEventListener('change', () => {
    if (batchState.logData) renderLogs();
}));

$$('#levelFilter .level-chip').forEach((chip) => chip.addEventListener('click', () => {
    const level = chip.dataset.level;
    if (batchState.levels.has(level)) batchState.levels.delete(level); else batchState.levels.add(level);
    chip.classList.toggle('active', batchState.levels.has(level));
    if (batchState.logData) renderLogs();
}));

function analyzeLogs() {
    const text = $('#logText').value;
    if (!text.trim()) {
        showToast('분석할 로그를 입력하거나 파일을 불러와 주세요.');
        return;
    }
    batchState.logData = {jobName: $('#logJobName').value.trim(), lines: parseLogText(text)};
    renderLogs();
    $('#aiResult').innerHTML = '<p class="empty">AI 로그 분석은 아직 연동되지 않았습니다.</p>';
}

function renderLogs() {
    const {jobName, lines} = batchState.logData;
    const from = $('#logFrom').value;
    const to = $('#logTo').value;
    const visible = lines.filter((line) => batchState.levels.has(line.level)
        && (!from || !line.clock || line.clock >= from.padEnd(8, ':00'))
        && (!to || !line.clock || line.clock <= to.padEnd(8, ':59')));
    const count = (level) => lines.filter((line) => line.level === level).length;
    $('#logTitle').textContent = jobName || '실행 로그';
    $('#logMeta').textContent = `ERROR ${count('ERROR')} · WARN ${count('WARN')} · INFO ${count('INFO')}`;
    $('#logCount').textContent = `${visible.length} / ${lines.length}줄`;
    $('#logViewer').innerHTML = visible.length
        ? visible.map((line) => `<div class="log-line level-${line.level.toLowerCase()}"><time>${escapeHtml(line.time || '-')}</time><b>${line.level}</b><span>${escapeHtml(line.message)}</span></div>`).join('')
        : `<p class="empty">${lines.length ? '선택한 조건에 해당하는 로그가 없습니다.' : '해석된 로그가 없습니다.'}</p>`;
}
