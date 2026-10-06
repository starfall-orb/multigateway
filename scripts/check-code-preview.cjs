const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const {execFileSync} = require('child_process');
const assets = path.resolve(__dirname, '../app/src/main/assets/code-preview');
const documentSource = fs.readFileSync(path.resolve(__dirname, '../app/src/main/java/org/starfall/multigateway/ui/preview/CodePreviewDocument.kt'), 'utf8');
const libraryUrls = [...documentSource.matchAll(/"(https:\/\/unpkg\.com\/[^"]+)"/g)].map(match => match[1]);
assert.equal(libraryUrls.length, 3);
// Fetch the exact CDN versions in memory for browser checks; no vendor files live in app assets.
const libraries = new Map(libraryUrls.map(url => [url, execFileSync('curl', ['--fail', '--location', '--silent', '--show-error', '--max-time', '45', url], {maxBuffer: 10 * 1024 * 1024})]));
const scriptTags = libraryUrls.map(url => `<script src="${url}"></script>`).join('');
(async () => {
 const browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE, args:['--no-sandbox']});
 const tests = [
  ['named React component and hooks', "import React, {useState} from 'react'; export default function App(){ const [count,setCount]=useState(0); return <button onClick={()=>setCount(count+1)}>Count: {count}</button> }",'jsx',async page=>{await page.getByText('Count: 0').click();await page.getByText('Count: 1').waitFor();}],
  ['anonymous default JSX', "export default () => <p>Anonymous component</p>",'jsx',async page=>{await page.getByText('Anonymous component').waitFor();}],
  ['inferred component', "function Greeting(){return <h1>Hello React</h1>}",'jsx',async page=>{await page.getByText('Hello React').waitFor();}],
  ['bare JSX expression', "<section>Standalone JSX</section>",'jsx',async page=>{await page.getByText('Standalone JSX').waitFor();}],
  ['JS DOM output', "document.getElementById('root').innerHTML='<b>JavaScript DOM</b>'",'javascript',async page=>{await page.getByText('JavaScript DOM').waitFor();}],
  ['JS console', "console.log('Output:', 42)",'javascript',async page=>{await page.getByText('Output: 42').waitFor();}],
  ['TSX types', "type Props={name:string}; export default function App(){const p:Props={name:'Typed component'};return <p>{p.name}</p>}",'tsx',async page=>{await page.getByText('Typed component').waitFor();}],
  ['explicit createRoot', "import React from 'react'; import {createRoot} from 'react-dom/client'; createRoot(document.getElementById('root')).render(<p>Explicit root</p>)",'jsx',async page=>{await page.getByText('Explicit root').waitFor();}],
  ['UTF8 and closing script', "export default () => <p>{'Tiếng Việt 中文 </script>'}</p>",'jsx',async page=>{await page.getByText('Tiếng Việt 中文 </script>').waitFor();}],
  ['unsupported package error', "import thing from 'nonexistent-package'; export default () => <p>{thing}</p>",'jsx',async page=>{await page.locator('#preview-error').waitFor();assert((await page.locator('#preview-error').innerText()).includes('Unsupported import'));}],
  ['syntax error', "export default () => <p>",'jsx',async page=>{await page.locator('#preview-error').waitFor();}],
  ['render error', "export default function App(){throw new Error('Broken component')}",'jsx',async page=>{await page.locator('#preview-error').waitFor();assert((await page.locator('#preview-error').innerText()).includes('Broken component'));}]
 ];
 try {
  for(const [name,source,language,check] of tests){
   const page=await browser.newPage();
   await page.route('https://preview.multigateway.invalid/runtime/**',route=>route.fulfill({contentType:'application/javascript',body:fs.readFileSync(path.join(assets,route.request().url().split('/').pop()))}));
   await page.route('https://unpkg.com/**', route => route.fulfill({contentType:'application/javascript', body:libraries.get(route.request().url())}));
   const encoded=Buffer.from(source).toString('base64');
   await page.setContent(`<html><head><script src="https://preview.multigateway.invalid/runtime/runtime.js"></script>${scriptTags}</head><body><div id="root"></div><pre id="preview-console"></pre><script>runCodePreview("${encoded}","${language}")</script></body></html>`);
   await check(page); console.log('PASS',name); await page.close();
  }
  const offline = await browser.newPage();
  await offline.route('https://preview.multigateway.invalid/runtime/runtime.js', route => route.fulfill({contentType:'application/javascript', body:fs.readFileSync(path.join(assets, 'runtime.js'))}));
  await offline.route('https://unpkg.com/**', route => route.abort());
  await offline.setContent(`<html><head><script src="https://preview.multigateway.invalid/runtime/runtime.js"></script>${scriptTags}</head><body><div id="root"></div><pre id="preview-console"></pre><script>runCodePreview("${Buffer.from('export default () => <p>Hello</p>').toString('base64')}","jsx")</script></body></html>`);
  await offline.locator('#preview-error').waitFor();
  assert((await offline.locator('#preview-error').innerText()).includes('internet connection'));
  console.log('PASS CDN loading failure');
  await offline.close();
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
