const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync(`${__dirname}/../docs/beta/index.html`, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
function release(tag, extra = {}) {
  return {tag_name:tag, prerelease:true, draft:false, assets:[{name:'MBF-release.apk',state:'uploaded',size:1000,browser_download_url:`https://github.com/SmokeSlate/MBF-Tools/releases/download/${tag}/MBF-release.apk`}], ...extra};
}
async function render(pages, fail=false) {
  const elements = Object.fromEntries(['status','download','notes'].map(id=>[id,{hidden:id==='download'}]));
  let calls=0;
  const context = {document:{getElementById:id=>elements[id]},fetch:async()=>{if(fail)throw Error('offline');return {ok:true,json:async()=>pages[calls++]||[]};}};
  vm.runInNewContext(script,context);
  await new Promise(resolve=>setImmediate(resolve));
  return {elements,calls};
}
test('picks highest numeric beta and excludes drafts, stable, debug and missing assets', async()=>{
  const {elements} = await render([[release('3.6.4-beta.9'),release('3.6.4-beta.10'),release('3.6.5-beta.1',{draft:true}),release('3.7.0',{prerelease:false}),release('3.8.0-beta.1',{assets:[]}),release('3.9.0-beta.1',{assets:[{name:'debug.apk'}]}),release('4.0.0-beta.1',{assets:[{...release('4.0.0-beta.1').assets[0],name:'app-release-unsigned.apk'}]})]]);
  assert.match(elements.download.href,/3\.6\.4-beta\.10/);
  assert.equal(elements.download.hidden,false);
});
test('keeps download hidden when no beta is ready',async()=>{
  const {elements} = await render([[release('3.6.3',{prerelease:false})]]);
  assert.equal(elements.download.hidden,true);
  assert.match(elements.status.textContent,/No signed beta/);
});
test('API errors leave fallback releases link and no fabricated download',async()=>{
  const {elements} = await render([],true);
  assert.equal(elements.download.hidden,true);
  assert.match(elements.status.textContent,/Could not check/);
});
test('paginates instead of losing beta behind newer stable releases',async()=>{
  const {elements,calls} = await render([Array.from({length:100},()=>release('3.6.3',{prerelease:false})),[release('3.6.4-beta.1')]]);
  assert.equal(calls,2);
  assert.match(elements.download.href,/3\.6\.4-beta\.1/);
});
