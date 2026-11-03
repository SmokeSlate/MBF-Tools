const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const html = readFileSync(join(__dirname, '..', 'index.html'), 'utf8');
const source = html.slice(html.indexOf('  function escapeHtml('), html.indexOf('  function formatDate('));
const context = vm.createContext({ URL });
vm.runInContext(source, context);
const format = value => context.formatBody(value);

test('formats safe links, headings and query ampersands exactly once', () => {
  assert.equal(format('## Notes\n[Changes](https://example.com/?a=1&b=2)'),
    '<strong>Notes</strong>\n<a href="https://example.com/?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">Changes</a>');
  assert.equal(format(''), 'No release notes.');
});

test('quoted, malformed, relative, credentialed and disallowed URLs remain inert text', () => {
  for (const url of ['https://example.com/" data-test="inert', "https://example.com/'", 'https://',
    'https://[invalid', '//example.com', '/relative', 'javascript:inert', 'data:text/plain,inert',
    'file:///tmp/inert', 'mailto:test@example.com', 'https://user:pass@example.com', 'https://example.com/\tpath']) {
    assert.ok(!format(`[link](${url})`).includes('<a '), url);
  }
});

test('escapes raw HTML and labels, including entity-looking input', () => {
  assert.equal(format('<b>&quot;</b>'), '&lt;b&gt;&amp;quot;&lt;/b&gt;');
  assert.match(format('[<b>](https://example.com)'), />&lt;b&gt;<\/a>$/);
  assert.ok(!format('[x](https://example.com/&quot;)').includes('href="https://example.com/"'));
});

test('supports multiple links without interpreting generated markup', () => {
  const result = format('[one](https://example.com/1) and [two](http://example.com/2)');
  assert.equal((result.match(/<a /g) || []).length, 2);
  assert.equal((result.match(/<\/a>/g) || []).length, 2);
});
