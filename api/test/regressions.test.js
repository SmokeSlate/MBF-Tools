import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import worker, { FollowupLimiter } from '../api/handler.js';

function limiter() {
  const values = new Map();
  let queue = Promise.resolve();
  const txn = { get: async k => values.get(k), put: async (k, v) => values.set(k, v),
    delete: async k => values.delete(k), setAlarm: async () => {} };
  const obj = new FollowupLimiter({ storage: { transaction(fn) {
    const next = queue.then(() => fn(txn)); queue = next.catch(() => {}); return next;
  } } });
  return { obj, values, binding: { idFromName: x => x, get: () => obj } };
}
function env() {
  return { OPENROUTER_API_KEY: 'synthetic', FOLLOWUP_LIMITER: limiter().binding,
    LOGS: { get: async k => k.startsWith('log:') ? JSON.stringify({ payload: {} }) : null,
      put: async () => { throw Error('unexpected write'); } } };
}
const post = (body, path = '/followup/abc12') => new Request(`https://logs.mbf.tools${path}`,
  { method: 'POST', body: JSON.stringify(body) });

test('legacy redirects preserve POST and preflights terminate locally', async () => {
  const r = await worker.fetch(new Request('https://logs.sm0ke.org/?x=1', { method: 'POST', body: '{}' }), {});
  assert.equal(r.status, 308); assert.equal(r.headers.get('location'), 'https://logs.mbf.tools/?x=1');
  const preflight = await worker.fetch(new Request('https://logs.sm0ke.org/', { method: 'OPTIONS' }), {});
  assert.equal(preflight.status, 204); assert.equal(preflight.headers.get('location'), null);
});
test('upload and followup reject wrong JSON shapes', async () => {
  for (const value of [null, [], 'text', 42, true]) {
    assert.equal((await worker.fetch(post(value, '/'), env())).status, 400);
    assert.equal((await worker.fetch(post(value), env())).status, 400);
  }
});
test('history rejects unsupported roles and bounded lengths; accepted request keeps server system first', async t => {
  let sent;
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    sent = JSON.parse(options.body);
    return Response.json({ choices: [{ message: { content: 'Try restarting MBF.' } }] });
  });
  for (const history of [[{ role: 'system', content: 'inert' }], {},
    Array(11).fill({ role: 'user', content: 'x' }), [{ role: 'user', content: 'x'.repeat(4001) }]]) {
    assert.equal((await worker.fetch(post({ question: 'MBF?', history }), env())).status, 400);
  }
  assert.equal(sent, undefined);
  assert.equal((await worker.fetch(post({ question: 'x'.repeat(4001) }), env())).status, 400);
  assert.equal((await worker.fetch(post({ question: 'MBF?', history: [{ role: 'assistant', content: 'inert' }] }), env())).status, 200);
  assert.deepEqual(sent.messages.map(x => x.role), ['system', 'assistant', 'user']);
});
test('atomic limiter admits exactly ten simultaneous requests and resets at boundary', async t => {
  const { obj, values } = limiter();
  let now = 1000; t.mock.method(Date, 'now', () => now);
  const results = await Promise.all(Array.from({ length: 11 }, async () => (await obj.fetch()).json()));
  assert.equal(results.filter(r => r.allowed).length, 10);
  assert.equal(results.at(-1).retryAfter, 60);
  now = 61000;
  assert.equal((await (await obj.fetch()).json()).allowed, true);
  await obj.alarm(); assert.equal(values.has('window'), true);
  now = 121000; await obj.alarm(); assert.equal(values.size, 0);
});
test('limiter failures fail closed without AI calls', async t => {
  t.mock.method(globalThis, 'fetch', async () => { throw Error('must not call AI'); });
  const e = env(); e.FOLLOWUP_LIMITER.get = () => ({ fetch: async () => { throw Error('storage failed'); } });
  const r = await worker.fetch(post({ question: 'MBF?' }), e);
  assert.equal(r.status, 503); assert.equal((await r.json()).ok, false);
});
test('admin sorting agrees with direction for date, code and summary', () => {
  const source = readFileSync(new URL('../api/handler.js', import.meta.url), 'utf8');
  const fn = source.slice(source.indexOf('  function getFiltered()'), source.indexOf('\n  function render()'));
  for (const sortKey of ['createdAt', 'code', 'summary']) for (const sortDir of [-1, 1]) {
    const context = { ALL: [{ code: 'a', createdAt: '2020', summary: 'a' }, { code: 'z', createdAt: '2026', summary: 'z' }],
      state: { q: '', sortKey, sortDir } };
    vm.createContext(context); vm.runInContext(fn, context);
    assert.equal(context.getFiltered()[0].code, sortDir === 1 ? 'a' : 'z');
  }
});

test('invalid request bodies do not access storage or consume quota', async () => {
  const e = { OPENROUTER_API_KEY: 'synthetic', LOGS: {}, FOLLOWUP_LIMITER: {} };
  for (const body of [null, [], 1, { question: 'MBF?', history: [{ role: 'developer', content: 'inert' }] }]) {
    assert.equal((await worker.fetch(post(body), e)).status, 400);
  }
});

test('only role and content from valid history reach the upstream request', async t => {
  let sent;
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    sent = JSON.parse(options.body);
    return Response.json({ choices: [{ message: { content: 'Restart MBF.' } }] });
  });
  const history = [{ role: 'user', content: 'inert', name: 'unexpected', tool_calls: [] }];
  assert.equal((await worker.fetch(post({ question: 'MBF?', history }), env())).status, 200);
  assert.deepEqual(sent.messages[1], { role: 'user', content: 'inert' });
});

test('concurrent route requests return ten successes and one retryable 429', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    calls++;
    return Response.json({ choices: [{ message: { content: 'Restart MBF.' } }] });
  });
  const e = env();
  const responses = await Promise.all(Array.from({ length: 11 }, () => worker.fetch(post({ question: 'MBF?' }), e)));
  assert.equal(responses.filter(r => r.status === 200).length, 10);
  const limited = responses.find(r => r.status === 429);
  assert.ok(Number(limited.headers.get('retry-after')) > 0);
  assert.equal(calls, 10);
});

test('Durable Object read, write and alarm failures return controlled route responses', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => { calls++; throw Error('must not call AI'); });
  for (const failingMethod of ['get', 'put', 'setAlarm']) {
    const txn = { get: async () => null, put: async () => {}, setAlarm: async () => {} };
    txn[failingMethod] = async () => { throw Error('synthetic storage failure'); };
    const obj = new FollowupLimiter({ storage: { transaction: fn => fn(txn) } });
    const e = env(); e.FOLLOWUP_LIMITER.get = () => obj;
    const response = await worker.fetch(post({ question: 'MBF?' }), e);
    assert.equal(response.status, 503);
    assert.equal((await response.json()).ok, false);
  }
  assert.equal(calls, 0);
});
