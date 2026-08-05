import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import test from 'node:test';
import worker from '../api/handler.js';

function testEnvironment(password) {
  return {
    ADMIN_PASSWORD_HASH: createHash('sha256').update(password).digest('hex'),
    ADMIN_SESSION_SECRET: randomBytes(32).toString('hex'),
    LOGS: {
      async list() {
        return { keys: [], list_complete: true };
      },
      async delete() {},
    },
  };
}

function loginRequest(password) {
  return new Request('https://logs.mbf.tools/admin/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ password }),
  });
}

test('admin login issues a signed session without exposing the password hash', async () => {
  const password = 'release-test-password';
  const env = testEnvironment(password);
  const loginResponse = await worker.fetch(loginRequest(password), env);

  assert.equal(loginResponse.status, 302);
  const setCookie = loginResponse.headers.get('set-cookie') || '';
  assert.match(setCookie, /^mbf_admin=/);
  assert.ok(!setCookie.includes(env.ADMIN_PASSWORD_HASH));

  const sessionCookie = setCookie.split(';', 1)[0];
  const adminResponse = await worker.fetch(
    new Request('https://logs.mbf.tools/admin', { headers: { Cookie: sessionCookie } }),
    env,
  );
  assert.equal(adminResponse.status, 200);
  assert.match(await adminResponse.text(), /MBF Tools Admin/);
});

test('admin rejects tampered sessions', async () => {
  const password = 'release-test-password';
  const env = testEnvironment(password);
  const loginResponse = await worker.fetch(loginRequest(password), env);
  const sessionCookie = (loginResponse.headers.get('set-cookie') || '').split(';', 1)[0];
  const tamperedCookie = `${sessionCookie.slice(0, -1)}${sessionCookie.endsWith('a') ? 'b' : 'a'}`;

  const response = await worker.fetch(
    new Request('https://logs.mbf.tools/admin', { headers: { Cookie: tamperedCookie } }),
    env,
  );
  assert.doesNotMatch(await response.text(), /Shared debug logs/);
});

test('admin login fails closed when secrets are missing', async () => {
  const response = await worker.fetch(loginRequest('anything'), {
    LOGS: testEnvironment('anything').LOGS,
  });
  assert.equal(response.status, 503);
});
