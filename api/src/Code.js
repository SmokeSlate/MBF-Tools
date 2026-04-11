const FOLDER_ID_PROP = 'logs_folder_id';
const LOG_FOLDER_NAME = 'MBF Tools Shared Logs';
const COMMAND_PREFIX = '!s';

function doPost(e) {
  try {
    const payload = parseJsonBody_(e);
    const code = generateCode_();
    const analysis = analyzePayload_(payload);
    const baseUrl = getBaseUrl_();
    const record = {
      code: code,
      createdAt: new Date().toISOString(),
      command: `${COMMAND_PREFIX} ${code}`,
      summary: analysis.summary,
      issues: analysis.issues,
      payload: payload
    };

    saveRecord_(code, record);

    return jsonResponse_({
      ok: true,
      code: code,
      command: record.command,
      summary: record.summary,
      viewerUrl: buildActionUrl_(baseUrl, 'view', code),
      summaryUrl: buildActionUrl_(baseUrl, 'summary', code),
      messageUrl: buildActionUrl_(baseUrl, 'message', code),
      dataUrl: buildActionUrl_(baseUrl, 'data', code)
    });
  } catch (error) {
    return jsonResponse_({
      ok: false,
      error: error && error.message ? error.message : String(error)
    });
  }
}

function doGet(e) {
  const action = normalizeAction_(e && e.parameter && e.parameter.action);
  const code = String((e && e.parameter && e.parameter.code) || '').trim().toLowerCase();

  if (!code) {
    return action === 'view'
      ? htmlResponse_(renderMissingCodePage_())
      : jsonResponse_({ ok: false, error: 'Missing code.' });
  }

  const record = loadRecord_(code);
  if (!record) {
    return action === 'view'
      ? htmlResponse_(renderErrorPage_(`No shared log was found for code ${escapeHtml_(code)}.`))
      : jsonResponse_({ ok: false, error: `No shared log was found for code ${code}.` });
  }

  switch (action) {
    case 'summary':
      return renderSummaryResponse_(record, e);
    case 'message':
      return textResponse_(buildMessageText_(record));
    case 'data':
      return jsonResponse_({
        ok: true,
        code: record.code,
        createdAt: record.createdAt,
        summary: record.summary,
        issues: record.issues,
        payload: record.payload
      });
    case 'view':
    default:
      return htmlResponse_(renderViewerPage_(record));
  }
}

function renderSummaryResponse_(record, e) {
  const format = String((e && e.parameter && e.parameter.format) || '').trim().toLowerCase();
  if (format === 'text') {
    return textResponse_(record.summary || 'No short summary is available.');
  }

  return jsonResponse_({
    ok: true,
    code: record.code,
    summary: record.summary,
    issues: record.issues,
    currentGuideStep: getPath_(record, ['payload', 'setup', 'currentGuideStep'], ''),
    createdAt: record.createdAt
  });
}

function parseJsonBody_(e) {
  const raw = e && e.postData && e.postData.contents ? e.postData.contents : '';
  if (!raw) {
    throw new Error('Request body was empty.');
  }

  let payload;
  try {
    payload = JSON.parse(raw);
  } catch (error) {
    throw new Error('Request body was not valid JSON.');
  }

  if (!payload || typeof payload !== 'object') {
    throw new Error('Payload must be a JSON object.');
  }

  return payload;
}

function analyzePayload_(payload) {
  const issues = [];
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const app = payload.app || {};

  if (!setup.developerModeEnabled) {
    issues.push('Android developer mode is not enabled.');
  }
  if (!setup.wirelessDebuggingEnabled) {
    issues.push('Wireless Debugging is turned off.');
  }
  if (!setup.connectedDevice) {
    issues.push('No authorized ADB headset connection is active.');
  }
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
  const interestingBeatSaberLogs = Array.isArray(beatSaberLogs.interesting) ? beatSaberLogs.interesting : [];
  if (interestingBeatSaberLogs.length > 0) {
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

  return { summary: summary, issues: issues };
}

function buildMessageText_(record) {
  const payload = record.payload || {};
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const baseUrl = getBaseUrl_();
  const viewerUrl = buildActionUrl_(baseUrl, 'view', record.code);
  const summaryUrl = buildActionUrl_(baseUrl, 'summary', record.code);

  const modItems = Array.isArray(mods.items) ? mods.items.slice(0, 15) : [];
  const modSuffix =
    Array.isArray(mods.items) && mods.items.length > 15
      ? ` (+${mods.items.length - 15} more)`
      : '';
  const issueItems = Array.isArray(record.issues) ? record.issues.slice(0, 6) : [];
  const issueBlock =
    issueItems.length > 0
      ? issueItems.map((issue) => `- ${issue}`).join('\n')
      : '- No explicit issues were inferred.';
  const modsLine =
    modItems.length > 0
      ? modItems.join(', ') + modSuffix
      : 'none detected';

  const lines = [
    `# ${COMMAND_PREFIX} ${record.code}`,
    ``,
    `**Summary**`,
    `${record.summary || 'No short summary is available.'}`,
    ``,
    `**Issues**`,
    `${issueBlock}`,
    ``,
    `**Status**`,
    `- Created: ${record.createdAt || ''}`,
    `- Current step: ${setup.currentGuideStep || 'Not in setup'}`,
    `- ADB: ${setup.connectedDevice ? `Connected to ${setup.connectedDevice}` : 'Not connected'}`,
    `- Developer mode: ${setup.developerModeEnabled ? 'On' : 'Off'}`,
    `- Wireless Debugging: ${setup.wirelessDebuggingEnabled ? 'On' : 'Off'}`,
    `- Beat Saber: ${
      beatSaber.installed
        ? `${beatSaber.packageName || 'installed'} ${beatSaber.versionName || ''}`.trim()
        : `${beatSaber.packageName || 'com.beatgames.beatsaber'} not installed`
    }`,
    `- Mods (${Number(mods.count || 0)}): ${modsLine}`,
    `- Beat Saber logs: ${Number(beatSaberLogs.lineCount || 0)} line(s), ${Number(beatSaberLogs.interestingCount || 0)} interesting`,
    `- App logs: ${Number(logStats.errorCount || 0)} error(s), ${Number(logStats.warnCount || 0)} warning(s), ${Number(logStats.lineCount || 0)} line(s)`,
    ``,
    `**Links**`,
    `- Viewer: ${viewerUrl}`,
    `- Summary API: ${summaryUrl}`
  ];

  return lines.join('\n');
}

function saveRecord_(code, record) {
  const folder = getOrCreateLogsFolder_();
  folder.createFile(`${code}.json`, JSON.stringify(record, null, 2), MimeType.PLAIN_TEXT);
}

function loadRecord_(code) {
  const files = getOrCreateLogsFolder_().getFilesByName(`${code}.json`);
  if (!files.hasNext()) {
    return null;
  }

  const file = files.next();
  return JSON.parse(file.getBlob().getDataAsString());
}

function getOrCreateLogsFolder_() {
  const props = PropertiesService.getScriptProperties();
  const existingId = props.getProperty(FOLDER_ID_PROP);
  if (existingId) {
    try {
      return DriveApp.getFolderById(existingId);
    } catch (error) {
      props.deleteProperty(FOLDER_ID_PROP);
    }
  }

  const folders = DriveApp.getFoldersByName(LOG_FOLDER_NAME);
  const folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(LOG_FOLDER_NAME);
  props.setProperty(FOLDER_ID_PROP, folder.getId());
  return folder;
}

function buildActionUrl_(baseUrl, action, code) {
  return `${baseUrl}?action=${encodeURIComponent(action)}&code=${encodeURIComponent(code)}`;
}

function normalizeAction_(action) {
  const normalized = String(action || '').trim().toLowerCase();
  return normalized || 'view';
}

// Updated to use the specified GAS URL rather than dynamically resolving
// with ScriptApp.getService().getUrl(). This ensures that all generated URLs
// (view, summary, message, data) point to the correct deployed endpoint.
function getBaseUrl_() {
  return 'https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec';
}

function generateCode_() {
  const folder = getOrCreateLogsFolder_();
  for (let i = 0; i < 10; i += 1) {
    const code = Math.random().toString(36).slice(2, 7).toLowerCase();
    if (!folder.getFilesByName(`${code}.json`).hasNext()) {
      return code;
    }
  }
  return Utilities.getUuid().replace(/-/g, '').slice(0, 8).toLowerCase();
}

function jsonResponse_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(
    ContentService.MimeType.JSON
  );
}

function textResponse_(text) {
  return ContentService.createTextOutput(String(text || '')).setMimeType(
    ContentService.MimeType.TEXT
  );
}

function htmlResponse_(html) {
  return HtmlService.createHtmlOutput(html)
    .setTitle('MBF Tools Debug Viewer')
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function renderViewerPage_(record) {
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

  const issueList =
    Array.isArray(record.issues) && record.issues.length > 0
      ? `<ul>${record.issues.map((issue) => `<li>${escapeHtml_(issue)}</li>`).join('')}</ul>`
      : '<p class="muted">No explicit issues were inferred.</p>';

  const problemList =
    recentProblems.length > 0
      ? `<ul>${recentProblems.map((line) => `<li>${escapeHtml_(line)}</li>`).join('')}</ul>`
      : '<p class="muted">No recent warning or error lines were captured.</p>';

  const modList =
    modItems.length > 0
      ? `<ul>${modItems.map((item) => `<li>${escapeHtml_(item)}</li>`).join('')}</ul>`
      : '<p class="muted">No installed mods were detected.</p>';

  const beatSaberProblems =
    beatSaberInteresting.length > 0
      ? `<ul>${beatSaberInteresting.map((line) => `<li>${escapeHtml_(line)}</li>`).join('')}</ul>`
      : '<p class="muted">No Beat Saber error-like lines were detected.</p>';

  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>MBF Tools Debug Viewer</title>
    <style>
      :root {
        color-scheme: dark;
      }
      body {
        margin: 0;
        font-family: "Segoe UI", sans-serif;
        background:
          radial-gradient(circle at top, rgba(54, 139, 255, 0.22), transparent 34%),
          linear-gradient(180deg, #081626 0%, #0f2134 100%);
        color: #eaf4ff;
      }
      .page {
        max-width: 1200px;
        margin: 0 auto;
        padding: 24px;
      }
      .hero, .panel {
        background: rgba(6, 17, 30, 0.82);
        border: 1px solid rgba(146, 198, 255, 0.22);
        border-radius: 20px;
        padding: 20px;
        box-shadow: 0 16px 48px rgba(0, 0, 0, 0.22);
      }
      .hero {
        margin-bottom: 16px;
      }
      .grid {
        display: grid;
        gap: 16px;
        grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      }
      h1, h2 {
        margin: 0 0 10px 0;
      }
      h2 {
        font-size: 20px;
      }
      h3 {
        margin: 0 0 8px 0;
        font-size: 14px;
        color: #d6e7f8;
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }
      p, li {
        line-height: 1.5;
      }
      .muted {
        color: #a7bfd8;
      }
      .summary {
        font-size: 18px;
        color: #ffffff;
      }
      .stats {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: 12px;
        margin-top: 14px;
      }
      .stat {
        background: rgba(23, 43, 67, 0.9);
        border-radius: 14px;
        padding: 12px;
      }
      .hero-top {
        display: flex;
        gap: 16px;
        align-items: flex-start;
        justify-content: space-between;
        flex-wrap: wrap;
      }
      .hero-meta {
        min-width: 280px;
        flex: 0 1 320px;
      }
      .status-pills {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-top: 12px;
      }
      .pill {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        border-radius: 999px;
        padding: 8px 12px;
        font-size: 13px;
        background: rgba(18, 42, 68, 0.95);
        border: 1px solid rgba(146, 198, 255, 0.18);
      }
      .pill.ok {
        border-color: rgba(112, 224, 176, 0.28);
        color: #dffbea;
      }
      .pill.warn {
        border-color: rgba(255, 191, 102, 0.26);
        color: #fff0d5;
      }
      .pill.bad {
        border-color: rgba(255, 124, 124, 0.28);
        color: #ffe0e0;
      }
      .label {
        font-size: 12px;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: #8cb6dd;
      }
      .value {
        margin-top: 6px;
        font-size: 16px;
      }
      pre {
        white-space: pre-wrap;
        word-break: break-word;
        background: rgba(1, 8, 16, 0.72);
        border-radius: 14px;
        padding: 14px;
        overflow: auto;
      }
      code {
        font-family: "Cascadia Code", "Consolas", monospace;
      }
      ul {
        margin: 10px 0 0 18px;
        padding: 0;
      }
      .tabs {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
        margin-top: 18px;
      }
      .tab-button {
        appearance: none;
        border: 1px solid rgba(146, 198, 255, 0.18);
        background: rgba(14, 31, 50, 0.9);
        color: #d9ebfd;
        border-radius: 999px;
        padding: 10px 16px;
        cursor: pointer;
        font-size: 14px;
      }
      .tab-button:hover {
        background: rgba(21, 44, 70, 0.95);
        border-color: rgba(146, 198, 255, 0.32);
      }
      .tab-button.active {
        background: linear-gradient(180deg, #2d79dd 0%, #215cad 100%);
        border-color: rgba(162, 212, 255, 0.5);
        color: #ffffff;
      }
      .tab-panel {
        display: none;
        margin-top: 16px;
      }
      .tab-panel.active {
        display: block;
      }
      .section-stack {
        display: grid;
        gap: 16px;
      }
      .split {
        display: grid;
        gap: 16px;
        grid-template-columns: 1.2fr 0.8fr;
      }
      .empty-state {
        margin: 0;
        padding: 18px;
        border-radius: 14px;
        background: rgba(1, 8, 16, 0.46);
        border: 1px dashed rgba(146, 198, 255, 0.18);
      }
      .facts {
        display: grid;
        gap: 10px;
      }
      .fact {
        display: flex;
        justify-content: space-between;
        gap: 16px;
        padding: 12px 14px;
        border-radius: 14px;
        background: rgba(16, 35, 56, 0.85);
      }
      .fact-key {
        color: #8cb6dd;
      }
      .fact-value {
        text-align: right;
      }
      @media (max-width: 860px) {
        .split {
          grid-template-columns: 1fr;
        }
      }
      @media (max-width: 640px) {
        .page {
          padding: 16px;
        }
        .hero, .panel {
          padding: 16px;
          border-radius: 16px;
        }
        .stats {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }
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
          <div class="stat"><div class="label">Beat Saber</div><div class="value">${escapeHtml_(beatSaber.installed ? `${beatSaber.versionName || 'Installed'}` : 'Not installed')}</div></div>
          <div class="stat"><div class="label">Mods</div><div class="value">${escapeHtml_(String(Number(mods.count || 0)))}</div></div>
          <div class="stat"><div class="label">Errors</div><div class="value">${escapeHtml_(String(Number(logStats.errorCount || 0)))}</div></div>
          <div class="stat"><div class="label">Warnings</div><div class="value">${escapeHtml_(String(Number(logStats.warnCount || 0)))}</div></div>
          <div class="stat"><div class="label">Beat Saber logs</div><div class="value">${escapeHtml_(String(Number(beatSaberLogs.lineCount || 0)))}</div></div>
        </div>
        <div class="tabs" role="tablist" aria-label="Debug viewer sections">
          <button class="tab-button active" type="button" role="tab" aria-selected="true" aria-controls="tab-overview" data-tab-target="tab-overview">Overview</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" aria-controls="tab-setup" data-tab-target="tab-setup">Setup</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" aria-controls="tab-mods" data-tab-target="tab-mods">Mods</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" aria-controls="tab-bslogs" data-tab-target="tab-bslogs">Beat Saber Logs</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" aria-controls="tab-applogs" data-tab-target="tab-applogs">App Logs</button>
          <button class="tab-button" type="button" role="tab" aria-selected="false" aria-controls="tab-json" data-tab-target="tab-json">Raw JSON</button>
        </div>
      </div>

      <div id="tab-overview" class="tab-panel active" role="tabpanel" aria-hidden="false">
        <div class="grid">
          <div class="panel">
            <h2>Inferred issues</h2>
            ${issueList}
          </div>
          <div class="panel">
            <h2>Recent problems</h2>
            ${problemList}
          </div>
          <div class="panel">
            <h2>Beat Saber log highlights</h2>
            ${beatSaberProblems}
          </div>
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

      <div id="tab-setup" class="tab-panel" role="tabpanel" aria-hidden="true">
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

      <div id="tab-mods" class="tab-panel" role="tabpanel" aria-hidden="true">
        <div class="section-stack">
          <div class="panel">
            <h2>Detected mods</h2>
            <p class="muted">${escapeHtml_(String(Number(mods.count || 0)))} mod folder(s) were detected for the current Beat Saber version.</p>
            ${modList}
          </div>
        </div>
      </div>

      <div id="tab-bslogs" class="tab-panel" role="tabpanel" aria-hidden="true">
        <div class="section-stack">
          <div class="panel">
            <h2>Beat Saber log highlights</h2>
            ${beatSaberProblems}
          </div>
          <div class="panel">
            <h2>Beat Saber logs</h2>
            ${
              beatSaberLogLines.length > 0
                ? `<pre><code>${escapeHtml_(beatSaberLogLines.join('\n'))}</code></pre>`
                : '<p class="empty-state muted">No Beat Saber log lines were included in this upload.</p>'
            }
          </div>
        </div>
      </div>

      <div id="tab-applogs" class="tab-panel" role="tabpanel" aria-hidden="true">
        <div class="section-stack">
          <div class="panel">
            <h2>Recent app problems</h2>
            ${problemList}
          </div>
          <div class="panel">
            <h2>App log output</h2>
            ${
              logsText.trim()
                ? `<pre><code>${escapeHtml_(logsText)}</code></pre>`
                : '<p class="empty-state muted">No app log output was included in this upload.</p>'
            }
          </div>
        </div>
      </div>

      <div id="tab-json" class="tab-panel" role="tabpanel" aria-hidden="true">
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

        function setActiveTab(targetId) {
          buttons.forEach((button) => {
            const isActive = button.getAttribute('data-tab-target') === targetId;
            button.classList.toggle('active', isActive);
            button.setAttribute('aria-selected', isActive ? 'true' : 'false');
          });
          panels.forEach((panel) => {
            const isActive = panel.id === targetId;
            panel.classList.toggle('active', isActive);
            panel.setAttribute('aria-hidden', isActive ? 'false' : 'true');
          });
        }

        buttons.forEach((button) => {
          button.addEventListener('click', function () {
            setActiveTab(button.getAttribute('data-tab-target'));
          });
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
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>MBF Tools Debug Viewer</title>
    <style>
      body {
        margin: 0;
        font-family: "Segoe UI", sans-serif;
        background: #0f1722;
        color: #f4f8fc;
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
      }
      .card {
        max-width: 560px;
        background: #162232;
        border: 1px solid rgba(181, 216, 255, 0.18);
        border-radius: 18px;
        padding: 24px;
      }
    </style>
  </head>
  <body>
    <div class="card">
      <h1>MBF Tools Debug Viewer</h1>
      <p>${message}</p>
    </div>
  </body>
</html>`;
}

function escapeHtml_(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function getPath_(value, path, fallback) {
  let current = value;
  for (let i = 0; i < path.length; i += 1) {
    if (!current || typeof current !== 'object' || !(path[i] in current)) {
      return fallback;
    }
    current = current[path[i]];
  }
  return current == null ? fallback : current;
}
