#!/usr/bin/env node
/* ============================================================================
 * export-diagrams - render every Mermaid diagram in docs/ as a standalone
 * image, for use outside the documents (social posts, slides, video frames).
 *
 * Produces, per diagram:
 *   assets/diagrams/<doc>-<n>.svg   vector, for print or further editing
 *   assets/diagrams/<doc>-<n>.png   2x raster on white, for social platforms
 *
 * Uses the same palette and the same label-colour override as the PDF build,
 * so exported images match the documents exactly.
 *
 *   node export-diagrams.mjs
 * ========================================================================= */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const DOCS = path.join(ROOT, 'docs');
const OUT = path.join(ROOT, 'assets', 'diagrams');
const TMP = path.join(__dirname, '.tmp');

function findBrowser() {
  const c = [
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium',
  ].filter(Boolean);
  for (const x of c) if (fs.existsSync(x)) return x;
  throw new Error('No Chrome or Edge found. Set CHROME_PATH.');
}

// collect every mermaid fence, tagged with its source document
const sources = [];
const files = ['../README.md', ...fs.readdirSync(DOCS).filter((f) => /^\d\d-.*\.md$/.test(f))];
for (const f of files) {
  const p = path.resolve(DOCS, f);
  if (!fs.existsSync(p)) continue;
  const name = path.basename(f, '.md').replace('README', '00-overview');
  const text = fs.readFileSync(p, 'utf8');
  let n = 0;
  for (const m of text.matchAll(/```mermaid\n([\s\S]*?)```/g)) {
    n += 1;
    // the nearest preceding heading becomes the caption
    const before = text.slice(0, m.index);
    const h = [...before.matchAll(/^#{2,3} (.+)$/gm)].pop();
    sources.push({ id: `${name}-${n}`, src: m[1].trim(), title: h ? h[1].trim() : name });
  }
}

const MERMAID_JS = fs.readFileSync(path.join(__dirname, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js'), 'utf8');
const INK = '#1e293b';
const CONFIG = {
  startOnLoad: false, theme: 'base', securityLevel: 'loose',
  fontFamily: '"Helvetica Neue", Helvetica, Arial, sans-serif',
  themeVariables: {
    background: '#ffffff', primaryColor: '#e7ecf5', primaryTextColor: INK,
    primaryBorderColor: '#0f2863', secondaryColor: '#f4f7f9', tertiaryColor: '#ffffff',
    lineColor: '#505050', textColor: INK, fontSize: '15px', nodeBorder: '#0f2863',
    nodeTextColor: INK, clusterBkg: '#ffffff', clusterBorder: '#d8dee5',
    edgeLabelBackground: '#ffffff', actorBkg: '#e7ecf5', actorBorder: '#0f2863',
    actorTextColor: INK, signalColor: '#505050', signalTextColor: INK,
    noteBkgColor: '#fdf7ef', noteBorderColor: '#b45309', noteTextColor: '#505050',
    labelBoxBkgColor: '#e7ecf5', labelBoxBorderColor: '#0f2863', labelColor: INK,
    transitionColor: '#505050', stateBkg: '#e7ecf5', stateBorder: '#0f2863',
  },
  flowchart: { curve: 'basis', htmlLabels: true, padding: 14, useMaxWidth: false },
  sequence: { useMaxWidth: false, wrap: true, width: 150 },
  state: { useMaxWidth: false, padding: 14 },
};

const TINT = {
  '#1e3a5f': ['#e7ecf5', '#0f2863'], '#3a3a5a': ['#eaecf4', '#3b4a72'],
  '#2a2a3a': ['#eef0f3', '#4a5261'], '#1e5f3a': ['#e8f3ec', '#15803d'],
  '#1e4a2e': ['#e8f3ec', '#15803d'], '#3a5a3a': ['#eaf2ec', '#2f6b3f'],
  '#5f3a1e': ['#fbf1e3', '#b45309'], '#5f4a1e': ['#fbf1e3', '#b45309'],
  '#7a4020': ['#fbeee3', '#b45309'], '#7a2020': ['#fcf2f2', '#b91c1c'],
  '#5a2020': ['#fcf2f2', '#b91c1c'],
};

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(TMP, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: findBrowser(), headless: 'new',
    args: ['--allow-file-access-from-files', '--force-device-scale-factor=2'],
  });

  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
    body{margin:0;background:#fff;font-family:"Helvetica Neue",Helvetica,Arial,sans-serif}
    #stage{display:inline-block;padding:28px;background:#fff}
  </style></head><body><div id="stage"></div>
  <script>${MERMAID_JS}</script>
  <script>window.__cfg=${JSON.stringify(CONFIG)};window.__tint=${JSON.stringify(TINT)};
    window.mermaid.initialize(window.__cfg);</script></body></html>`;
  const f = path.join(TMP, 'export.html');
  fs.writeFileSync(f, html);

  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1200, deviceScaleFactor: 2 });
  await page.goto('file:///' + f.replace(/\\/g, '/'), { waitUntil: 'load' });

  let done = 0, failed = [];
  for (const d of sources) {
    const svg = await page.evaluate(async (src, id) => {
      const stage = document.getElementById('stage');
      try {
        const { svg } = await window.mermaid.render('x' + id.replace(/[^a-z0-9]/gi, ''), src);
        const T = window.__tint;
        const lc = (c) => (c || '').trim().toLowerCase();
        const fixed = svg
          .replace(/(fill\s*[:=]\s*"?)(#[0-9a-fA-F]{6})/g, (m, p, c) => T[lc(c)] ? p + T[lc(c)][0] : m)
          .replace(/(stroke\s*[:=]\s*"?)(#[0-9a-fA-F]{6})/g, (m, p, c) => T[lc(c)] ? p + T[lc(c)][1] : m)
          .replace(/(?<![-\w])(color\s*[:=]\s*"?)(#fff(?:fff)?|white|rgb\(\s*255\s*,\s*255\s*,\s*255\s*\))/gi, (m, p) => p + '#1e293b');
        stage.innerHTML = fixed;
        const el = stage.querySelector('svg');
        el.style.maxWidth = 'none';
        return el.outerHTML;
      } catch (e) {
        stage.innerHTML = '';
        return null;
      }
    }, d.src, d.id);

    if (!svg) { failed.push(d.id); continue; }
    fs.writeFileSync(path.join(OUT, `${d.id}.svg`), svg);
    const stage = await page.$('#stage');
    await stage.screenshot({ path: path.join(OUT, `${d.id}.png`), omitBackground: false });
    const kb = Math.round(fs.statSync(path.join(OUT, `${d.id}.png`)).size / 1024);
    console.log(`  ok  ${d.id.padEnd(22)} ${String(kb).padStart(4)} KB   ${d.title.slice(0, 44)}`);
    done += 1;
  }

  await browser.close();
  fs.writeFileSync(path.join(OUT, 'index.json'), JSON.stringify(
    sources.map((s) => ({ id: s.id, title: s.title, png: `${s.id}.png`, svg: `${s.id}.svg` })), null, 2));
  console.log(`\n${done} diagrams exported to assets/diagrams/`);
  if (failed.length) console.log(`failed: ${failed.join(', ')}`);
})().catch((e) => { console.error(e); process.exit(1); });
