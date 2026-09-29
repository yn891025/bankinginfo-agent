const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);
let currentAnalysis = null;
const agentMeta = {
    web: {name: '웹 취약점 에이전트', description: '취약점 분석 API 연결 정보'},
    batch: {name: '배치 에이전트', description: '배치 분석 API 연결 정보'},
    prevention: {name: '장애예방 에이전트', description: '장애 징후 분석 API 연결 정보'}
};
let activeSettingsAgent = 'web';
let agentConfigs = loadAgentConfigs();

$$('.nav-item').forEach((button) => button.addEventListener('click', () => {
    $$('.nav-item').forEach((item) => item.classList.remove('active'));
    button.classList.add('active');
    const view = button.dataset.view;
    const views = {web: '#workspaceView', batch: '#batchView', prevention: '#preventionView', dashboard: '#dashboardView'};
    const titles = {web: '웹 취약점 에이전트', batch: '배치 에이전트', prevention: '장애예방 에이전트', dashboard: '대시보드'};
    Object.values(views).forEach((selector) => $(selector).classList.remove('active'));
    $(views[view]).classList.add('active');
    $('#pageTitle').textContent = titles[view];
    $('.sidebar').classList.remove('open');
    if (view === 'dashboard') loadDashboard();
    if (view === 'batch') loadBatchView();
}));

$('#menuButton').addEventListener('click', () => $('.sidebar').classList.toggle('open'));

$('#openAgentSettings').addEventListener('click', () => {
    activeSettingsAgent = 'web';
    $$('.settings-tab').forEach((tab) => tab.classList.toggle('active', tab.dataset.settingsAgent === 'web'));
    renderAgentConfig();
    $('#agentSettingsDialog').showModal();
    $('.sidebar').classList.remove('open');
});

$$('.settings-tab').forEach((tab) => tab.addEventListener('click', () => {
    stashAgentConfig();
    activeSettingsAgent = tab.dataset.settingsAgent;
    $$('.settings-tab').forEach((item) => item.classList.toggle('active', item === tab));
    renderAgentConfig();
}));

$('#toggleApiKey').addEventListener('click', () => {
    const input = $('#agentApiKey');
    const showing = input.type === 'text';
    input.type = showing ? 'password' : 'text';
    $('#toggleApiKey').textContent = showing ? '보기' : '숨김';
});

$('#agentSettingsForm').addEventListener('submit', (event) => {
    event.preventDefault();
    stashAgentConfig();
    localStorage.setItem('departmentGuideAgentConfigs', JSON.stringify(agentConfigs));
    $('#agentSettingsDialog').close('saved');
    showToast(`${agentMeta[activeSettingsAgent].name} API 설정을 저장했습니다.`);
});

function loadAgentConfigs() {
    const defaults = {
        web: {url: '', apiKey: '', model: '', timeout: '30'},
        batch: {url: '', apiKey: '', model: '', timeout: '30'},
        prevention: {url: '', apiKey: '', model: '', timeout: '30'}
    };
    try {
        const saved = JSON.parse(localStorage.getItem('departmentGuideAgentConfigs'));
        return saved ? {...defaults, ...saved} : defaults;
    } catch {
        return defaults;
    }
}

function stashAgentConfig() {
    agentConfigs[activeSettingsAgent] = {
        url: $('#agentApiUrl').value.trim(),
        apiKey: $('#agentApiKey').value.trim(),
        model: $('#agentModel').value.trim(),
        timeout: $('#agentTimeout').value || '30'
    };
}

function renderAgentConfig() {
    const config = agentConfigs[activeSettingsAgent];
    const meta = agentMeta[activeSettingsAgent];
    $('#settingsAgentName').textContent = meta.name;
    $('#settingsAgentDescription').textContent = meta.description;
    $('#agentApiUrl').value = config.url;
    $('#agentApiKey').value = config.apiKey;
    $('#agentModel').value = config.model;
    $('#agentTimeout').value = config.timeout;
    $('#agentApiKey').type = 'password';
    $('#toggleApiKey').textContent = '보기';
    const configured = Boolean(config.url && config.apiKey);
    $('#connectionState').textContent = configured ? '설정됨' : '미설정';
    $('#connectionState').classList.toggle('configured', configured);
}

$('#fillSample').addEventListener('click', () => {
    $('#vulnId').value = 'WEB-2026-021';
    $('#vulnTitle').value = 'SQL Injection 입력값 검증 미흡';
    $('#systemName').value = '인터넷뱅킹 포털';
    $('#targetUrl').value = 'https://bank.example.com/api/accounts';
    $('#symptom').value = '고객번호 파라미터에 특수문자 입력 시 DB 오류 메시지가 노출됩니다.';
    $('#asIsCode').value = 'var query = "SELECT * FROM account WHERE customer_id = " + customerId;\nreturn execute(query);';
});

$('#analysisForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    setStep(2);
    $('#inputPanel').classList.add('hidden');
    $('#analysisLoading').classList.remove('hidden');
    const messages = ['사내 위키에서 유사 사례를 검색하는 중입니다.', '소스 코드의 취약 구간을 분석하는 중입니다.', '영향 범위와 점검 항목을 정리하는 중입니다.'];
    let messageIndex = 0;
    const messageTimer = setInterval(() => {
        messageIndex = Math.min(messageIndex + 1, messages.length - 1);
        $('#loadingMessage').textContent = messages[messageIndex];
    }, 550);

    try {
        const response = await fetch('/api/web/analyze', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                vulnId: $('#vulnId').value,
                vulnTitle: $('#vulnTitle').value,
                systemName: $('#systemName').value,
                url: $('#targetUrl').value,
                checkQuarter: $('#checkQuarter').value,
                symptom: $('#symptom').value,
                asIsCode: $('#asIsCode').value
            })
        });
        if (!response.ok) throw new Error('분석 요청에 실패했습니다.');
        currentAnalysis = await response.json();
        await new Promise((resolve) => setTimeout(resolve, 1400));
        renderResults(currentAnalysis);
    } catch (error) {
        showToast(error.message);
        $('#inputPanel').classList.remove('hidden');
        setStep(1);
    } finally {
        clearInterval(messageTimer);
        $('#analysisLoading').classList.add('hidden');
    }
});

function renderResults(data) {
    $('#resultSummary').textContent = data.summary;
    $('#toBeCode').textContent = data.toBeCode;
    $('#impactLevel').textContent = data.impact.level;
    $('#recommendation').textContent = '권고: ' + data.recommendation;
    $('#similarCases').innerHTML = data.similarCases.map((item) => `
        <article class="case-card"><div><strong>${escapeHtml(item.title)}</strong><br><small>${escapeHtml(item.id)}</small></div><span class="match">${escapeHtml(item.similarity)} 일치</span><p>${escapeHtml(item.summary)}</p></article>`).join('');
    $('#impactTargets').innerHTML = data.impact.targets.map((item) => `<li>${escapeHtml(item)}</li>`).join('');
    $('#checklist').innerHTML = data.checklist.map((item, index) => `<label class="check-item"><input type="checkbox" data-check="${index}"><span>${escapeHtml(item)}</span></label>`).join('');
    $('#resultsPanel').classList.remove('hidden');
    setStep(3);
    window.scrollTo({top: 0, behavior: 'smooth'});
}

$('#copyCode').addEventListener('click', async () => {
    await navigator.clipboard.writeText($('#toBeCode').textContent);
    showToast('수정 코드를 복사했습니다.');
});

$('#backToInput').addEventListener('click', () => {
    $('#resultsPanel').classList.add('hidden');
    $('#inputPanel').classList.remove('hidden');
    setStep(1);
    window.scrollTo({top: 0, behavior: 'smooth'});
});

$('#completeAction').addEventListener('click', () => {
    setStep(4);
    $('#completionDialog').showModal();
});

$('#completionDialog').addEventListener('close', () => {
    if ($('#completionDialog').returnValue === 'cancel') setStep(3);
});

$('#completionForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!$('#actionResult').value.trim()) {
        $('#actionResult').focus();
        return;
    }
    try {
        const response = await fetch('/api/assetize', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({analysisId: currentAnalysis.analysisId, vulnTitle: currentAnalysis.vulnTitle, actionResult: $('#actionResult').value, note: $('#actionNote').value})
        });
        if (!response.ok) throw new Error('위키 저장에 실패했습니다.');
        const saved = await response.json();
        $('#documentId').textContent = saved.documentId;
        $('#documentPath').textContent = saved.path;
        $('#savedAt').textContent = saved.savedAt;
        $('#completionDialog').close('saved');
        setStep(5);
        $('#savedDialog').showModal();
    } catch (error) {
        showToast(error.message);
    }
});

$('#startNew').addEventListener('click', resetAnalysis);

function resetAnalysis() {
    $('#analysisForm').reset();
    $('#completionForm').reset();
    $('#resultsPanel').classList.add('hidden');
    $('#inputPanel').classList.remove('hidden');
    currentAnalysis = null;
    setStep(1);
    window.scrollTo({top: 0, behavior: 'smooth'});
}

function setStep(active) {
    $$('.step').forEach((step) => {
        const number = Number(step.dataset.step);
        step.classList.toggle('active', number === active);
        step.classList.toggle('complete', number < active);
    });
}

async function loadDashboard() {
    try {
        const response = await fetch('/api/dashboard/summary');
        const data = await response.json();
        $('#totalCases').textContent = data.totalCases;
        $('#webCases').textContent = data.webCases;
        $('#batchCases').textContent = data.batchCases;
        $('#completionRate').textContent = data.completionRate;
        $('#donut').querySelector('strong').textContent = data.totalCases;
        const webPercent = Math.round(data.webCases / data.totalCases * 100);
        $('#webPercent').textContent = webPercent + '%';
        $('#batchPercent').textContent = 100 - webPercent + '%';
        $('#donut').style.background = `conic-gradient(var(--blue) 0 ${webPercent}%, #9e86b5 ${webPercent}% 100%)`;
        const max = Math.max(...data.topics.map((topic) => topic.count));
        $('#topicList').innerHTML = data.topics.map((topic) => `<div class="topic"><div><span>${escapeHtml(topic.name)}</span><div class="bar"><i style="width:${topic.count / max * 100}%"></i></div></div><strong>${topic.count}</strong><small>${topic.change}</small></div>`).join('');
    } catch {
        showToast('대시보드 데이터를 불러오지 못했습니다.');
    }
}

function showToast(message) {
    $('#toast').textContent = message;
    $('#toast').classList.add('show');
    setTimeout(() => $('#toast').classList.remove('show'), 1800);
}

function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, (character) => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[character]));
}
