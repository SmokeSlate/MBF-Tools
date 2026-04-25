const COMMAND_PREFIX = '!s';
const CODE_TTL = 60 * 60 * 24 * 30; // 30 days

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method === 'POST') {
      return handleUpload(request, env, url, corsHeaders);
    }

    if (request.method === 'GET') {
      return handleGet(request, env, url, corsHeaders);
    }

    return jsonResponse({ ok: false, error: 'Method not allowed.' }, 405, corsHeaders);
  },
};

async function handleUpload(request, env, url, corsHeaders) {
  try {
    let payload;
    try {
      payload = await request.json();
    } catch {
      return jsonResponse({ ok: false, error: 'Request body was not valid JSON.' }, 400, corsHeaders);
    }

    if (!payload || typeof payload !== 'object') {
      return jsonResponse({ ok: false, error: 'Payload must be a JSON object.' }, 400, corsHeaders);
    }

    const code = await generateCode_(env);
    const analysis = analyzePayload_(payload);
    const baseUrl = `${url.protocol}//${url.host}`;

    const record = {
      code,
      createdAt: new Date().toISOString(),
      command: `${COMMAND_PREFIX} ${code}`,
      summary: analysis.summary,
      issues: analysis.issues,
      payload,
    };

    await env.LOGS.put(`log:${code}`, JSON.stringify(record), { expirationTtl: CODE_TTL });

    return jsonResponse({
      ok: true,
      code,
      command: record.command,
      summary: record.summary,
      viewerUrl: buildActionUrl_(baseUrl, 'view', code),
      summaryUrl: buildActionUrl_(baseUrl, 'summary', code),
      messageUrl: buildActionUrl_(baseUrl, 'message', code),
      dataUrl: buildActionUrl_(baseUrl, 'data', code),
    }, 200, corsHeaders);
  } catch (error) {
    return jsonResponse({ ok: false, error: error?.message || String(error) }, 500, corsHeaders);
  }
}

async function handleGet(request, env, url, corsHeaders) {
  const action = (url.searchParams.get('action') || '').trim().toLowerCase() || 'view';
  const code = (url.searchParams.get('code') || '').trim().toLowerCase();

  if (!code) {
    if (action === 'view') return htmlResponse(renderMissingCodePage_(), corsHeaders);
    return jsonResponse({ ok: false, error: 'Missing code.' }, 400, corsHeaders);
  }

  if (action === 'aifix') {
    return handleAiFix(env, url, code, corsHeaders);
  }

  const raw = await env.LOGS.get(`log:${code}`);
  if (!raw) {
    if (action === 'view') return htmlResponse(renderErrorPage_(`No shared log was found for code ${escapeHtml_(code)}.`), corsHeaders);
    return jsonResponse({ ok: false, error: `No shared log was found for code ${code}.` }, 404, corsHeaders);
  }

  const record = JSON.parse(raw);
  const baseUrl = `${url.protocol}//${url.host}`;

  switch (action) {
    case 'summary': {
      const format = (url.searchParams.get('format') || '').trim().toLowerCase();
      if (format === 'text') return textResponse(record.summary || 'No short summary is available.', corsHeaders);
      return jsonResponse({
        ok: true,
        code: record.code,
        summary: record.summary,
        issues: record.issues,
        currentGuideStep: record.payload?.setup?.currentGuideStep || '',
        createdAt: record.createdAt,
      }, 200, corsHeaders);
    }
    case 'message':
      return textResponse(buildMessageText_(record, baseUrl), corsHeaders);
    case 'data':
      return jsonResponse({
        ok: true,
        code: record.code,
        createdAt: record.createdAt,
        summary: record.summary,
        issues: record.issues,
        payload: record.payload,
      }, 200, corsHeaders);
    case 'view':
    default:
      return htmlResponse(renderViewerPage_(record, baseUrl), corsHeaders);
  }
}

async function handleAiFix(env, url, code, corsHeaders) {
  const raw = await env.LOGS.get(`log:${code}`);
  if (!raw) {
    return jsonResponse({ ok: false, error: `No shared log was found for code ${code}.` }, 404, corsHeaders);
  }

  try {
    const record = JSON.parse(raw);
    const setup = record.payload?.setup || {};
    const beatSaber = record.payload?.beatSaber || {};
    const mods = record.payload?.mods || {};
    const issues = Array.isArray(record.issues) ? record.issues : [];

    const prompt = [
      'You are a helpful assistant for Meta Quest headset setup with MBF (Mods Before Friday).',
      'A user has shared their diagnostics. Provide clear, numbered, step-by-step fix instructions.',
      'Be concise and specific to Meta Quest / ADB / Beat Saber modding.',
      '',
      `Issues detected: ${issues.length > 0 ? issues.join('; ') : 'none'}`,
      `ADB connected: ${setup.connectedDevice ? 'yes' : 'no'}`,
      `Developer mode: ${setup.developerModeEnabled ? 'on' : 'off'}`,
      `Wireless debugging: ${setup.wirelessDebuggingEnabled ? 'on' : 'off'}`,
      `Beat Saber installed: ${beatSaber.installed ? 'yes' : 'no'}`,
      `Mods count: ${mods.count || 0}`,
      `Current guide step: ${setup.currentGuideStep || 'none'}`,
    ].join('\n');

    const aiRes = await fetch(`https://gen.pollinations.ai/text/${encodeURIComponent(prompt)}`, {
      signal: AbortSignal.timeout(20000),
    });

    if (!aiRes.ok) throw new Error(`Pollinations returned ${aiRes.status}`);

    const fix = await aiRes.text();
    return jsonResponse({ ok: true, code, fix: fix.trim() }, 200, corsHeaders);
  } catch (error) {
    return jsonResponse({ ok: false, error: error?.message || 'AI fix failed.' }, 500, corsHeaders);
  }
}

async function generateCode_(env) {
  for (let i = 0; i < 10; i++) {
    const code = Math.random().toString(36).slice(2, 7).toLowerCase();
    const existing = await env.LOGS.get(`log:${code}`);
    if (!existing) return code;
  }
  return crypto.randomUUID().replace(/-/g, '').slice(0, 8).toLowerCase();
}

function buildActionUrl_(baseUrl, action, code) {
  return `${baseUrl}?action=${encodeURIComponent(action)}&code=${encodeURIComponent(code)}`;
}

function analyzePayload_(payload) {
  const issues = [];
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const app = payload.app || {};

  if (!setup.developerModeEnabled) issues.push('Android developer mode is not enabled.');
  if (!setup.wirelessDebuggingEnabled) issues.push('Wireless Debugging is turned off.');
  if (!setup.connectedDevice) issues.push('No authorized ADB headset connection is active.');
  if (beatSaber.packageName && !beatSaber.installed) {
    issues.push(`Beat Saber package ${beatSaber.packageName} is not installed.`);
  }
  if (Number(logStats.errorCount || 0) > 0) {
    issues.push(`${logStats.errorCount} error log lines were captured.`);
  } else if (Number(logStats.warnCount || 0) > 0) {
    issues.push(`${logStats.warnCount} warning log lines were captured.`);
  }
  if (beatSaber.installed && Number(mods.count || 0) === 0) {
    issues.push('Beat Saber is installed but no current mods were detected.');
  }
  const interesting = Array.isArray(beatSaberLogs.interesting) ? beatSaberLogs.interesting : [];
  if (interesting.length > 0) {
    issues.push('Beat Saber logs include crash, exception, or error text.');
  } else if (Number(beatSaberLogs.lineCount || 0) > 0) {
    issues.push(`Beat Saber log bundle included ${beatSaberLogs.lineCount} matching line(s).`);
  }
  if (setup.currentGuideStep && !app.setupComplete) {
    issues.push(`User is currently on setup step: ${setup.currentGuideStep}.`);
  }

  let summary;
  if (issues.length > 0) {
    summary = issues.slice(0, 3).join(' ');
  } else if (setup.connectedDevice && beatSaber.installed) {
    summary = 'Setup looks healthy. ADB is connected and Beat Saber is installed.';
  } else {
    summary = 'No obvious blocker was detected from the uploaded diagnostics.';
  }

  return { summary, issues };
}

function buildMessageText_(record, baseUrl) {
  const payload = record.payload || {};
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const viewerUrl = buildActionUrl_(baseUrl, 'view', record.code);
  const summaryUrl = buildActionUrl_(baseUrl, 'summary', record.code);

  const modItems = Array.isArray(mods.items) ? mods.items.slice(0, 15) : [];
  const modSuffix = Array.isArray(mods.items) && mods.items.length > 15 ? ` (+${mods.items.length - 15} more)` : '';
  const issueItems = Array.isArray(record.issues) ? record.issues.slice(0, 6) : [];
  const issueBlock = issueItems.length > 0 ? issueItems.map(i => `- ${i}`).join('\n') : '- No explicit issues were inferred.';
  const modsLine = modItems.length > 0 ? modItems.join(', ') + modSuffix : 'none detected';

  return [
    `# ${COMMAND_PREFIX} ${record.code}`,
    '',
    '**Summary**',
    record.summary || 'No short summary is available.',
    '',
    '**Issues**',
    issueBlock,
    '',
    '**Status**',
    `- Created: ${record.createdAt || ''}`,
    `- Current step: ${setup.currentGuideStep || 'Not in setup'}`,
    `- ADB: ${setup.connectedDevice ? `Connected to ${setup.connectedDevice}` : 'Not connected'}`,
    `- Developer mode: ${setup.developerModeEnabled ? 'On' : 'Off'}`,
    `- Wireless Debugging: ${setup.wirelessDebuggingEnabled ? 'On' : 'Off'}`,
    `- Beat Saber: ${beatSaber.installed ? `${beatSaber.packageName || 'installed'} ${beatSaber.versionName || ''}`.trim() : `${beatSaber.packageName || 'com.beatgames.beatsaber'} not installed`}`,
    `- Mods (${Number(mods.count || 0)}): ${modsLine}`,
    `- Beat Saber logs: ${Number(beatSaberLogs.lineCount || 0)} line(s), ${Number(beatSaberLogs.interestingCount || 0)} interesting`,
    `- App logs: ${Number(logStats.errorCount || 0)} error(s), ${Number(logStats.warnCount || 0)} warning(s), ${Number(logStats.lineCount || 0)} line(s)`,
    '',
    '**Links**',
    `- Viewer: ${viewerUrl}`,
    `- Summary API: ${summaryUrl}`,
  ].join('\n');
}

function jsonResponse(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
  });
}

function textResponse(text, extraHeaders = {}) {
  return new Response(String(text || ''), {
    status: 200,
    headers: { 'Content-Type': 'text/plain; charset=utf-8', ...extraHeaders },
  });
}

function htmlResponse(html, extraHeaders = {}) {
  return new Response(html, {
    status: 200,
    headers: { 'Content-Type': 'text/html; charset=utf-8', ...extraHeaders },
  });
}

function escapeHtml_(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderViewerPage_(record, baseUrl) {
  const payload = record.payload || {};
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const recentProblems = Array.isArray(logStats.recentProblems) ? logStats.recentProblems : [];
  const modItems = Array.isArray(mods.items) ? mods.items : [];
  const beatSaberLogLines = Array.isArray(beatSaberLogs.lines) ? beatSaberLogs.lines : [];
  const beatSaberInteresting = Array.isArray(beatSaberLogs.interesting) ? beatSaberLogs.interesting : [];
  const logsText = String(payload.logsText || '');
  const rawJson = JSON.stringify(record, null, 2);
  const aifixUrl = buildActionUrl_(baseUrl, 'aifix', record.code);

  const issueList = Array.isArray(record.issues) && record.issues.length > 0
    ? `<ul>${record.issues.map(i => `<li>${escapeHtml_(i)}</li>`).join('')}</ul>`
    : '<p class="muted">No explicit issues were inferred.</p>';
  const problemList = recentProblems.length > 0
    ? `<ul>${recentProblems.map(l => `<li>${escapeHtml_(l)}</li>`).join('')}</ul>`
    : '<p class="muted">No recent warning or error lines were captured.</p>';
  const modList = modItems.length > 0
    ? `<ul>${modItems.map(i => `<li>${escapeHtml_(i)}</li>`).join('')}</ul>`
    : '<p class="muted">No installed mods were detected.</p>';
  const beatSaberProblems = beatSaberInteresting.length > 0
    ? `<ul>${beatSaberInteresting.map(l => `<li>${escapeHtml_(l)}</li>`).join('')}</ul>`
    : '<p class="muted">No Beat Saber error-like lines were detected.</p>';

  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>MBF Tools Debug Viewer</title>
    <style>
      :root { color-scheme: dark; }
      body { margin:0; font-family:"Segoe UI",sans-serif; background:radial-gradient(circle at top,rgba(54,139,255,0.22),transparent 34%),linear-gradient(180deg,#081626 0%,#0f2134 100%); color:#eaf4ff; }
      .page { max-width:1200px; margin:0 auto; padding:24px; }
      .hero,.panel { background:rgba(6,17,30,0.82); border:1px solid rgba(146,198,255,0.22); border-radius:20px; padding:20px; box-shadow:0 16px 48px rgba(0,0,0,0.22); }
      .hero { margin-bottom:16px; }
      .grid { display:grid; gap:16px; grid-template-columns:repeat(auto-fit,minmax(320px,1fr)); }
      h1,h2 { margin:0 0 10px 0; } h2 { font-size:20px; }
      h3 { margin:0 0 8px 0; font-size:14px; color:#d6e7f8; text-transform:uppercase; letter-spacing:.08em; }
      p,li { line-height:1.5; } .muted { color:#a7bfd8; }
      .summary { font-size:18px; color:#fff; }
      .stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:12px; margin-top:14px; }
      .stat { background:rgba(23,43,67,0.9); border-radius:14px; padding:12px; }
      .hero-top { display:flex; gap:16px; align-items:flex-start; justify-content:space-between; flex-wrap:wrap; }
      .hero-meta { min-width:280px; flex:0 1 320px; }
      .status-pills { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
      .pill { display:inline-flex; align-items:center; gap:8px; border-radius:999px; padding:8px 12px; font-size:13px; background:rgba(18,42,68,0.95); border:1px solid rgba(146,198,255,0.18); }
      .pill.ok { border-color:rgba(112,224,176,0.28); color:#dffbea; }
      .pill.warn { border-color:rgba(255,191,102,0.26); color:#fff0d5; }
      .pill.bad { border-color:rgba(255,124,124,0.28); color:#ffe0e0; }
      .label { font-size:12px; text-transform:uppercase; letter-spacing:.08em; color:#8cb6dd; }
      .value { margin-top:6px; font-size:16px; }
      pre { white-space:pre-wrap; word-break:break-word; background:rgba(1,8,16,0.72); border-radius:14px; padding:14px; overflow:auto; }
      code { font-family:"Cascadia Code","Consolas",monospace; }
      ul { margin:10px 0 0 18px; padding:0; }
      .tabs { display:flex; flex-wrap:wrap; gap:10px; margin-top:18px; }
      .tab-button { appearance:none; border:1px solid rgba(146,198,255,0.18); background:rgba(14,31,50,0.9); color:#d9ebfd; border-radius:999px; padding:10px 16px; cursor:pointer; font-size:14px; }
      .tab-button:hover { background:rgba(21,44,70,0.95); border-color:rgba(146,198,255,0.32); }
      .tab-button.active { background:linear-gradient(180deg,#2d79dd 0%,#215cad 100%); border-color:rgba(162,212,255,0.5); color:#fff; }
      .tab-panel { display:none; margin-top:16px; } .tab-panel.active { display:block; }
      .section-stack { display:grid; gap:16px; }
      .split { display:grid; gap:16px; grid-template-columns:1.2fr 0.8fr; }
      .empty-state { margin:0; padding:18px; border-radius:14px; background:rgba(1,8,16,0.46); border:1px dashed rgba(146,198,255,0.18); }
      .facts { display:grid; gap:10px; }
      .fact { display:flex; justify-content:space-between; gap:16px; padding:12px 14px; border-radius:14px; background:rgba(16,35,56,0.85); }
      .fact-key { color:#8cb6dd; } .fact-value { text-align:right; }
      .ai-btn { appearance:none; border:1px solid rgba(162,212,255,0.4); background:linear-gradient(180deg,#2d79dd 0%,#215cad 100%); color:#fff; border-radius:999px; padding:12px 22px; cursor:pointer; font-size:15px; font-weight:600; margin-bottom:16px; }
      .ai-btn:hover { filter:brightness(1.1); } .ai-btn:disabled { opacity:.55; cursor:default; }
      .ai-result { white-space:pre-wrap; background:rgba(1,8,16,0.72); border-radius:14px; padding:16px; font-family:"Segoe UI",sans-serif; line-height:1.6; }
      @media (max-width:860px) { .split { grid-template-columns:1fr; } }
      @media (max-width:640px) { .page { padding:16px; } .hero,.panel { padding:16px; border-radius:16px; } .stats { grid-template-columns:repeat(2,minmax(0,1fr)); } }
    </style>
  </head>
  <body>
    <div class="page">
      <div class="hero">
        <div class="hero-top">
          <div>
            <h1>MBF Tools Debug Viewer</h1>
            <p class="summary">${escapeHtml_(record.summary || 'No short summary is available.')}</p>
            <p class="muted">Code: <code>${escapeHtml_(record.code || '')}</code> · Command: <code>${escapeHtml_(record.command || '')}</code> · Created: ${escapeHtml_(record.createdAt || '')}</p>
            <div class="status-pills">
              <div class="pill ${setup.connectedDevice ? 'ok' : 'bad'}">ADB ${escapeHtml_(setup.connectedDevice ? 'connected' : 'not connected')}</div>
              <div class="pill ${setup.wirelessDebuggingEnabled ? 'ok' : 'bad'}">Wireless Debugging ${escapeHtml_(setup.wirelessDebuggingEnabled ? 'on' : 'off')}</div>
              <div class="pill ${setup.developerModeEnabled ? 'ok' : 'bad'}">Developer mode ${escapeHtml_(setup.developerModeEnabled ? 'on' : 'off')}</div>
              <div class="pill ${beatSaber.installed ? 'ok' : 'warn'}">Beat Saber ${escapeHtml_(beatSaber.installed ? 'installed' : 'not found')}</div>
            </div>
          </div>
          <div class="hero-meta">
            <div class="panel" style="padding:16px;">
              <h3>At a glance</h3>
              <div class="facts">
                <div class="fact"><span class="fact-key">Guide step</span><span class="fact-value">${escapeHtml_(setup.currentGuideStep || 'Not in setup')}</span></div>
                <div class="fact"><span class="fact-key">Device</span><span class="fact-value">${escapeHtml_(setup.connectedDevice || 'No device')}</span></div>
                <div class="fact"><span class="fact-key">Beat Saber</span><span class="fact-value">${escapeHtml_(beatSaber.versionName || 'Unknown')}</span></div>
              </div>
            </div>
          </div>
        </div>
        <div class="stats">
          <div class="stat"><div class="label">Current step</div><div class="value">${escapeHtml_(setup.currentGuideStep || 'Not in setup')}</div></div>
          <div class="stat"><div class="label">ADB</div><div class="value">${escapeHtml_(setup.connectedDevice ? `Connected to ${setup.connectedDevice}` : 'Not connected')}</div></div>
          <div class="stat"><div class="label">Beat Saber</div><div class="value">${escapeHtml_(beatSaber.installed ? (beatSaber.versionName || 'Installed') : 'Not installed')}</div></div>
          <div class="stat"><div class="label">Mods</div><div class="value">${escapeHtml_(String(Number(mods.count || 0)))}</div></div>
          <div class="stat"><div class="label">Errors</div><div class="value">${escapeHtml_(String(Number(logStats.errorCount || 0)))}</div></div>
          <div class="stat"><div class="label">Warnings</div><div class="value">${escapeHtml_(String(Number(logStats.warnCount || 0)))}</div></div>
          <div class="stat"><div class="label">Beat Saber logs</div><div class="value">${escapeHtml_(String(Number(beatSaberLogs.lineCount || 0)))}</div></div>
        </div>
        <div class="tabs" role="tablist">
          <button class="tab-button active" type="button" role="tab" aria-selected="true" data-tab-target="tab-overview">Overview</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" data-tab-target="tab-setup">Setup</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" data-tab-target="tab-mods">Mods</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" data-tab-target="tab-bslogs">Beat Saber Logs</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" data-tab-target="tab-applogs">App Logs</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" data-tab-target="tab-aifix">AI Fix ✨</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" data-tab-target="tab-json">Raw JSON</button>
        </div>
      </div>

      <div id="tab-overview" class="tab-panel active" role="tabpanel">
        <div class="grid">
          <div class="panel"><h2>Inferred issues</h2>${issueList}</div>
          <div class="panel"><h2>Recent problems</h2>${problemList}</div>
          <div class="panel"><h2>Beat Saber log highlights</h2>${beatSaberProblems}</div>
          <div class="panel">
            <h2>Quick facts</h2>
            <ul>
              <li>Pairing port: ${escapeHtml_(String(setup.pairingPort || 'Unknown'))}</li>
              <li>Debug port: ${escapeHtml_(String(setup.debugPort || 'Unknown'))}</li>
              <li>Package: ${escapeHtml_(beatSaber.packageName || 'Unknown')}</li>
              <li>Version: ${escapeHtml_(beatSaber.versionName || 'Unknown')}</li>
            </ul>
          </div>
        </div>
      </div>

      <div id="tab-setup" class="tab-panel" role="tabpanel">
        <div class="split">
          <div class="panel">
            <h2>Setup status</h2>
            <div class="facts">
              <div class="fact"><span class="fact-key">Developer mode</span><span class="fact-value">${escapeHtml_(setup.developerModeEnabled ? 'On' : 'Off')}</span></div>
              <div class="fact"><span class="fact-key">Wireless Debugging</span><span class="fact-value">${escapeHtml_(setup.wirelessDebuggingEnabled ? 'On' : 'Off')}</span></div>
              <div class="fact"><span class="fact-key">Connected device</span><span class="fact-value">${escapeHtml_(setup.connectedDevice || 'Not connected')}</span></div>
              <div class="fact"><span class="fact-key">Current guide step</span><span class="fact-value">${escapeHtml_(setup.currentGuideStep || 'Not in setup')}</span></div>
              <div class="fact"><span class="fact-key">Pairing port</span><span class="fact-value">${escapeHtml_(String(setup.pairingPort || 'Unknown'))}</span></div>
              <div class="fact"><span class="fact-key">Debug port</span><span class="fact-value">${escapeHtml_(String(setup.debugPort || 'Unknown'))}</span></div>
            </div>
          </div>
          <div class="panel">
            <h2>Beat Saber status</h2>
            <div class="facts">
              <div class="fact"><span class="fact-key">Installed</span><span class="fact-value">${escapeHtml_(beatSaber.installed ? 'Yes' : 'No')}</span></div>
              <div class="fact"><span class="fact-key">Package</span><span class="fact-value">${escapeHtml_(beatSaber.packageName || 'Unknown')}</span></div>
              <div class="fact"><span class="fact-key">Version</span><span class="fact-value">${escapeHtml_(beatSaber.versionName || 'Unknown')}</span></div>
              <div class="fact"><span class="fact-key">Detected mods</span><span class="fact-value">${escapeHtml_(String(Number(mods.count || 0)))}</span></div>
              <div class="fact"><span class="fact-key">Beat Saber log lines</span><span class="fact-value">${escapeHtml_(String(Number(beatSaberLogs.lineCount || 0)))}</span></div>
            </div>
          </div>
        </div>
      </div>

      <div id="tab-mods" class="tab-panel" role="tabpanel">
        <div class="section-stack">
          <div class="panel">
            <h2>Detected mods</h2>
            <p class="muted">${escapeHtml_(String(Number(mods.count || 0)))} mod folder(s) were detected for the current Beat Saber version.</p>
            ${modList}
          </div>
        </div>
      </div>

      <div id="tab-bslogs" class="tab-panel" role="tabpanel">
        <div class="section-stack">
          <div class="panel"><h2>Beat Saber log highlights</h2>${beatSaberProblems}</div>
          <div class="panel">
            <h2>Beat Saber logs</h2>
            ${beatSaberLogLines.length > 0 ? `<pre><code>${escapeHtml_(beatSaberLogLines.join('\n'))}</code></pre>` : '<p class="empty-state muted">No Beat Saber log lines were included in this upload.</p>'}
          </div>
        </div>
      </div>

      <div id="tab-applogs" class="tab-panel" role="tabpanel">
        <div class="section-stack">
          <div class="panel"><h2>Recent app problems</h2>${problemList}</div>
          <div class="panel">
            <h2>App log output</h2>
            ${logsText.trim() ? `<pre><code>${escapeHtml_(logsText)}</code></pre>` : '<p class="empty-state muted">No app log output was included in this upload.</p>'}
          </div>
        </div>
      </div>

      <div id="tab-aifix" class="tab-panel" role="tabpanel">
        <div class="panel">
          <h2>AI Fix Suggestions ✨</h2>
          <p class="muted">Click the button below to get AI-powered step-by-step fix instructions based on the detected issues.</p>
          <button class="ai-btn" id="ai-fix-btn" type="button">Generate AI Fix</button>
          <div id="ai-fix-output" style="display:none;">
            <div class="ai-result" id="ai-fix-text"></div>
          </div>
        </div>
      </div>

      <div id="tab-json" class="tab-panel" role="tabpanel">
        <div class="panel">
          <h2>Raw JSON</h2>
          <pre><code>${escapeHtml_(rawJson)}</code></pre>
        </div>
      </div>
    </div>
    <script>
      (function () {
        const buttons = Array.from(document.querySelectorAll('.tab-button'));
        const panels = Array.from(document.querySelectorAll('.tab-panel'));
        function setActiveTab(t) {
          buttons.forEach(b => { const a = b.getAttribute('data-tab-target') === t; b.classList.toggle('active', a); b.setAttribute('aria-selected', a ? 'true' : 'false'); });
          panels.forEach(p => p.classList.toggle('active', p.id === t));
        }
        buttons.forEach(b => b.addEventListener('click', () => setActiveTab(b.getAttribute('data-tab-target'))));

        const aiBtn = document.getElementById('ai-fix-btn');
        const aiOutput = document.getElementById('ai-fix-output');
        const aiText = document.getElementById('ai-fix-text');
        aiBtn.addEventListener('click', async function () {
          aiBtn.disabled = true;
          aiBtn.textContent = 'Generating...';
          try {
            const res = await fetch(${JSON.stringify(aifixUrl)});
            const data = await res.json();
            aiText.textContent = data.ok ? data.fix : ('Error: ' + (data.error || 'Unknown error'));
            aiOutput.style.display = 'block';
            aiBtn.textContent = data.ok ? 'Regenerate AI Fix' : 'Retry';
          } catch (e) {
            aiText.textContent = 'Failed to fetch AI fix: ' + e.message;
            aiOutput.style.display = 'block';
            aiBtn.textContent = 'Retry';
          }
          aiBtn.disabled = false;
        });
      })();
    </script>
  </body>
</html>`;
}

function renderMissingCodePage_() {
  return renderErrorPage_('Open this viewer with a shared debug code.');
}

function renderErrorPage_(message) {
  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width,initial-scale=1"/>
    <title>MBF Tools Debug Viewer</title>
    <style>
      body{margin:0;font-family:"Segoe UI",sans-serif;background:#0f1722;color:#f4f8fc;display:flex;align-items:center;justify-content:center;min-height:100vh;}
      .card{max-width:560px;background:#162232;border:1px solid rgba(181,216,255,0.18);border-radius:18px;padding:24px;}
    </style>
  </head>
  <body><div class="card"><h1>MBF Tools Debug Viewer</h1><p>${message}</p></div></body>
</html>`;
}
