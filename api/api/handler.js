const COMMAND_PREFIX = '!s';
const CODE_TTL = 60 * 60 * 24 * 30; // 30 days
const ADMIN_HASH = 'e447973503108005e9143ebec44e5f4e2e025c04253ce80c4b113537fccff97d';
const ADMIN_COOKIE = 'mbf_admin';
const PUBLIC_BASE_URL = 'https://logs.mbf.tools';
const LEGACY_HOST = 'logs.sm0ke.org';
const OPENROUTER_API = 'https://openrouter.ai/api/v1/chat/completions';
const DIAG_MODEL = 'openrouter/free';
const DIAG_MAX_TOKENS = 1200;
const DIAG_MAX_ITERATIONS = 4;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Permanent redirect: logs.sm0ke.org → logs.mbf.tools
    if (url.hostname === LEGACY_HOST) {
      url.hostname = 'logs.mbf.tools';
      return new Response(null, {
        status: 301,
        headers: {
          Location: url.toString(),
          'Cache-Control': 'max-age=86400',
        },
      });
    }

    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (path === '/admin' || path === '/admin/login' || path === '/admin/delete' || path === '/admin/bulk-delete' || path === '/admin/logout') {
      return handleAdmin(request, env, url, path);
    }

    if (request.method === 'POST') {
      // POST /followup/<code>  — follow-up chat on an existing diagnosis
      const postSegments = path.split('/').filter(Boolean);
      if (postSegments[0] === 'followup' && postSegments[1]) {
        return handleFollowup(request, env, normalizeCode_(postSegments[1]), corsHeaders);
      }
      return handleUpload(request, env, url, corsHeaders);
    }

    if (request.method === 'GET') {
      return handleGet(request, env, url, corsHeaders);
    }

    return jsonResponse({ ok: false, error: 'Method not allowed.' }, 405, corsHeaders);
  },
};

// ─── Admin ────────────────────────────────────────────────────────────────────

async function handleAdmin(request, env, url, path) {
  if (path === '/admin/login') {
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });
    const form = await request.formData();
    const password = form.get('password') || '';
    const remember = form.get('remember') === '1';
    const hash = await sha256hex(password);
    if (hash !== ADMIN_HASH) {
      return htmlResponse(renderAdminLogin_('Incorrect password.'));
    }
    const maxAge = remember ? 60 * 60 * 24 * 30 : 60 * 60 * 8; // 30 days or 8 hours
    return new Response(null, {
      status: 302,
      headers: {
        Location: '/admin',
        'Set-Cookie': `${ADMIN_COOKIE}=${ADMIN_HASH}; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}; Path=/`,
      },
    });
  }

  if (path === '/admin/logout') {
    return new Response(null, {
      status: 302,
      headers: {
        Location: '/admin',
        'Set-Cookie': `${ADMIN_COOKIE}=; HttpOnly; Secure; SameSite=Strict; Max-Age=0; Path=/`,
      },
    });
  }

  if (!isAdminAuthed_(request)) {
    return htmlResponse(renderAdminLogin_());
  }

  if (path === '/admin/delete') {
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });
    const form = await request.formData();
    const code = (form.get('code') || '').trim().toLowerCase();
    if (code) await env.LOGS.delete(`log:${code}`);
    return new Response(null, { status: 302, headers: { Location: '/admin' } });
  }

  if (path === '/admin/bulk-delete') {
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });
    const form = await request.formData();
    const codes = form.getAll('code').filter(c => c.trim());
    await Promise.all(codes.map(c => env.LOGS.delete(`log:${c.trim().toLowerCase()}`)));
    return new Response(null, { status: 302, headers: { Location: '/admin' } });
  }

  // Browse logs — load all for client-side filtering / sorting / pagination
  let allKeys = [];
  let cursor;
  do {
    const listed = await env.LOGS.list({ prefix: 'log:', limit: 1000, cursor });
    allKeys = allKeys.concat(listed.keys);
    cursor = listed.list_complete ? null : listed.cursor;
  } while (cursor);

  const rows = allKeys.map(k => ({
    code: k.name.replace('log:', ''),
    createdAt: k.metadata?.createdAt || '',
    summary: k.metadata?.summary || '',
  }));

  return htmlResponse(renderAdminPage_(rows));
}

function isAdminAuthed_(request) {
  const cookie = request.headers.get('Cookie') || '';
  const match = cookie.match(new RegExp(`(?:^|;\\s*)${ADMIN_COOKIE}=([^;]+)`));
  return match?.[1] === ADMIN_HASH;
}

async function sha256hex(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
}

// ─── Upload ───────────────────────────────────────────────────────────────────

async function handleUpload(request, env, url, corsHeaders) {
  try {
    let payload;
    try { payload = await request.json(); } catch {
      return jsonResponse({ ok: false, error: 'Request body was not valid JSON.' }, 400, corsHeaders);
    }
    if (!payload || typeof payload !== 'object') {
      return jsonResponse({ ok: false, error: 'Payload must be a JSON object.' }, 400, corsHeaders);
    }

    const code = await generateCode_(env);
    const analysis = analyzePayload_(payload);
    const record = {
      code,
      createdAt: new Date().toISOString(),
      command: `${COMMAND_PREFIX} ${code}`,
      summary: analysis.summary,
      issues: analysis.issues,
      payload,
    };

    await env.LOGS.put(`log:${code}`, JSON.stringify(record), {
      expirationTtl: CODE_TTL,
      metadata: { createdAt: record.createdAt, summary: record.summary },
    });

    return jsonResponse({
      ok: true, code, command: record.command, summary: record.summary,
      viewerUrl: buildActionUrl_(PUBLIC_BASE_URL, 'view', code),
      summaryUrl: buildActionUrl_(PUBLIC_BASE_URL, 'summary', code),
      messageUrl: buildActionUrl_(PUBLIC_BASE_URL, 'message', code),
      dataUrl: buildActionUrl_(PUBLIC_BASE_URL, 'data', code),
    }, 200, corsHeaders);
  } catch (error) {
    return jsonResponse({ ok: false, error: error?.message || String(error) }, 500, corsHeaders);
  }
}

// ─── Get ──────────────────────────────────────────────────────────────────────

async function handleGet(request, env, url, corsHeaders) {
  const route = parseLogRoute_(url);
  const action = route.action;
  const code = route.code;

  if (!code) {
    if (action === 'view') return htmlResponse(renderMissingCodePage_(), corsHeaders);
    return jsonResponse({ ok: false, error: 'Missing code.' }, 400, corsHeaders);
  }

  if (action === 'aifix') return handleAiFix(env, url, code, corsHeaders);
  if (action === 'diagnose') return handleDiagnose(env, code, corsHeaders);

  const raw = await env.LOGS.get(`log:${code}`);
  if (!raw) {
    if (action === 'view') return htmlResponse(renderErrorPage_(`No shared log was found for code ${escapeHtml_(code)}.`), corsHeaders);
    return jsonResponse({ ok: false, error: `No shared log was found for code ${code}.` }, 404, corsHeaders);
  }

  const record = JSON.parse(raw);
  switch (action) {
    case 'summary': {
      const format = (url.searchParams.get('format') || '').trim().toLowerCase();
      if (format === 'text') return textResponse(record.summary || 'No short summary is available.', corsHeaders);
      return jsonResponse({ ok: true, code: record.code, summary: record.summary, issues: record.issues, currentGuideStep: record.payload?.setup?.currentGuideStep || '', createdAt: record.createdAt }, 200, corsHeaders);
    }
    case 'message':
      return textResponse(buildMessageText_(record, PUBLIC_BASE_URL), corsHeaders);
    case 'data':
      return jsonResponse({ ok: true, code: record.code, createdAt: record.createdAt, summary: record.summary, issues: record.issues, payload: record.payload }, 200, corsHeaders);
    case 'view':
    default:
      return htmlResponse(renderViewerPage_(record, PUBLIC_BASE_URL), corsHeaders);
  }
}

async function handleAiFix(env, url, code, corsHeaders) {
  const raw = await env.LOGS.get(`log:${code}`);
  if (!raw) return jsonResponse({ ok: false, error: `No shared log was found for code ${code}.` }, 404, corsHeaders);
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
    const aiRes = await fetch(`https://text.pollinations.ai/${encodeURIComponent(prompt)}`, { signal: AbortSignal.timeout(20000) });
    if (!aiRes.ok) throw new Error(`Pollinations returned ${aiRes.status}`);
    const fix = await aiRes.text();
    return jsonResponse({ ok: true, code, fix: fix.trim() }, 200, corsHeaders);
  } catch (error) {
    return jsonResponse({ ok: false, error: error?.message || 'AI fix failed.' }, 500, corsHeaders);
  }
}

async function handleDiagnose(env, code, corsHeaders) {
  const raw = await env.LOGS.get(`log:${code}`);
  if (!raw) return jsonResponse({ ok: false, error: `No shared log was found for code ${code}.` }, 404, corsHeaders);
  if (!env.OPENROUTER_API_KEY) return jsonResponse({ ok: false, error: 'AI diagnosis is not configured on this server.' }, 503, corsHeaders);
  try {
    const record = JSON.parse(raw);
    const diagnosis = await runDiagnosisAgent(env, record);
    return jsonResponse({ ok: true, code, diagnosis }, 200, corsHeaders);
  } catch (error) {
    return jsonResponse({ ok: false, error: error?.message || 'Diagnosis failed.' }, 500, corsHeaders);
  }
}

async function runDiagnosisAgent(env, record) {
  const payload = record.payload || {};
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const issues = Array.isArray(record.issues) ? record.issues : [];

  // Pull real data out of the record to embed directly in the prompt.
  // Keep it focused — only the signal-rich parts, not raw bulk logs.
  const modItems = Array.isArray(mods.items) ? mods.items : [];
  const interesting = Array.isArray(beatSaberLogs.interesting) ? beatSaberLogs.interesting.slice(0, 20) : [];
  const recentProblems = Array.isArray(logStats.recentProblems) ? logStats.recentProblems.slice(0, 10) : [];
  // Only fall back to raw BS lines if there are no interesting ones AND the count is small
  const bsLines = interesting.length === 0 && (beatSaberLogs.lineCount || 0) <= 30
    ? (Array.isArray(beatSaberLogs.lines) ? beatSaberLogs.lines : [])
    : [];

  const systemPrompt = [
    'You are an expert support assistant for Beat Saber modding on Meta Quest headsets.',
    '',
    'KEY FACTS:',
    '- Modding needs Developer Mode (tap Build Number 7×) + Wireless Debugging ON in Developer Options. If it asks for a code, tell them to tempararaly remove it and add it back later.',
    '- MBF Tools (org.sm0ke.mbftools) connects via wireless ADB; Wireless Debugging must stay ON after setup.',
    '- MBF Launcher downgrades Beat Saber to a moddable version (1.37 or 1.40.8) then installs mods.',
    '- Beat Saber auto-updates wipe all mods — just re-mod after an update.',
    '- First launch after modding often crashes 1-3× before stabilising — this is normal, if it continues, investagate further.',
    '- Pairing codes expire in ~60s — request a fresh one if pairing fails.',
    '- "Awaiting patch generation" = patches not ready yet for a new BS version; wait up to 48 hours.',
    '- QBeatSaberPlus/QuestSounds ERROR log lines are usually harmless ChatPlexSDK init noise.',
    '- hasBridgePermissions:false = ADB permission not yet granted; reconnect to prompt it.',
    '- "Custom Sabers" in MBF on 1.40.8 is Qosmetics — it is stable and safe, not a crash cause.',
    '- Qosmetics on 1.37 causes crashes — recommend upgrading to 1.40.8.',
    '- Any user still on 1.37 should be recommended to upgrade to 1.40.8 (more stable, better mods, actively updated).',
    '- Vivify is Quest-unavailable (PC only); maps requiring it will not load on Quest.',
    '- Multiple accounts: Developer Mode can only be enabled from the primary admin account. If the user has multiple accounts, instruct them to use the main admin account.',
    '- ADB: The device is running an adb INTO itself, there is NO PC INVOLVED.',
    '',
    'Analyse the diagnostic data below. Give numbered fix steps based ONLY on what the data shows.',
    'Do not invent mods, commands, or issues absent from the data.',
  ].join('\n');

  // Build the full context message
  const parts = [
    '## MBF Tools Diagnostic Report',
    `Uploaded: ${record.createdAt || 'unknown'}`,
    '',
    '### Device',
    `- Model: ${payload.device?.manufacturer || ''} ${payload.device?.model || ''} (Android ${payload.device?.release || ''})`,
    `- MBF Tools version: ${payload.app?.versionName || 'unknown'}`,
    '',
    '### Setup Status',
    `- Developer mode: ${setup.developerModeEnabled ? 'ON' : 'OFF'}`,
    `- Wireless Debugging: ${setup.wirelessDebuggingEnabled ? 'ON' : 'OFF'}`,
    `- ADB connected device: ${setup.connectedDevice || 'none'}`,
    `- Current guide step: ${setup.currentGuideStep || 'none (setup complete)'}`,
    `- Pairing port: ${setup.pairingPort || 'unknown'}`,
    `- Debug port: ${setup.debugPort || 'unknown'}`,
    '',
    '### Beat Saber',
    `- Package: ${beatSaber.packageName || 'unknown'}`,
    `- Installed: ${beatSaber.installed ? 'yes' : 'no'}`,
    `- Version: ${beatSaber.versionName || 'unknown'} (code ${beatSaber.versionCode || '?'})`,
    `- Detection: ${beatSaber.detectionSource || 'unknown'}`,
    '',
    '### Installed Mods',
    modItems.length > 0
      ? `${modItems.length} mods: ${modItems.join(', ')}`
      : 'No mods detected.',
  ];

  if (issues.length > 0) {
    parts.push('', '### Auto-detected Issues');
    issues.forEach(i => parts.push(`- ${i}`));
  }

  if (interesting.length > 0) {
    parts.push('', '### Beat Saber Crash / Error Lines');
    parts.push('```');
    interesting.forEach(l => parts.push(l));
    parts.push('```');
  } else if (bsLines.length > 0) {
    parts.push('', `### Beat Saber Log Lines (last ${bsLines.length})`);
    parts.push('```');
    bsLines.forEach(l => parts.push(l));
    parts.push('```');
  } else {
    parts.push('', '### Beat Saber Logs', 'No Beat Saber log lines were captured.');
  }

  if (recentProblems.length > 0) {
    parts.push('', '### Recent MBF App Problems');
    parts.push('```');
    recentProblems.forEach(l => parts.push(l));
    parts.push('```');
  }

  parts.push('', `### Log Summary`, record.summary || 'No summary.');
  parts.push('', '---');
  parts.push(
    'Based ONLY on the data above, write a complete diagnosis. Always include:',
    '1. A 1-2 sentence summary of what the data shows.',
    '2. Numbered fix steps for every issue found (or confirmation that the setup looks healthy).',
    '3. Any mods or log lines that look suspicious and what to do about them.',
    'Do not invent issues or commands not supported by the data. Minimum 3 numbered steps.'
  );

  const userContent = parts.join('\n');

  const res = await fetch(OPENROUTER_API, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${env.OPENROUTER_API_KEY}`,
      'Content-Type': 'application/json',
      'HTTP-Referer': 'https://wiki.sm0ke.org',
      'X-Title': 'MBF Tools Diagnostics',
    },
    body: JSON.stringify({
      model: DIAG_MODEL,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userContent },
      ],
      max_tokens: DIAG_MAX_TOKENS,
    }),
    signal: AbortSignal.timeout(40000),
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new Error(`OpenRouter error ${res.status}: ${errText.slice(0, 200)}`);
  }

  const data = await res.json();
  const content = data.choices?.[0]?.message?.content;
  if (!content) throw new Error('Empty response from AI.');
  return content;
}

// ─── Follow-up chat ───────────────────────────────────────────────────────────

async function handleFollowup(request, env, code, corsHeaders) {
  if (!env.OPENROUTER_API_KEY) {
    return jsonResponse({ ok: false, error: 'AI not configured.' }, 503, corsHeaders);
  }
  if (!code) {
    return jsonResponse({ ok: false, error: 'Missing log code.' }, 400, corsHeaders);
  }

  const ip = request.headers.get('CF-Connecting-IP')
    || request.headers.get('X-Forwarded-For')?.split(',')[0]?.trim()
    || 'unknown';

  // Check IP ban first
  const ban = await getIpBan(env, ip);
  if (ban) {
    return jsonResponse({
      ok: false,
      error: 'Your IP has been banned from AI chat due to policy violations.',
      bannedUntil: ban.bannedUntil,
    }, 403, corsHeaders);
  }

  // Rate limit: 10 requests per minute per IP
  const rateResult = await checkAndIncrementRateLimit(env, ip);
  if (!rateResult.allowed) {
    return jsonResponse({
      ok: false,
      error: 'Rate limit exceeded — maximum 10 questions per minute.',
      retryAfter: rateResult.retryAfter,
    }, 429, { ...corsHeaders, 'Retry-After': String(rateResult.retryAfter) });
  }

  // Load the log record for context
  const raw = await env.LOGS.get(`log:${code}`);
  if (!raw) {
    return jsonResponse({ ok: false, error: `No log found for code ${code}.` }, 404, corsHeaders);
  }

  let body;
  try { body = await request.json(); } catch {
    return jsonResponse({ ok: false, error: 'Invalid JSON body.' }, 400, corsHeaders);
  }

  const question = String(body.question || '').trim();
  if (!question) {
    return jsonResponse({ ok: false, error: 'question is required.' }, 400, corsHeaders);
  }

  // Accept up to last 10 prior messages for context (5 exchanges)
  const history = Array.isArray(body.history)
    ? body.history.slice(-10).filter(m => m && typeof m.role === 'string' && typeof m.content === 'string')
    : [];

  const record = JSON.parse(raw);

  try {
    const result = await runFollowupChat(env, record, question, history);

    if (result.isAbuse) {
      await banIp(env, ip, 'Off-topic usage detected by AI');
      return jsonResponse({
        ok: false,
        error: 'Your question was not related to Beat Saber or your diagnostic data. Your IP has been banned from AI chat for 30 days.',
        banned: true,
      }, 403, corsHeaders);
    }

    return jsonResponse({ ok: true, answer: result.answer }, 200, corsHeaders);
  } catch (err) {
    return jsonResponse({ ok: false, error: err?.message || 'Chat failed.' }, 500, corsHeaders);
  }
}

async function runFollowupChat(env, record, question, history) {
  const payload = record.payload || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const modItems = Array.isArray(mods.items) ? mods.items : [];

  const systemPrompt = [
    'You are a Beat Saber modding support assistant helping a user understand their MBF Tools diagnostic report.',
    'Answer questions about Beat Saber, Meta Quest, MBF Tools, wireless ADB, and the diagnostic data below.',
    'Be concise and specific. If a question is clearly unrelated to Beat Saber or modding, reply: "I can only help with Beat Saber and MBF Tools questions."',
    '',
    `Diagnostic context: BS ${beatSaber.versionName || 'unknown'}, ` +
    `${mods.count || 0} mods (${modItems.slice(0, 8).join(', ')}${modItems.length > 8 ? '…' : ''}), ` +
    `issues: ${(record.issues || []).join('; ') || 'none'}.`,
  ].join('\n');

  const messages = [
    { role: 'system', content: systemPrompt },
    ...history,
    { role: 'user', content: question },
  ];

  const res = await fetch(OPENROUTER_API, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${env.OPENROUTER_API_KEY}`,
      'Content-Type': 'application/json',
      'HTTP-Referer': 'https://wiki.sm0ke.org',
      'X-Title': 'MBF Tools Diagnostics Chat',
    },
    body: JSON.stringify({
      model: DIAG_MODEL,
      messages,
      max_tokens: 800,
    }),
    signal: AbortSignal.timeout(40000),
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new Error(`OpenRouter error ${res.status}: ${errText.slice(0, 200)}`);
  }

  const data = await res.json();
  // Some models return null content — treat it as a soft failure, not a hard error
  const raw = data.choices?.[0]?.message?.content;
  const content = typeof raw === 'string' ? raw.trim() : '';

  if (!content) {
    return { isAbuse: false, answer: "Sorry, I wasn't able to generate a response. Please rephrase your question and try again." };
  }

  // Detect deliberate off-topic abuse via server-side pattern check on the question.
  // We no longer rely on an AI sentinel (which confuses free models).
  if (isOffTopicAbuse(question)) {
    return { isAbuse: true, answer: null };
  }

  return { isAbuse: false, answer: content };
}

function isOffTopicAbuse(question) {
  const q = question.toLowerCase();
  // If the question mentions any on-topic terms it's fine
  const onTopic = ['beat saber', 'beatsaber', 'mbf', 'quest', 'mod', 'adb',
    'wireless', 'crash', 'saber', 'song', 'map', 'log', 'error', 'version',
    'install', 'debug', 'pairing', 'developer', 'patch'];
  if (onTopic.some(t => q.includes(t))) return false;
  // Flag clearly off-topic patterns that have no modding context
  return /write (a |an )?(story|poem|essay|email|letter)|act as |pretend (to be|you are)|ignore (your |all |previous )|jailbreak|bypass (your|the) (rules|filter)/i.test(q);
}

// ─── Rate limiting & IP banning ───────────────────────────────────────────────

async function checkAndIncrementRateLimit(env, ip) {
  const key = `ratelimit:${ip}`;
  const now = Date.now();

  const stored = await env.LOGS.get(key, { type: 'json' }).catch(() => null);
  const windowActive = stored && stored.resetAt > now;
  const count = windowActive ? stored.count : 0;
  const resetAt = windowActive ? stored.resetAt : now + 60_000;

  if (count >= 10) {
    return { allowed: false, retryAfter: Math.ceil((resetAt - now) / 1000) };
  }

  await env.LOGS.put(key, JSON.stringify({ count: count + 1, resetAt }), { expirationTtl: 61 });
  return { allowed: true, remaining: 9 - count };
}

async function getIpBan(env, ip) {
  return env.LOGS.get(`ipban:${ip}`, { type: 'json' }).catch(() => null);
}

async function banIp(env, ip, reason) {
  const bannedUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
  await env.LOGS.put(
    `ipban:${ip}`,
    JSON.stringify({ reason, bannedAt: new Date().toISOString(), bannedUntil }),
    { expirationTtl: 30 * 24 * 60 * 60 },
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

async function generateCode_(env) {
  for (let i = 0; i < 10; i++) {
    const code = Math.random().toString(36).slice(2, 7).toLowerCase();
    if (!await env.LOGS.get(`log:${code}`)) return code;
  }
  return crypto.randomUUID().replace(/-/g, '').slice(0, 8).toLowerCase();
}

function buildActionUrl_(baseUrl, action, code) {
  const safeCode = encodeURIComponent(code);
  switch (action) {
    case 'summary':
      return `${baseUrl}/summary/${safeCode}`;
    case 'message':
      return `${baseUrl}/message/${safeCode}`;
    case 'data':
      return `${baseUrl}/data/${safeCode}`;
    case 'aifix':
      return `${baseUrl}/aifix/${safeCode}`;
    case 'diagnose':
      return `${baseUrl}/diagnose/${safeCode}`;
    case 'view':
    default:
      return `${baseUrl}/${safeCode}`;
  }
}

function parseLogRoute_(url) {
  const path = (url.pathname || '/').replace(/\/+$/, '') || '/';
  const segments = path.split('/').filter(Boolean);

  if (segments.length === 0) {
    return {
      action: normalizeAction_(url.searchParams.get('action')),
      code: normalizeCode_(url.searchParams.get('code')),
    };
  }

  const [first, second] = segments;
  if (['view', 'summary', 'message', 'data', 'aifix', 'diagnose'].includes(first)) {
    return {
      action: normalizeAction_(first),
      code: normalizeCode_(second || url.searchParams.get('code')),
    };
  }

  return {
    action: 'view',
    code: normalizeCode_(first),
  };
}

function normalizeAction_(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return normalized || 'view';
}

function normalizeCode_(value) {
  return String(value || '').trim().toLowerCase();
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
  if (beatSaber.packageName && !beatSaber.installed) issues.push(`Beat Saber package ${beatSaber.packageName} is not installed.`);
  if (Number(logStats.errorCount || 0) > 0) issues.push(`${logStats.errorCount} error log lines were captured.`);
  else if (Number(logStats.warnCount || 0) > 0) issues.push(`${logStats.warnCount} warning log lines were captured.`);
  if (beatSaber.installed && Number(mods.count || 0) === 0) issues.push('Beat Saber is installed but no current mods were detected.');
  const interesting = Array.isArray(beatSaberLogs.interesting) ? beatSaberLogs.interesting : [];
  if (interesting.length > 0) issues.push('Beat Saber logs include crash, exception, or error text.');
  else if (Number(beatSaberLogs.lineCount || 0) > 0) issues.push(`Beat Saber log bundle included ${beatSaberLogs.lineCount} matching line(s).`);
  if (setup.currentGuideStep && !app.setupComplete) issues.push(`User is currently on setup step: ${setup.currentGuideStep}.`);
  let summary;
  if (issues.length > 0) summary = issues.slice(0, 3).join(' ');
  else if (setup.connectedDevice && beatSaber.installed) summary = 'Setup looks healthy. ADB is connected and Beat Saber is installed.';
  else summary = 'No obvious blocker was detected from the uploaded diagnostics.';
  return { summary, issues };
}

function buildMessageText_(record, baseUrl) {
  const payload = record.payload || {};
  const setup = payload.setup || {};
  const beatSaber = payload.beatSaber || {};
  const mods = payload.mods || {};
  const beatSaberLogs = payload.beatSaberLogs || {};
  const logStats = payload.logStats || {};
  const modItems = Array.isArray(mods.items) ? mods.items.slice(0, 15) : [];
  const modSuffix = Array.isArray(mods.items) && mods.items.length > 15 ? ` (+${mods.items.length - 15} more)` : '';
  const issueItems = Array.isArray(record.issues) ? record.issues.slice(0, 6) : [];
  const issueBlock = issueItems.length > 0 ? issueItems.map(i => `- ${i}`).join('\n') : '- No explicit issues were inferred.';
  const modsLine = modItems.length > 0 ? modItems.join(', ') + modSuffix : 'none detected';
  return [
    `# ${COMMAND_PREFIX} ${record.code}`, '',
    '**Summary**', record.summary || 'No short summary is available.', '',
    '**Issues**', issueBlock, '',
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
    '', '**Links**',
    `- Viewer: ${buildActionUrl_(baseUrl, 'view', record.code)}`,
    `- Summary API: ${buildActionUrl_(baseUrl, 'summary', record.code)}`,
  ].join('\n');
}

function jsonResponse(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...extraHeaders } });
}
function textResponse(text, extraHeaders = {}) {
  return new Response(String(text || ''), { status: 200, headers: { 'Content-Type': 'text/plain; charset=utf-8', ...extraHeaders } });
}
function htmlResponse(html, extraHeaders = {}) {
  return new Response(html, { status: 200, headers: { 'Content-Type': 'text/html; charset=utf-8', ...extraHeaders } });
}
function escapeHtml_(value) {
  return String(value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ─── Shared styles (MBF aesthetic) ───────────────────────────────────────────

const BASE_STYLE = `
  @import url('https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap');
  *{box-sizing:border-box;}
  :root{color-scheme:dark;}
  body{margin:0;font-family:'Roboto',sans-serif;background:#002040;color:#fff;min-height:100vh;overflow-y:scroll;}
  @media(prefers-reduced-motion){#anim-bg{display:none;}}
  #anim-bg{display:block;position:fixed;z-index:-100;top:0;left:0;width:100%;height:100%;filter:blur(0.5em);}
  .block rect{fill:#000078;stroke-width:4px;stroke:#000050;}
  .block path{stroke-width:4px;stroke:#0061ff;fill:#b9baff;}
  .bomb path{fill:url(#bomb-gradient);stroke:#000;transform-box:fill-box;transform-origin:50% 50%;}
  .red-block rect{stroke:#500000;fill:#780000;}
  .red-block path,ellipse{stroke:red;fill:#ffe8e8;}
  h1,h2,h3{margin-top:0;margin-bottom:0;}
  a{color:#0066ff;font-weight:700;text-decoration:none;} a:hover{text-decoration:underline;}
  .page{display:flex;flex-direction:column;align-items:center;padding:16px 8px 40px;position:relative;z-index:0;}
  .main{width:100%;max-width:min(72rem,98vw);}
  .container{background:#000000f2;padding:16px;margin:5px 0;border-radius:10px;}
  .section-title{font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:#99d9ea;margin-bottom:10px;}
  .muted{color:#aaa;font-size:14px;}
  .grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:8px;}
  .grid4{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px;}
  .stat-box{background:#0d1825;border-radius:10px;padding:12px 14px;}
  .stat-label{font-size:11px;text-transform:uppercase;letter-spacing:.07em;color:#99d9ea;margin-bottom:4px;}
  .stat-value{font-size:15px;font-weight:500;}
  .fact-row{display:flex;justify-content:space-between;gap:16px;padding:9px 12px;border-radius:8px;background:#0d1825;font-size:14px;margin-bottom:4px;}
  .fact-key{color:#99d9ea;} .fact-val{text-align:right;font-weight:500;}
  .btn{background:#333;border:0;border-radius:10px;color:#fff;font-weight:500;padding:8px 16px;cursor:pointer;font-family:inherit;font-size:14px;user-select:none;position:relative;isolation:isolate;overflow:hidden;}
  .btn::after{content:'';position:absolute;inset:0;background:#1127f3;opacity:0;transition:opacity .25s;z-index:-1;}
  .btn:hover::after{opacity:1;}
  .btn:disabled{opacity:.4;cursor:default;}
  .btn:disabled::after{display:none;}
  .btn-danger{background:#b30a0a;} .btn-danger::after{background:#d10a0a;}
  .btn-sm{padding:5px 12px;font-size:13px;}
  .tab-bar{display:flex;flex-wrap:wrap;gap:2px;align-items:flex-end;padding:0 2px;margin-top:8px;}
  .tab{background:#111;border:1px solid #2a2a2a;border-bottom:none;border-radius:8px 8px 0 0;color:#aaa;font-weight:500;padding:9px 16px;cursor:pointer;font-family:inherit;font-size:13px;user-select:none;text-transform:capitalize;transition:background .15s,color .15s;white-space:nowrap;margin-bottom:-1px;position:relative;z-index:0;}
  .tab:hover{background:#1d1d1d;color:#fff;}
  .tab.active{background:#000000f2;border-color:#333;color:#fff;z-index:2;cursor:default;}
  .tab-content{border:1px solid #333;border-radius:0 8px 8px 8px;background:#000000f2;padding:16px;position:relative;z-index:1;}
  .tab-panel{display:none;} .tab-panel.active{display:block;}
  .badge{display:inline-flex;align-items:center;gap:4px;border-radius:10px;padding:4px 10px;font-size:12px;font-weight:700;}
  .badge-ok{background:#00543380;color:#5fffb0;}
  .badge-warn{background:#6b4d0080;color:#ffc43a;}
  .badge-bad{background:#5c000080;color:#ff7070;}
  .badge-neutral{background:#22303f;color:#aac8e0;}
  pre{white-space:pre-wrap;word-break:break-word;background:#111;border-radius:8px;padding:12px;overflow:auto;font-family:Consolas,monospace;font-size:13px;line-height:1.5;}
  ul{margin:8px 0 0 18px;padding:0;} li{line-height:1.6;font-size:14px;}
  input[type=password]{width:100%;background:#111827;border:1px solid #334;border-radius:10px;padding:11px 14px;color:#fff;font-size:15px;font-family:inherit;margin:10px 0 14px;}
  input[type=password]:focus{outline:none;border-color:#0066ff;}
  .error-msg{color:#ff7070;font-size:14px;margin-bottom:10px;}
  ::-webkit-scrollbar{width:7px;} ::-webkit-scrollbar-track{background:rgba(0,0,0,.2);} ::-webkit-scrollbar-thumb{background:rgba(255,255,255,.15);border-radius:5px;}
  table{width:100%;border-collapse:collapse;}
  th{font-size:11px;text-transform:uppercase;letter-spacing:.07em;color:#99d9ea;padding:8px 12px;text-align:left;border-bottom:1px solid #1a2a3a;}
  td{padding:9px 12px;font-size:14px;border-bottom:1px solid #0d1825;vertical-align:middle;}
  tr:last-child td{border-bottom:none;}
  tr:hover td{background:rgba(255,255,255,.03);}
  @media(max-width:600px){.page{padding:8px 4px 32px;}.tab{font-size:13px;padding:8px 12px;}}
`;

const ANIM_BG_SCRIPT = `
(function(){
  const V=10/1e3,AV=.2/1e3,SV=.4,BV=.4,SJ=.1,DUR=5e3,JIT=1e3,DENS=2e-5;
  function el(tag,attrs){const e=document.createElementNS("http://www.w3.org/2000/svg",tag);for(const k in attrs)e.setAttribute(k==="className"?"class":k,attrs[k]);return e;}
  function count(){return Math.ceil(Math.max(innerWidth*innerHeight*DENS/devicePixelRatio,20));}
  class Note{
    constructor(root,top=false){this.node=el("g");root.appendChild(this.node);this.init(top);}
    init(top=true){
      let t=Math.floor(7*Math.random())-2;if(t<0)t+=2;this.type=t;
      this.av=(2*Math.random()-1)*AV;this.rot=1-(2*Math.random()-1)*Math.PI;
      const n=2*Math.random()-1,r=2*Math.random()-1;
      this.sc=1-n*SV;const se=1-r*SV;this.br=1-n*BV;const be=1-r*BV;
      this.pos=[Math.random()*(innerWidth+200*this.sc)-this.sc*100,
        top?-100*this.sc:Math.random()*(innerHeight+150*this.sc)-this.sc*100];
      const ang=Math.random()*Math.PI/2+Math.PI/4;
      this.vel=[V*Math.cos(ang),V*Math.sin(ang)];
      const tte=(innerHeight-this.pos[1]+se)/this.vel[1];
      this.scs=(se-this.sc)/tte;this.bcs=t!==4?(be-this.br)/tte:0;
      this.anim=null;this.build();this.tick(0);
    }
    build(){
      while(this.node.lastChild)this.node.removeChild(this.node.lastChild);
      this.node.className.baseVal="";
      const t=this.type;
      if(t===4){this.node.classList.add("bomb");this.node.appendChild(el("path",{d:"M 16.588 25.261 L 0.271 25.261 L -9.292 58.594 L -8.873 25.26 L -19.645 25.26 L -24.671 29.566 L -21.536 21.708 L -25.928 6.658 L -66.658 9.545 L -28.483 -2.096 L -31.32 -11.818 L -30.336 -12.56 L -41.991 -20.297 L -24.148 -17.223 L -17.527 -22.213 L -32.214 -56.515 L -9.796 -28.04 L -5.567 -31.228 L -2.62 -47.336 L 0.907 -33.318 L 13.827 -23.318 L 46.439 -48.293 L 21.605 -17.299 L 26.002 -13.896 L 39.033 -14.184 L 27.412 -7.903 L 24.15 2.09 L 60.606 22.849 L 21.106 11.417 L 18.779 18.547 L 25.405 33.345 L 16.652 25.066 L 16.588 25.261 Z"}));return;}
      const red=t===1||t===3;
      this.node.classList.add("block");if(red)this.node.classList.add("red-block");
      this.node.appendChild(el("rect",{x:-50,y:-50,width:100,height:100,rx:20,ry:20}));
      if(t===0||t===1)this.node.appendChild(el("path",{d:"M -40 -40 L 40 -40 L 40 -30 L 0 -10 L -40 -30 Z"}));
      else this.node.appendChild(el("ellipse",{cx:0,cy:0,rx:20,ry:20}));
    }
    next(dt){
      return{pos:[this.pos[0]+this.vel[0]*dt,this.pos[1]+this.vel[1]*dt],
        rot:this.rot+this.av*dt,
        sc:Math.min(Math.max(this.sc+this.scs*dt,1-SJ-SV),1+SJ+SV),
        br:Math.min(Math.max(this.br+this.bcs*dt,1-SJ-BV),1+SJ+BV)};
    }
    tick(dt){
      if(dt>0){const s=this.next(dt);this.pos=s.pos;this.rot=s.rot;this.sc=s.sc;this.br=s.br;}
      if(this.pos[1]>100*this.sc+innerHeight||this.pos[0]<-100*this.sc||this.pos[0]>100*this.sc+innerWidth)
        {this.init();return;}
      const dur=DUR+(2*Math.random()-1)*JIT,nx=this.next(dur);
      const kf=[
        {transform:\`translate(\${this.pos[0]}px,\${this.pos[1]}px) rotate(\${this.rot}rad) scale(\${this.sc})\`,filter:\`brightness(\${this.br})\`},
        {transform:\`translate(\${nx.pos[0]}px,\${nx.pos[1]}px) rotate(\${nx.rot}rad) scale(\${nx.sc})\`,filter:\`brightness(\${nx.br})\`}
      ];
      this.anim=this.node.animate(kf,dur);
      this.anim.onfinish=()=>this.tick(this.anim?.currentTime??dur);
    }
  }
  const svg=el("svg",{id:"anim-bg",viewBox:\`0 0 \${innerWidth} \${innerHeight}\`});
  const defs=el("defs",{});const grad=el("radialGradient",{id:"bomb-gradient"});
  grad.appendChild(el("stop",{offset:0,style:"stop-color:rgb(20,20,20)"}));
  grad.appendChild(el("stop",{offset:.75,style:"stop-color:rgb(3,3,3)"}));
  defs.appendChild(grad);svg.appendChild(defs);document.body.appendChild(svg);
  window.addEventListener("resize",()=>svg.setAttribute("viewBox",\`0 0 \${innerWidth} \${innerHeight}\`));
  const notes=[];for(let i=0;i<count();i++)notes.push(new Note(svg));
  setInterval(()=>{const t=count();if(notes.length<t)notes.push(new Note(svg,true));},500);
})();
`;

// ─── Admin HTML ───────────────────────────────────────────────────────────────

function renderAdminLogin_(error = '') {
  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
  <meta name="theme-color" content="#99d9ea"/><title>Admin — MBF Tools</title>
  <style>${BASE_STYLE}
    .login-wrap{display:flex;align-items:center;justify-content:center;min-height:100vh;}
    .login-card{background:#000000e6;border-radius:10px;padding:28px;width:100%;max-width:380px;}
    .remember-row{display:flex;align-items:center;gap:10px;margin-bottom:16px;cursor:pointer;font-size:14px;color:#aaa;user-select:none;}
    .remember-row input[type=checkbox]{width:16px;height:16px;accent-color:#0066ff;cursor:pointer;flex-shrink:0;margin:0;}
  </style></head>
  <body><script>${ANIM_BG_SCRIPT}</script><div class="login-wrap"><div class="login-card">
    <h1 style="margin-bottom:6px;">MBF Tools</h1>
    <p class="muted" style="margin:0 0 4px;">Admin panel — enter your password to continue.</p>
    ${error ? `<p class="error-msg" style="margin-top:10px;">${escapeHtml_(error)}</p>` : ''}
    <form method="POST" action="/admin/login">
      <input type="password" name="password" placeholder="Password" autofocus required/>
      <label class="remember-row">
        <input type="checkbox" name="remember" value="1"/>
        Remember me for 30 days
      </label>
      <button class="btn" type="submit" style="width:100%;padding:10px;font-size:15px;">Sign in</button>
    </form>
  </div></div></body></html>`;
}

function renderAdminPage_(rows) {
  // Unicode-escape <, >, & so the HTML parser can never misread the inline JSON block
  // as a closing tag, CDATA boundary, or entity reference, regardless of log content.
  const rowsJson = JSON.stringify(rows)
    .replace(/</g, '\\u003C')
    .replace(/>/g, '\\u003E')
    .replace(/&/g, '\\u0026');

  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
  <meta name="theme-color" content="#99d9ea"/><title>Admin — MBF Tools</title>
  <style>${BASE_STYLE}
    .sort-th{cursor:pointer;user-select:none;white-space:nowrap;}
    .sort-th:hover{color:#fff;}
    .sort-ind{font-size:10px;margin-left:3px;opacity:.7;}
    .cb-cell{width:36px;padding-left:12px!important;text-align:center;}
    input[type=checkbox]{accent-color:#0066ff;width:15px;height:15px;cursor:pointer;vertical-align:middle;}
    .bulk-bar{display:none;align-items:center;gap:10px;background:#1a0808;border:1px solid #500;border-radius:10px;padding:10px 16px;margin-bottom:6px;flex-wrap:wrap;}
    .search-wrap{background:#000000f2;border-radius:10px;padding:10px;margin-bottom:6px;}
    .search-input{width:100%;background:#0d1825;border:1px solid #1a2a3a;border-radius:8px;padding:10px 14px;color:#fff;font-size:14px;font-family:inherit;outline:none;transition:border-color .2s;}
    .search-input:focus{border-color:#0066ff;}
    .expand-cell{padding:14px 18px;background:#05101e;border-top:1px solid #1a2a3a;}
    .expand-inner{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:14px;}
    .page-active{background:#0066ff!important;}
    .pagination{display:flex;gap:5px;align-items:center;padding:10px 12px;flex-wrap:wrap;}
    tr.expanded-parent>td{background:rgba(255,255,255,.04)!important;}
  </style></head>
  <body><script>${ANIM_BG_SCRIPT}</script>
  <div class="page"><div class="main">

    <!-- Header -->
    <div style="display:flex;align-items:center;justify-content:space-between;margin:8px 0 12px;flex-wrap:wrap;gap:8px;">
      <div>
        <h1 style="font-size:22px;margin-bottom:4px;">MBF Tools Admin</h1>
        <p class="muted" style="margin:0;">Shared debug logs</p>
      </div>
      <form method="POST" action="/admin/logout" style="display:contents;">
        <button class="btn btn-sm" type="submit" style="background:#1a1a2e;">Sign out</button>
      </form>
    </div>

    <!-- Stats bar -->
    <div class="grid4" style="margin-bottom:6px;">
      <div class="stat-box"><div class="stat-label">Total logs</div><div class="stat-value" id="stat-total">${escapeHtml_(String(rows.length))}</div></div>
      <div class="stat-box"><div class="stat-label">Showing</div><div class="stat-value" id="stat-showing">${escapeHtml_(String(rows.length))}</div></div>
      <div class="stat-box"><div class="stat-label">Oldest</div><div class="stat-value" id="stat-oldest" style="font-size:13px;">—</div></div>
      <div class="stat-box"><div class="stat-label">Newest</div><div class="stat-value" id="stat-newest" style="font-size:13px;">—</div></div>
    </div>

    <!-- Search -->
    <div class="search-wrap">
      <input class="search-input" type="text" id="search-box" placeholder="Search by code or summary…" oninput="onSearch(this.value)" autocomplete="off"/>
    </div>

    <!-- Bulk action bar (shown when items selected) -->
    <div id="bulk-bar" class="bulk-bar">
      <span id="bulk-count" style="font-size:14px;font-weight:500;flex:1;"></span>
      <button class="btn btn-sm btn-danger" onclick="bulkDelete()">Delete selected</button>
      <button class="btn btn-sm" onclick="clearSelection()">Clear</button>
    </div>

    <!-- Table -->
    <div class="container" style="padding:0;overflow:hidden;">
      <div style="overflow-x:auto;">
        <table>
          <thead><tr>
            <th class="cb-cell"><input type="checkbox" id="select-all" onclick="toggleSelectAll()"/></th>
            <th class="sort-th" onclick="setSort('code')">Code<span class="sort-ind" id="sort-code">↕</span></th>
            <th class="sort-th" onclick="setSort('createdAt')">Created<span class="sort-ind" id="sort-createdAt">↕</span></th>
            <th class="sort-th" onclick="setSort('summary')">Summary<span class="sort-ind" id="sort-summary">↕</span></th>
            <th style="width:110px;">Actions</th>
          </tr></thead>
          <tbody id="log-tbody"></tbody>
        </table>
      </div>
      <div id="pagination" class="pagination"></div>
    </div>

  </div></div>

  <script>
  var ALL = ${rowsJson};
  var PAGE_SIZE = 25;
  var PUB = 'https://logs.mbf.tools';
  var state = { q: '', sortKey: 'createdAt', sortDir: -1, page: 0, selected: {} };
  var expandCache = {};
  var expandOpen = null;

  function esc(s) {
    return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function getFiltered() {
    var q = state.q.toLowerCase();
    var rows = q
      ? ALL.filter(function(r){ return r.code.indexOf(q)>=0 || (r.summary||'').toLowerCase().indexOf(q)>=0; })
      : ALL.slice();
    var sk = state.sortKey, sd = state.sortDir;
    rows.sort(function(a,b){
      var va=a[sk]||'', vb=b[sk]||'';
      return va<vb ? sd : va>vb ? -sd : 0;
    });
    return rows;
  }

  function render() {
    var filtered = getFiltered();
    var totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
    if (state.page >= totalPages) state.page = totalPages - 1;
    var pageStart = state.page * PAGE_SIZE;
    var pageRows = filtered.slice(pageStart, pageStart + PAGE_SIZE);
    var selCount = Object.keys(state.selected).length;

    // stats
    document.getElementById('stat-total').textContent = ALL.length;
    document.getElementById('stat-showing').textContent =
      filtered.length !== ALL.length ? filtered.length + ' matched' : ALL.length;

    // bulk bar
    document.getElementById('bulk-bar').style.display = selCount > 0 ? 'flex' : 'none';
    document.getElementById('bulk-count').textContent =
      selCount + ' log' + (selCount===1?'':'s') + ' selected';

    // sort indicators
    ['code','createdAt','summary'].forEach(function(k){
      var el = document.getElementById('sort-' + k);
      if (el) el.textContent = state.sortKey===k ? (state.sortDir===1?' ▲':' ▼') : ' ↕';
    });

    // table body
    var tbody = document.getElementById('log-tbody');
    tbody.innerHTML = '';

    if (pageRows.length === 0) {
      var empty = document.createElement('tr');
      empty.innerHTML = '<td colspan="5" style="text-align:center;padding:32px;color:#aaa;">No logs match your search.</td>';
      tbody.appendChild(empty);
    } else {
      pageRows.forEach(function(r) {
        var isSel = !!state.selected[r.code];
        var isExpanded = expandOpen === r.code;
        var dateStr = r.createdAt ? new Date(r.createdAt).toLocaleString() : '—';

        // main row
        var tr = document.createElement('tr');
        tr.style.cursor = 'pointer';
        if (isExpanded) tr.className = 'expanded-parent';
        tr.innerHTML =
          '<td class="cb-cell"><input type="checkbox"' + (isSel?' checked':'') +
            ' data-code="' + esc(r.code) + '" onclick="onCbClick(event,this.dataset.code)" /></td>' +
          '<td><code style="font-family:Consolas,monospace;background:#111;padding:2px 6px;border-radius:5px;">' + esc(r.code) + '</code></td>' +
          '<td style="color:#aaa;white-space:nowrap;">' + esc(dateStr) + '</td>' +
          '<td style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#cde;">' + esc(r.summary||'—') + '</td>' +
          '<td><div style="display:flex;gap:5px;">' +
            '<a href="' + PUB + '/' + encodeURIComponent(r.code) + '" target="_blank" onclick="event.stopPropagation()">' +
              '<button class="btn btn-sm" type="button">View</button></a>' +
            '<button class="btn btn-sm btn-danger" type="button" data-code="' + esc(r.code) + '" onclick="deleteSingle(event,this.dataset.code)">Del</button>' +
          '</div></td>';
        tr.addEventListener('click', function(e) {
          if (e.target.tagName==='INPUT'||e.target.tagName==='BUTTON'||e.target.tagName==='A') return;
          toggleExpand(r.code);
        });
        tbody.appendChild(tr);

        // expand row
        var xtr = document.createElement('tr');
        xtr.id = 'xrow-' + r.code;
        xtr.style.display = isExpanded ? '' : 'none';
        var xtd = document.createElement('td');
        xtd.colSpan = 5;
        xtd.className = 'expand-cell';
        var xdiv = document.createElement('div');
        xdiv.id = 'xcontent-' + r.code;
        if (isExpanded && expandCache[r.code]) {
          xdiv.innerHTML = expandCache[r.code];
        } else if (isExpanded) {
          xdiv.innerHTML = '<p class="muted" style="margin:0;font-size:13px;">Loading…</p>';
          loadExpand(r.code, xdiv);
        }
        xtd.appendChild(xdiv);
        xtr.appendChild(xtd);
        tbody.appendChild(xtr);
      });
    }

    // select-all checkbox state
    var sa = document.getElementById('select-all');
    if (sa) {
      var allSel = pageRows.length > 0 && pageRows.every(function(r){ return !!state.selected[r.code]; });
      var someSel = pageRows.some(function(r){ return !!state.selected[r.code]; });
      sa.checked = allSel;
      sa.indeterminate = someSel && !allSel;
    }

    renderPagination(totalPages, filtered.length);
  }

  function renderPagination(totalPages, total) {
    var c = document.getElementById('pagination');
    c.innerHTML = '';
    if (totalPages <= 1) return;

    var info = document.createElement('span');
    info.className = 'muted';
    info.style.cssText = 'font-size:13px;margin-right:4px;flex:1;';
    var lo = state.page * PAGE_SIZE + 1;
    var hi = Math.min((state.page + 1) * PAGE_SIZE, total);
    info.textContent = lo + '–' + hi + ' of ' + total;
    c.appendChild(info);

    function mkBtn(label, disabled, active, onClick) {
      var b = document.createElement('button');
      b.className = 'btn btn-sm' + (active ? ' page-active' : '');
      b.textContent = label;
      b.disabled = !!disabled;
      if (!disabled) b.onclick = onClick;
      c.appendChild(b);
    }

    mkBtn('←', state.page===0, false, function(){ state.page--; render(); });
    var s = Math.max(0, Math.min(state.page - 3, totalPages - 7));
    var e = Math.min(totalPages, s + 7);
    for (var i=s; i<e; i++) {
      (function(p){ mkBtn(p+1, false, p===state.page, function(){ state.page=p; render(); }); })(i);
    }
    mkBtn('→', state.page>=totalPages-1, false, function(){ state.page++; render(); });
  }

  function loadExpand(code, container) {
    fetch('/data/' + encodeURIComponent(code))
      .then(function(r){ return r.json(); })
      .then(function(data) {
        if (!data.ok) { container.innerHTML='<p style="color:#ff7070;margin:0;font-size:13px;">Failed to load details.</p>'; return; }
        var p=data.payload||{}, setup=p.setup||{}, bs=p.beatSaber||{}, mods=p.mods||{};
        var issues = Array.isArray(data.issues) ? data.issues : [];
        var issueHtml = issues.length > 0
          ? issues.map(function(i){
              return '<div style="background:#200a0a;border-radius:6px;padding:6px 10px;margin-bottom:4px;font-size:13px;display:flex;gap:8px;align-items:flex-start;">' +
                '<span style="color:#ff7070;flex-shrink:0;margin-top:1px;">&#9888;</span><span>' + esc(i) + '</span></div>';
            }).join('')
          : '<p class="muted" style="margin:0;font-size:13px;">No issues detected.</p>';
        function fr(k,v){
          return '<div class="fact-row" style="margin-bottom:3px;"><span class="fact-key">' + esc(k) + '</span><span class="fact-val">' + esc(String(v)) + '</span></div>';
        }
        var html =
          '<div class="expand-inner">' +
            '<div><div class="section-title" style="margin-bottom:8px;">Issues</div>' + issueHtml + '</div>' +
            '<div><div class="section-title" style="margin-bottom:8px;">Status</div>' +
              fr('Guide step', setup.currentGuideStep||'Not in setup') +
              fr('ADB', setup.connectedDevice||'Not connected') +
              fr('Dev mode', setup.developerModeEnabled?'On':'Off') +
              fr('Wireless debug', setup.wirelessDebuggingEnabled?'On':'Off') +
              fr('Beat Saber', bs.installed?(bs.versionName||'Installed'):'Not installed') +
              fr('Mods', String(Number(mods.count||0))) +
            '</div>' +
            '<div><div class="section-title" style="margin-bottom:8px;">Links</div>' +
              '<div style="display:flex;flex-direction:column;gap:8px;">' +
                '<a href="' + PUB + '/' + encodeURIComponent(code) + '" target="_blank" style="font-size:13px;">&#128203; View log</a>' +
                '<a href="' + PUB + '/message/' + encodeURIComponent(code) + '" target="_blank" style="font-size:13px;">&#128172; Message text</a>' +
                '<a href="' + PUB + '/summary/' + encodeURIComponent(code) + '" target="_blank" style="font-size:13px;">&#128196; Summary API</a>' +
                '<a href="' + PUB + '/aifix/' + encodeURIComponent(code) + '" target="_blank" style="font-size:13px;">&#129302; AI fix</a>' +
              '</div>' +
            '</div>' +
          '</div>';
        expandCache[code] = html;
        container.innerHTML = html;
      })
      .catch(function(){ container.innerHTML='<p style="color:#ff7070;margin:0;font-size:13px;">Failed to load details.</p>'; });
  }

  function toggleExpand(code) {
    expandOpen = (expandOpen === code) ? null : code;
    render();
  }

  function onCbClick(e, code) {
    e.stopPropagation();
    if (e.target.checked) state.selected[code] = true;
    else delete state.selected[code];
    render();
  }

  function toggleSelectAll() {
    var filtered = getFiltered();
    var pageRows = filtered.slice(state.page*PAGE_SIZE, (state.page+1)*PAGE_SIZE);
    var allSel = pageRows.every(function(r){ return !!state.selected[r.code]; });
    if (allSel) pageRows.forEach(function(r){ delete state.selected[r.code]; });
    else pageRows.forEach(function(r){ state.selected[r.code] = true; });
    render();
  }

  function clearSelection() {
    state.selected = {};
    render();
  }

  function deleteSingle(e, code) {
    e.stopPropagation();
    if (!confirm('Delete log ' + code + '?')) return;
    var f = document.createElement('form');
    f.method='POST'; f.action='/admin/delete';
    var inp = document.createElement('input');
    inp.type='hidden'; inp.name='code'; inp.value=code;
    f.appendChild(inp); document.body.appendChild(f); f.submit();
  }

  function bulkDelete() {
    var codes = Object.keys(state.selected);
    if (!codes.length) return;
    if (!confirm('Delete ' + codes.length + ' selected log(s)? This cannot be undone.')) return;
    var f = document.createElement('form');
    f.method='POST'; f.action='/admin/bulk-delete';
    codes.forEach(function(code){
      var inp = document.createElement('input');
      inp.type='hidden'; inp.name='code'; inp.value=code;
      f.appendChild(inp);
    });
    document.body.appendChild(f); f.submit();
  }

  function setSort(key) {
    if (state.sortKey===key) state.sortDir *= -1;
    else { state.sortKey=key; state.sortDir = key==='createdAt' ? -1 : 1; }
    state.page = 0;
    render();
  }

  var searchTimer;
  function onSearch(val) {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(function(){
      state.q = val.trim();
      state.page = 0;
      state.selected = {};
      expandOpen = null;
      render();
    }, 180);
  }

  // Init date range in stats boxes
  (function(){
    var dates = ALL.map(function(r){ return r.createdAt; }).filter(Boolean).sort();
    if (dates.length) {
      document.getElementById('stat-oldest').textContent = new Date(dates[0]).toLocaleDateString();
      document.getElementById('stat-newest').textContent = new Date(dates[dates.length-1]).toLocaleDateString();
    }
    render();
  })();
  </script>
  </body></html>`;
}

// ─── Viewer HTML ──────────────────────────────────────────────────────────────

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

  const pill = (ok, label) => `<span class="badge ${ok ? 'badge-ok' : 'badge-bad'}">${escapeHtml_(label)}</span>`;
  const bsStatus = beatSaber.installed ? 'badge-ok' : 'badge-warn';

  const issueList = Array.isArray(record.issues) && record.issues.length > 0
    ? record.issues.map(i => `<div style="background:#0d1825;border-radius:8px;padding:9px 12px;margin-bottom:5px;font-size:14px;display:flex;gap:8px;align-items:flex-start;"><span style="color:#ff7070;margin-top:1px;">⚠</span><span>${escapeHtml_(i)}</span></div>`).join('')
    : '<p class="muted">No explicit issues were inferred.</p>';
  const problemList = recentProblems.length > 0
    ? `<ul>${recentProblems.map(l => `<li>${escapeHtml_(l)}</li>`).join('')}</ul>`
    : '<p class="muted">No recent warning or error lines were captured.</p>';
  const modList = modItems.length > 0
    ? modItems.map(i => `<div style="background:#0d1825;border-radius:8px;padding:8px 12px;margin-bottom:4px;font-size:14px;">${escapeHtml_(i)}</div>`).join('')
    : '<p class="muted">No installed mods were detected.</p>';
  const beatSaberProblems = beatSaberInteresting.length > 0
    ? `<ul>${beatSaberInteresting.map(l => `<li>${escapeHtml_(l)}</li>`).join('')}</ul>`
    : '<p class="muted">No Beat Saber error-like lines were detected.</p>';

  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width,initial-scale=1"/>
    <meta name="theme-color" content="#99d9ea"/>
    <title>MBF Tools Debug Viewer</title>
    <style>
      ${BASE_STYLE}
      .ai-result{white-space:pre-wrap;background:#111;border-radius:8px;padding:14px;font-size:14px;line-height:1.6;}
    </style>
  </head>
  <body>
    <script>${ANIM_BG_SCRIPT}</script>
    <div class="page">
      <div class="main">

        <!-- Header -->
        <div class="container">
          <h1 style="font-size:22px;margin-bottom:6px;">MBF Tools Debug Viewer</h1>
          <p style="margin:0 0 10px;font-size:15px;color:#cde;">${escapeHtml_(record.summary || 'No short summary is available.')}</p>
          <p class="muted" style="margin:0 0 12px;">Code: <code style="font-family:Consolas,monospace;background:#111;padding:1px 5px;border-radius:4px;">${escapeHtml_(record.code || '')}</code> &nbsp;·&nbsp; Command: <code style="font-family:Consolas,monospace;background:#111;padding:1px 5px;border-radius:4px;">${escapeHtml_(record.command || '')}</code> &nbsp;·&nbsp; ${escapeHtml_(record.createdAt || '')}</p>
          <div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:14px;">
            ${pill(!!setup.connectedDevice, 'ADB ' + (setup.connectedDevice ? 'connected' : 'not connected'))}
            ${pill(!!setup.wirelessDebuggingEnabled, 'Wireless Debugging ' + (setup.wirelessDebuggingEnabled ? 'on' : 'off'))}
            ${pill(!!setup.developerModeEnabled, 'Developer mode ' + (setup.developerModeEnabled ? 'on' : 'off'))}
            <span class="badge ${bsStatus}">Beat Saber ${escapeHtml_(beatSaber.installed ? 'installed' : 'not found')}</span>
          </div>
          <!-- Stats bar -->
          <div class="grid4">
            <div class="stat-box"><div class="stat-label">Guide step</div><div class="stat-value">${escapeHtml_(setup.currentGuideStep || 'Not in setup')}</div></div>
            <div class="stat-box"><div class="stat-label">ADB</div><div class="stat-value">${escapeHtml_(setup.connectedDevice ? setup.connectedDevice : 'Not connected')}</div></div>
            <div class="stat-box"><div class="stat-label">Beat Saber</div><div class="stat-value">${escapeHtml_(beatSaber.installed ? (beatSaber.versionName || 'Installed') : 'Not installed')}</div></div>
            <div class="stat-box"><div class="stat-label">Mods</div><div class="stat-value">${escapeHtml_(String(Number(mods.count || 0)))}</div></div>
            <div class="stat-box"><div class="stat-label">Errors</div><div class="stat-value" style="color:${Number(logStats.errorCount||0)>0?'#ff7070':'#fff'}">${escapeHtml_(String(Number(logStats.errorCount || 0)))}</div></div>
            <div class="stat-box"><div class="stat-label">Warnings</div><div class="stat-value" style="color:${Number(logStats.warnCount||0)>0?'#ffc43a':'#fff'}">${escapeHtml_(String(Number(logStats.warnCount || 0)))}</div></div>
            <div class="stat-box"><div class="stat-label">BS log lines</div><div class="stat-value">${escapeHtml_(String(Number(beatSaberLogs.lineCount || 0)))}</div></div>
          </div>
        </div><!-- end .container (header) -->

        <!-- Browser tabs -->
        <div class="tab-bar">
          <button class="tab active" type="button" data-tab="tab-overview">Overview</button>
          <button class="tab" type="button" data-tab="tab-setup">Setup</button>
          <button class="tab" type="button" data-tab="tab-mods">Mods</button>
          <button class="tab" type="button" data-tab="tab-bslogs">Beat Saber Logs</button>
          <button class="tab" type="button" data-tab="tab-applogs">App Logs</button>
          <button class="tab" type="button" data-tab="tab-json">Raw JSON</button>
        </div>

        <div class="tab-content">
          <div id="tab-overview" class="tab-panel active">
            <div class="grid2">
              <div><div class="section-title">Inferred issues</div>${issueList}</div>
              <div><div class="section-title">Recent app problems</div>${problemList}</div>
              <div><div class="section-title">Beat Saber log highlights</div>${beatSaberProblems}</div>
              <div><div class="section-title">Quick facts</div>
                <div class="fact-row"><span class="fact-key">Pairing port</span><span class="fact-val">${escapeHtml_(String(setup.pairingPort || 'Unknown'))}</span></div>
                <div class="fact-row"><span class="fact-key">Debug port</span><span class="fact-val">${escapeHtml_(String(setup.debugPort || 'Unknown'))}</span></div>
                <div class="fact-row"><span class="fact-key">Package</span><span class="fact-val">${escapeHtml_(beatSaber.packageName || 'Unknown')}</span></div>
                <div class="fact-row"><span class="fact-key">Version</span><span class="fact-val">${escapeHtml_(beatSaber.versionName || 'Unknown')}</span></div>
              </div>
            </div>
          </div>

          <div id="tab-setup" class="tab-panel">
            <div class="grid2">
              <div><div class="section-title">Setup status</div>
                <div class="fact-row"><span class="fact-key">Developer mode</span><span class="fact-val">${escapeHtml_(setup.developerModeEnabled ? 'On' : 'Off')}</span></div>
                <div class="fact-row"><span class="fact-key">Wireless Debugging</span><span class="fact-val">${escapeHtml_(setup.wirelessDebuggingEnabled ? 'On' : 'Off')}</span></div>
                <div class="fact-row"><span class="fact-key">Connected device</span><span class="fact-val">${escapeHtml_(setup.connectedDevice || 'Not connected')}</span></div>
                <div class="fact-row"><span class="fact-key">Current guide step</span><span class="fact-val">${escapeHtml_(setup.currentGuideStep || 'Not in setup')}</span></div>
                <div class="fact-row"><span class="fact-key">Pairing port</span><span class="fact-val">${escapeHtml_(String(setup.pairingPort || 'Unknown'))}</span></div>
                <div class="fact-row"><span class="fact-key">Debug port</span><span class="fact-val">${escapeHtml_(String(setup.debugPort || 'Unknown'))}</span></div>
              </div>
              <div><div class="section-title">Beat Saber status</div>
                <div class="fact-row"><span class="fact-key">Installed</span><span class="fact-val">${escapeHtml_(beatSaber.installed ? 'Yes' : 'No')}</span></div>
                <div class="fact-row"><span class="fact-key">Package</span><span class="fact-val">${escapeHtml_(beatSaber.packageName || 'Unknown')}</span></div>
                <div class="fact-row"><span class="fact-key">Version</span><span class="fact-val">${escapeHtml_(beatSaber.versionName || 'Unknown')}</span></div>
                <div class="fact-row"><span class="fact-key">Detected mods</span><span class="fact-val">${escapeHtml_(String(Number(mods.count || 0)))}</span></div>
                <div class="fact-row"><span class="fact-key">Beat Saber log lines</span><span class="fact-val">${escapeHtml_(String(Number(beatSaberLogs.lineCount || 0)))}</span></div>
              </div>
            </div>
          </div>

          <div id="tab-mods" class="tab-panel">
            <div class="section-title">Detected mods &nbsp;<span class="muted">${escapeHtml_(String(Number(mods.count || 0)))} detected</span></div>
            ${modList}
          </div>

          <div id="tab-bslogs" class="tab-panel">
            <div class="grid2">
              <div><div class="section-title">Beat Saber log highlights</div>${beatSaberProblems}</div>
              <div><div class="section-title">Beat Saber logs</div>
                ${beatSaberLogLines.length > 0 ? `<pre>${escapeHtml_(beatSaberLogLines.join('\n'))}</pre>` : '<p class="muted">No Beat Saber log lines were included in this upload.</p>'}
              </div>
            </div>
          </div>

          <div id="tab-applogs" class="tab-panel">
            <div class="grid2">
              <div><div class="section-title">Recent app problems</div>${problemList}</div>
              <div><div class="section-title">App log output</div>
                ${logsText.trim() ? `<pre>${escapeHtml_(logsText)}</pre>` : '<p class="muted">No app log output was included in this upload.</p>'}
              </div>
            </div>
          </div>

          <div id="tab-json" class="tab-panel">
            <div class="section-title">Raw JSON</div>
            <pre>${escapeHtml_(rawJson)}</pre>
          </div>
        </div><!-- end .tab-content -->

      </div>
    </div>
    <script>
      (function(){
        const tabs=Array.from(document.querySelectorAll('.tab'));
        const panels=Array.from(document.querySelectorAll('.tab-panel'));
        function setTab(t){
          tabs.forEach(b=>{const a=b.getAttribute('data-tab')===t;b.classList.toggle('active',a);});
          panels.forEach(p=>p.classList.toggle('active',p.id===t));
        }
        tabs.forEach(b=>b.addEventListener('click',()=>setTab(b.getAttribute('data-tab'))));
      })();
    </script>
  </body>
</html>`;
}

function renderMissingCodePage_() { return renderErrorPage_('Open this viewer with a shared debug code.'); }
function renderErrorPage_(message) {
  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
  <meta name="theme-color" content="#99d9ea"/><title>MBF Tools Debug Viewer</title>
  <style>${BASE_STYLE}.center{display:flex;align-items:center;justify-content:center;min-height:100vh;}</style>
  </head><body><script>${ANIM_BG_SCRIPT}</script><div class="center"><div class="container" style="max-width:520px;width:100%;">
    <h1 style="margin-bottom:10px;">MBF Tools Debug Viewer</h1><p style="margin:0;color:#cde;">${message}</p>
  </div></div></body></html>`;
}
