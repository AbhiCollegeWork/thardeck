#!/usr/bin/env node
/* ============================================================================
 * CarDeck — documentation build pipeline
 *
 * Markdown + Mermaid  ->  controlled, versioned PDF
 *
 *   - Mermaid diagrams are rendered to inline SVG (vector, not raster) by a
 *     headless Chromium, then typeset with the surrounding prose.
 *   - Revision history for each document is derived from git history of the
 *     source file. No hand-maintained revision tables to drift out of date.
 *   - Cross-document Markdown links are resolved to document IDs, so a
 *     printed page can still be navigated.
 *   - Page numbers in each table of contents come from a pagination
 *     simulation that honours break-inside:avoid, not naive division.
 *
 * Usage:
 *   node build-docs.mjs                 build everything
 *   node build-docs.mjs --only 03       build one document
 *   node build-docs.mjs --no-manual     skip the combined manual
 * ========================================================================= */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';
import MarkdownIt from 'markdown-it';
import anchor from 'markdown-it-anchor';
import puppeteer from 'puppeteer-core';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const DOCS = path.join(ROOT, 'docs');
const DIST = path.join(ROOT, 'dist');
const TMP = path.join(__dirname, '.tmp');

/* A4 printable box in CSS pixels: (210-17-17)mm x (297-20-18)mm at 96 dpi. */
const PRINTABLE_W = Math.round(176 * 96 / 25.4);   // 665
const PRINTABLE_H = Math.round(259 * 96 / 25.4);   // 979

const argv = process.argv.slice(2);
const ONLY = argv.includes('--only') ? argv[argv.indexOf('--only') + 1] : null;
const NO_MANUAL = argv.includes('--no-manual');

/* ---------------------------------------------------------------- helpers -- */

const log = (...a) => console.log(...a);
const ok = (m) => log(`  \x1b[32m✓\x1b[0m ${m}`);
const step = (m) => log(`\n\x1b[36m▸\x1b[0m ${m}`);
const warn = (m) => log(`  \x1b[33m!\x1b[0m ${m}`);

function git(cmd, fallback = null) {
  try {
    return execSync(`git ${cmd}`, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return fallback;
  }
}

function findBrowser() {
  const candidates = [
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ].filter(Boolean);
  for (const c of candidates) if (fs.existsSync(c)) return c;
  throw new Error('No Chrome or Edge installation found. Set CHROME_PATH.');
}

/* ------------------------------------------------------- version control -- */

const VERSION = fs.readFileSync(path.join(ROOT, 'VERSION'), 'utf8').trim();
const MANIFEST = JSON.parse(fs.readFileSync(path.join(DOCS, 'manifest.json'), 'utf8'));

const BUILD = {
  version: VERSION,
  commit: git('rev-parse --short HEAD', 'uncommitted'),
  commitFull: git('rev-parse HEAD', ''),
  branch: git('rev-parse --abbrev-ref HEAD', 'main'),
  dirty: git('status --porcelain', '') !== '',
  date: new Date().toISOString().slice(0, 10),
};

/**
 * Revision history for one source file, derived from git.
 * Revisions are numbered from the file's first commit forward, so a document
 * carries its own revision count independent of the repository's history.
 */
function revisionHistory(relFile) {
  const raw = git(`log --follow --date=short --format=%h%x1f%ad%x1f%s%x1f%an -- "${relFile}"`, '');
  if (!raw) {
    return [{
      rev: 'A', date: BUILD.date, hash: BUILD.commit,
      subject: 'Initial issue', author: '—',
    }];
  }
  const commits = raw.split('\n').filter(Boolean).map((l) => {
    const [hash, date, subject, author] = l.split('\x1f');
    return { hash, date, subject, author };
  }).reverse(); // oldest first

  // Revision letters A, B, C ... then AA, AB for very long histories.
  const letter = (i) => {
    let s = '';
    i += 1;
    while (i > 0) { const r = (i - 1) % 26; s = String.fromCharCode(65 + r) + s; i = Math.floor((i - 1) / 26); }
    return s;
  };
  return commits.map((c, i) => ({ ...c, rev: letter(i) }));
}

/* ------------------------------------------------------- markdown -> html -- */

const md = new MarkdownIt({ html: true, linkify: true, typographer: true, breaks: false })
  .use(anchor, { slugify: (s) => slug(s), permalink: false });

function slug(s) {
  return s.toLowerCase().trim()
    .replace(/[^\w\s-]/g, '')
    .replace(/\s+/g, '-');
}

/** Map a source filename to its document ID, for cross-reference resolution. */
const docIdByFile = new Map();
for (const d of MANIFEST.documents) {
  docIdByFile.set(path.basename(d.file), d.docId);
}

let figureCounter = 0;
let currentDocNum = '';

/* Fence: capture mermaid blocks for later browser-side rendering. */
const defaultFence = md.renderer.rules.fence.bind(md.renderer.rules);
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const info = (token.info || '').trim();

  if (info === 'mermaid') {
    figureCounter += 1;
    const id = `fig-${currentDocNum}-${figureCounter}`;
    const caption = `Figure ${currentDocNum}.${figureCounter}`;
    const enc = encodeURIComponent(token.content);
    return `<figure class="figure" data-fig="${id}">`
      + `<div class="mermaid" id="m-${id}" data-src="${enc}"></div>`
      + `<figcaption>${caption}</figcaption>`
      + `</figure>\n`;
  }

  // Plain fences with no language that look like ASCII diagrams get a neutral frame
  if (!info && /[│├└─┤┬┼▁█┼┤╢]|^\s*[+|].*[+|]\s*$/m.test(token.content)) {
    return `<pre class="plain"><code>${md.utils.escapeHtml(token.content)}</code></pre>\n`;
  }
  return defaultFence(tokens, idx, options, env, self);
};

/* Links: resolve cross-document references to document IDs. */
const defaultLinkOpen = md.renderer.rules.link_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options, env));

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const href = tokens[idx].attrGet('href') || '';
  if (/\.md($|#)/.test(href) && !/^https?:/.test(href)) {
    const base = path.basename(href.split('#')[0]);
    const target = docIdByFile.get(base) || (base === 'README.md' ? 'CD-000' : null);
    tokens[idx].attrSet('href', '#');
    tokens[idx].attrSet('class', 'xref');
    if (target) tokens[idx].attrSet('data-doc', target);
  } else if (/^https?:/.test(href)) {
    tokens[idx].attrSet('class', 'exlink');
  }
  return defaultLinkOpen(tokens, idx, options, env, self);
};

const defaultLinkClose = md.renderer.rules.link_close
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options, env));

md.renderer.rules.link_close = (tokens, idx, options, env, self) => {
  const out = defaultLinkClose(tokens, idx, options, env, self);
  // Find the matching open token to recover the document ID
  for (let i = idx; i >= 0; i--) {
    if (tokens[i].type === 'link_open') {
      const doc = tokens[i].attrGet('data-doc');
      return doc ? `${out}<span class="xref-id">${doc}</span>` : out;
    }
  }
  return out;
};

/** Classify blockquotes into callout variants from their leading marker. */
function classifyCallouts(html) {
  return html.replace(/<blockquote>([\s\S]*?)<\/blockquote>/g, (m, inner) => {
    const probe = inner.slice(0, 400);
    let cls = 'info';
    if (/⚠️|\bWARNING\b|\bCAUTION\b|Do not|DOES NOT WORK|never/i.test(probe)) cls = 'warn';
    if (/Dead end|DANGEROUS|🚨|must never/i.test(probe)) cls = 'danger';
    if (/✓|Verified|SOLVED|Measured result/i.test(probe)) cls = 'good';
    return `<blockquote class="${cls}">${inner}</blockquote>`;
  });
}

/** Strip the markdown navigation footer — meaningless in a bound document. */
function stripNav(src) {
  return src.replace(/\n---\s*\n\s*\*\*(Next|Back to):\*\*.*$/s, '\n');
}

/* --------------------------------------------------------------- page HTML -- */

const CSS = fs.readFileSync(path.join(__dirname, 'styles', 'print.css'), 'utf8');
const MERMAID_JS = fs.readFileSync(
  path.join(__dirname, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js'), 'utf8');

const MERMAID_CONFIG = {
  startOnLoad: false,
  theme: 'base',
  securityLevel: 'loose',
  fontFamily: '"Segoe UI", -apple-system, Helvetica, Arial, sans-serif',
  themeVariables: {
    background: '#ffffff',
    primaryColor: '#eef4fa',
    primaryTextColor: '#12181f',
    primaryBorderColor: '#0b5394',
    lineColor: '#5b6570',
    secondaryColor: '#f1f8f5',
    tertiaryColor: '#f7f9fb',
    fontSize: '13px',
    nodeBorder: '#0b5394',
    clusterBkg: '#fbfcfe',
    clusterBorder: '#dde3ea',
    edgeLabelBackground: '#ffffff',
    // sequence
    actorBkg: '#eef4fa',
    actorBorder: '#0b5394',
    actorTextColor: '#12181f',
    signalColor: '#3d4854',
    signalTextColor: '#12181f',
    noteBkgColor: '#fdf8ef',
    noteBorderColor: '#9a5b0e',
    noteTextColor: '#3d4854',
    labelBoxBkgColor: '#eef4fa',
    labelBoxBorderColor: '#0b5394',
    // state
    labelColor: '#12181f',
    transitionColor: '#5b6570',
    stateBkg: '#eef4fa',
    stateBorder: '#0b5394',
  },
  flowchart: { curve: 'basis', htmlLabels: true, padding: 14, nodeSpacing: 44, rankSpacing: 52, useMaxWidth: true },
  sequence: { actorMargin: 44, boxMargin: 10, mirrorActors: false, useMaxWidth: true, wrap: true, width: 148 },
  state: { useMaxWidth: true, padding: 14 },
};

function coverHtml(doc, revs) {
  const latest = revs[revs.length - 1];
  const shown = revs.slice(-6);
  const statusClass = doc.status.toLowerCase() === 'released' ? 'released' : 'draft';
  return `
<section class="cover">
  <div class="cover-rule"></div>
  <div class="cover-project">${MANIFEST.project} — ${MANIFEST.subtitle}</div>
  <div class="cover-set">${MANIFEST.documentSet}</div>

  <div class="cover-docid">${doc.docId}</div>
  <h1 class="cover-title">${doc.title}</h1>
  <div class="cover-subtitle">${doc.subtitle}</div>

  ${doc.note ? `<div class="cover-note"><strong>Note.</strong> ${doc.note}</div>` : ''}

  <div class="control">
    <div class="control-grid">
      <div class="control-item"><div class="k">Document</div><div class="v mono">${doc.docId}</div></div>
      <div class="control-item"><div class="k">Version</div><div class="v mono">${VERSION}</div></div>
      <div class="control-item"><div class="k">Revision</div><div class="v mono">${latest.rev}</div></div>
      <div class="control-item"><div class="k">Status</div><div class="v"><span class="badge ${statusClass}">${doc.status}</span></div></div>
      <div class="control-item"><div class="k">Issued</div><div class="v mono">${BUILD.date}</div></div>
      <div class="control-item"><div class="k">Source commit</div><div class="v mono">${BUILD.commit}${BUILD.dirty ? '+' : ''}</div></div>
      <div class="control-item"><div class="k">Audience</div><div class="v">${doc.audience}</div></div>
      <div class="control-item"><div class="k">Classification</div><div class="v">${MANIFEST.classification}</div></div>
    </div>
  </div>

  <div class="revtable-label">Revision history${revs.length > shown.length ? ` — most recent ${shown.length} of ${revs.length}` : ''}</div>
  <table class="revtable">
    <thead><tr><th>Rev</th><th>Date</th><th>Change</th><th>Commit</th></tr></thead>
    <tbody>
      ${shown.map((r) => `<tr>
        <td class="rev">${r.rev}</td>
        <td class="date">${r.date}</td>
        <td>${escapeHtml(r.subject).slice(0, 96)}</td>
        <td class="hash">${r.hash}</td>
      </tr>`).join('')}
    </tbody>
  </table>

  <div class="cover-foot">
    <span>${MANIFEST.project} · ${MANIFEST.license} licensed · ${MANIFEST.classification}</span>
    <span>${BUILD.branch}@${BUILD.commit}</span>
  </div>
</section>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function pageHtml({ title, bodyHtml, includeToc }) {
  return `<!doctype html>
<html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title>
<style>${CSS}</style>
</head><body>
${bodyHtml}
<script>${MERMAID_JS}</script>
<script>
  window.__mermaidConfig = ${JSON.stringify(MERMAID_CONFIG)};
  window.__includeToc = ${includeToc ? 'true' : 'false'};
  /* Runs during parse, before DOMContentLoaded, so mermaid's own
     startOnLoad pass never fires with the default theme. */
  window.mermaid.initialize(window.__mermaidConfig);
</script>
</body></html>`;
}

/* --------------------------------------------------- browser-side routine -- */
/* Runs inside the page: render diagrams, then simulate pagination to build a
 * table of contents with page numbers that respect break-inside:avoid.       */

async function renderInPage(page) {
  return page.evaluate(async () => {
    const MM = 96 / 25.4;
    const PAGE_H = 259 * MM;   // A4 height minus top+bottom margins

    /* Print palette.
     * The Markdown `style` directives use saturated dark fills chosen to read
     * well on a dark web page. On white paper they are heavy and fight the
     * document palette, so each is remapped to a light tint of the same
     * semantic colour, and the white label text paired with it becomes ink.
     *
     * This is done on the SVG markup as a string, before it enters the DOM.
     * Rewriting afterwards is unreliable: the browser normalises inline styles
     * and the original !important declarations win back during printing.
     */
    const TINT = {
      '#1e3a5f': ['#dceaf7', '#0b5394'],   // structural / primary
      '#3a3a5a': ['#e4e8f5', '#2f3f6b'],
      '#2a2a3a': ['#eceef4', '#3d4854'],
      '#1e5f3a': ['#dcf0e5', '#0e6f66'],   // verified / good
      '#1e4a2e': ['#dcf0e5', '#0e6f66'],
      '#3a5a3a': ['#e2f1e6', '#2f6b3f'],
      '#5f3a1e': ['#f6e7d7', '#8a5320'],   // hardware / car
      '#5f4a1e': ['#faedd4', '#9a5b0e'],   // caution
      '#7a4020': ['#f8e3d4', '#9a5b0e'],
      '#7a2020': ['#fbdede', '#9c2c2c'],   // fault
      '#5a2020': ['#fbdede', '#9c2c2c'],
    };
    const lc = (c) => (c || '').trim().toLowerCase();
    let recoloured = 0;
    const recolour = (markup) => markup
      .replace(/(fill\s*[:=]\s*"?)(#[0-9a-fA-F]{6})/g, (m, p, c) => {
        const t = TINT[lc(c)]; if (!t) return m; recoloured++; return p + t[0];
      })
      .replace(/(stroke\s*[:=]\s*"?)(#[0-9a-fA-F]{6})/g, (m, p, c) => {
        const t = TINT[lc(c)]; if (!t) return m; recoloured++; return p + t[1];
      })
      // lookbehind so `background-color` is not caught — edge labels keep
      // their white backing plate
      .replace(/(?<![-\w])(color\s*[:=]\s*"?)(#fff(?:fff)?|white|rgb\(\s*255\s*,\s*255\s*,\s*255\s*\))/gi,
               (m, p) => { recoloured++; return p + '#12181f'; });

    // 1 — render every mermaid block to inline SVG
    window.mermaid.initialize(window.__mermaidConfig);
    const blocks = [...document.querySelectorAll('.mermaid')];
    let rendered = 0, failed = [];
    for (const el of blocks) {
      const src = decodeURIComponent(el.dataset.src || '');
      try {
        const { svg } = await window.mermaid.render(el.id + '-svg', src);
        el.outerHTML = recolour(svg);
        rendered++;
      } catch (e) {
        el.outerHTML = `<pre class="plain"><code>${src.replace(/</g, '&lt;')}</code></pre>`;
        failed.push(String(e && e.message || e).slice(0, 120));
      }
    }

    // constrain diagram width so nothing overflows the text block
    document.querySelectorAll('.figure svg').forEach((svg) => {
      svg.removeAttribute('height');
      svg.style.maxWidth = '100%';
      svg.style.height = 'auto';
      const vb = svg.getAttribute('viewBox');
      if (vb) {
        const [, , w, h] = vb.split(/[\s,]+/).map(Number);
        // keep tall diagrams inside one page
        const maxH = PAGE_H * 0.86;
        if (h > maxH) svg.style.width = (w * (maxH / h)) + 'px';
      }
    });

    await new Promise((r) => setTimeout(r, 120));

    // 2 — simulate pagination over top-level blocks
    const content = document.querySelector('.content');
    const startPage = (document.querySelector('.cover') ? 1 : 0)
                    + (window.__includeToc ? 1 : 0) + 1;
    const pageOf = new Map();
    if (content) {
      let cursor = 0;
      let pageNo = startPage;
      let prevMb = 0;               // for CSS adjacent-margin collapsing
      const avoid = (el) => el.matches('table, pre, figure, blockquote')
                         || getComputedStyle(el).breakInside === 'avoid';

      for (const el of content.children) {
        const cs = getComputedStyle(el);
        if (cs.display === 'none') continue;

        const mt = parseFloat(cs.marginTop) || 0;
        const mb = parseFloat(cs.marginBottom) || 0;
        const h = el.getBoundingClientRect().height;
        // adjacent vertical margins collapse to the larger of the two
        const gap = Math.max(mt, prevMb);

        if (el.classList.contains('docbreak') || cs.breakBefore === 'page') {
          if (cursor > 0) { pageNo++; cursor = 0; }
        } else {
          cursor += gap;            // a collapsed margin is dropped at a page break
        }

        // an unbreakable block that will not fit is moved whole to the next page
        if (avoid(el) && h <= PAGE_H && cursor + h > PAGE_H) { pageNo++; cursor = 0; }

        if (/^H[1-6]$/.test(el.tagName)) {
          // break-after:avoid — a heading is never left stranded at the foot
          const next = el.nextElementSibling;
          const nextH = next ? Math.min(next.getBoundingClientRect().height, 90) : 0;
          if (cursor + h + nextH > PAGE_H) { pageNo++; cursor = 0; }
          pageOf.set(el.id || el.textContent, pageNo);
          el.dataset.page = String(pageNo);
        }

        cursor += h;
        prevMb = mb;
        while (cursor > PAGE_H) { cursor -= PAGE_H; pageNo++; }
      }
    }

    // 3 — build the table of contents
    if (window.__includeToc && content) {
      const items = [...content.querySelectorAll('h2, h3')]
        // a heading inside a callout is part of that callout, not a section
        .filter((h) => !h.closest('blockquote'))
        .filter((h) => !/^(Contents|Quick index)$/i.test(h.textContent.trim()));
      const ol = document.createElement('ol');
      for (const h of items) {
        const li = document.createElement('li');
        li.className = 'lvl-' + h.tagName[1];
        li.innerHTML = `<a href="#${h.id}"><span class="t">${h.textContent}</span>`
                     + `<span class="d">${'.'.repeat(90)}</span>`
                     + `<span class="p" data-key="${h.textContent.trim()}">${h.dataset.page || ''}</span></a>`;
        ol.appendChild(li);
      }
      const toc = document.querySelector('.toc');
      if (toc) toc.appendChild(ol);
    }

    return { rendered, failed, blocks: blocks.length, recoloured };
  });
}

/* --------------------------------------------------- pass 2: true paging -- */
/*
 * The simulated pagination above is a good estimate, but only the print engine
 * knows where a heading really lands. So the document is printed once, the
 * resulting PDF is read back to find the actual page of every heading, those
 * numbers are written into the table of contents, and it is printed again.
 * Filling the numbers cannot reflow the page (the leader-dot run absorbs the
 * width change), so the second print is exact.
 */
async function headingPagesFromPdf(pdfPath) {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const data = new Uint8Array(fs.readFileSync(pdfPath));
  const doc = await pdfjs.getDocument({ data, useSystemFonts: true }).promise;
  const map = new Map();

  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const text = await page.getTextContent();

    // group text items into visual lines, carrying the largest glyph height
    const lines = new Map();
    for (const it of text.items) {
      if (!it.str) continue;
      const y = Math.round(it.transform[5]);
      const size = Math.abs(it.transform[0]) || it.height || 0;
      const cur = lines.get(y) || { parts: [], size: 0 };
      cur.parts.push([it.transform[4], it.str]);
      cur.size = Math.max(cur.size, size);
      lines.set(y, cur);
    }
    for (const { parts, size } of lines.values()) {
      if (size <= 11.0) continue;                 // body text is 10.2pt
      const s = parts.sort((a, b) => a[0] - b[0]).map((p) => p[1]).join('').trim();
      if (s && !map.has(s)) map.set(s, n);
    }
    page.cleanup();
  }
  await doc.destroy();
  return Object.fromEntries(map);
}

/* ------------------------------------------------------------------ build -- */

function headerTemplate(doc) {
  return `<div style="width:100%;font-family:'Segoe UI',sans-serif;font-size:7pt;color:#929ba6;
    padding:0 17mm;display:flex;justify-content:space-between;border-bottom:0.5pt solid #eef2f6;
    padding-bottom:2mm;margin-bottom:3mm;">
    <span style="letter-spacing:.1em;text-transform:uppercase;">${escapeHtml(MANIFEST.project)} · ${escapeHtml(MANIFEST.documentSet)}</span>
    <span style="letter-spacing:.06em;">${escapeHtml(doc.docId)} — ${escapeHtml(doc.title)}</span>
  </div>`;
}

function footerTemplate(doc) {
  return `<div style="width:100%;font-family:'Segoe UI',sans-serif;font-size:7pt;color:#929ba6;
    padding:0 17mm;display:flex;justify-content:space-between;border-top:0.5pt solid #eef2f6;
    padding-top:2mm;">
    <span>${escapeHtml(doc.docId)} · v${VERSION} · ${BUILD.commit}${BUILD.dirty ? '+' : ''}</span>
    <span>Page <span class="pageNumber"></span> of <span class="totalPages"></span></span>
  </div>`;
}

async function buildDoc(browser, doc) {
  const srcPath = path.resolve(DOCS, doc.file);
  const relForGit = path.relative(ROOT, srcPath).replace(/\\/g, '/');
  const revs = revisionHistory(relForGit);

  currentDocNum = doc.docId.replace(/^CD-0*/, '') || '0';
  figureCounter = 0;

  let src = stripNav(fs.readFileSync(srcPath, 'utf8'));
  // The H1 is replaced by the cover page; drop it and any leading nav table.
  src = src.replace(/^#\s+.*\n/, '');

  let html = md.render(src);
  html = classifyCallouts(html);

  const body = coverHtml(doc, revs)
    + `<section class="toc"><h2>Contents</h2></section>`
    + `<main class="content">${html}</main>`;

  const tmpFile = path.join(TMP, `${doc.docId}.html`);
  fs.writeFileSync(tmpFile, pageHtml({ title: `${doc.docId} ${doc.title}`, bodyHtml: body, includeToc: true }));

  const page = await browser.newPage();
  await page.goto('file:///' + tmpFile.replace(/\\/g, '/'), { waitUntil: 'load', timeout: 60000 });
  const result = await renderInPage(page);

  const outName = `${MANIFEST.project}-${doc.docId}-${doc.title.replace(/\s+/g, '-')}-v${VERSION}.pdf`;
  const outPath = path.join(DIST, outName);
  const pdfOpts = {
    format: 'A4',
    printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: headerTemplate(doc),
    footerTemplate: footerTemplate(doc),
    margin: { top: '20mm', bottom: '18mm', left: '17mm', right: '17mm' },
    preferCSSPageSize: false,
    outline: true,
    timeout: 120000,
  };

  // pass 1 — print with estimated page numbers
  const probePath = path.join(TMP, `${doc.docId}.probe.pdf`);
  await page.pdf({ ...pdfOpts, path: probePath });

  // read back the true page of every heading, then correct the contents page
  const truePages = await headingPagesFromPdf(probePath);
  const corrected = await page.evaluate((map) => {
    let fixed = 0, missed = 0;
    for (const el of document.querySelectorAll('.toc .p[data-key]')) {
      const p = map[el.dataset.key];
      if (p) { if (el.textContent !== String(p)) fixed++; el.textContent = String(p); }
      else { missed++; }
    }
    return { fixed, missed };
  }, truePages);

  // pass 2 — print again, now exact
  await page.pdf({ ...pdfOpts, path: outPath });
  fs.unlinkSync(probePath);
  await page.close();

  const kb = Math.round(fs.statSync(outPath).size / 1024);
  const tocNote = corrected.missed ? `  (${corrected.missed} toc entries unresolved)` : '';
  ok(`${doc.docId}  ${doc.title.padEnd(26)} rev ${revs[revs.length - 1].rev.padEnd(3)} ${String(result.rendered).padStart(2)} figures  ${String(kb).padStart(4)} KB  toc+${corrected.fixed}  recol ${result.recoloured}${tocNote}`);
  if (result.failed.length) result.failed.forEach((f) => warn(`diagram failed: ${f}`));

  return { doc, revs, outName, kb, figures: result.rendered };
}

async function buildManual(browser, docs) {
  let body = `
<section class="cover">
  <div class="cover-rule"></div>
  <div class="cover-project">${MANIFEST.project} — ${MANIFEST.subtitle}</div>
  <div class="cover-set">Complete ${MANIFEST.documentSet}</div>
  <div class="cover-docid">CD-MANUAL</div>
  <h1 class="cover-title">Technical Manual</h1>
  <div class="cover-subtitle">All controlled documents, bound as one volume. Build, operate, diagnose and extend a tablet-based wireless Android Auto head unit.</div>
  <div class="control">
    <div class="control-grid">
      <div class="control-item"><div class="k">Volume</div><div class="v mono">CD-MANUAL</div></div>
      <div class="control-item"><div class="k">Version</div><div class="v mono">${VERSION}</div></div>
      <div class="control-item"><div class="k">Documents</div><div class="v mono">${docs.length}</div></div>
      <div class="control-item"><div class="k">Issued</div><div class="v mono">${BUILD.date}</div></div>
      <div class="control-item"><div class="k">Source commit</div><div class="v mono">${BUILD.commit}${BUILD.dirty ? '+' : ''}</div></div>
      <div class="control-item"><div class="k">Branch</div><div class="v mono">${BUILD.branch}</div></div>
      <div class="control-item"><div class="k">Licence</div><div class="v">${MANIFEST.license}</div></div>
      <div class="control-item"><div class="k">Classification</div><div class="v">${MANIFEST.classification}</div></div>
    </div>
  </div>
  <div class="revtable-label">Documents in this volume</div>
  <table class="revtable">
    <thead><tr><th>ID</th><th>Title</th><th>Status</th><th>Audience</th></tr></thead>
    <tbody>${docs.map((d) => `<tr><td class="rev">${d.docId}</td><td>${d.title}</td><td>${d.status}</td><td>${d.audience}</td></tr>`).join('')}</tbody>
  </table>
  <div class="cover-foot"><span>${MANIFEST.project} · ${MANIFEST.license} licensed</span><span>${BUILD.branch}@${BUILD.commit}</span></div>
</section>
<section class="toc"><h2>Contents</h2></section>
<main class="content">`;

  for (const doc of docs) {
    const srcPath = path.resolve(DOCS, doc.file);
    currentDocNum = doc.docId.replace(/^CD-0*/, '') || '0';
    figureCounter = 0;
    let src = stripNav(fs.readFileSync(srcPath, 'utf8')).replace(/^#\s+.*\n/, '');
    body += `<section class="part-divider">
        <div class="pd-rule"></div>
        <div class="pd-id">${doc.docId}</div>
        <div class="pd-title">${doc.title}</div>
        <div class="pd-sub">${doc.subtitle}</div>
      </section>`;
    body += `<h2 class="docbreak" id="doc-${doc.docId}">${doc.title}</h2>`;
    body += classifyCallouts(md.render(src));
  }
  body += `</main>`;

  const tmpFile = path.join(TMP, 'CD-MANUAL.html');
  fs.writeFileSync(tmpFile, pageHtml({ title: 'CarDeck Technical Manual', bodyHtml: body, includeToc: true }));

  const page = await browser.newPage();
  await page.goto('file:///' + tmpFile.replace(/\\/g, '/'), { waitUntil: 'load', timeout: 120000 });
  const result = await renderInPage(page);

  const doc = { docId: 'CD-MANUAL', title: 'Technical Manual' };
  const outName = `${MANIFEST.project}-Technical-Manual-v${VERSION}.pdf`;
  const outPath = path.join(DIST, outName);
  const pdfOpts = {
    format: 'A4',
    printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: headerTemplate(doc),
    footerTemplate: footerTemplate(doc),
    margin: { top: '20mm', bottom: '18mm', left: '17mm', right: '17mm' },
    outline: true,
    timeout: 180000,
  };
  const probePath = path.join(TMP, 'CD-MANUAL.probe.pdf');
  await page.pdf({ ...pdfOpts, path: probePath });
  const truePages = await headingPagesFromPdf(probePath);
  await page.evaluate((map) => {
    for (const el of document.querySelectorAll('.toc .p[data-key]')) {
      const p = map[el.dataset.key];
      if (p) el.textContent = String(p);
    }
  }, truePages);
  await page.pdf({ ...pdfOpts, path: outPath });
  fs.unlinkSync(probePath);
  await page.close();

  const kb = Math.round(fs.statSync(outPath).size / 1024);
  ok(`CD-MANUAL  Technical Manual (all ${docs.length})   ${String(result.rendered).padStart(2)} figures  ${String(kb).padStart(4)} KB`);
  return { outName, kb, figures: result.rendered };
}

/* ------------------------------------------------------------------- main -- */

(async () => {
  log(`\n\x1b[1m${MANIFEST.project} documentation build\x1b[0m`);
  log(`  version ${VERSION} · ${BUILD.branch}@${BUILD.commit}${BUILD.dirty ? ' (uncommitted changes)' : ''} · ${BUILD.date}`);

  fs.mkdirSync(DIST, { recursive: true });
  fs.mkdirSync(TMP, { recursive: true });

  const browserPath = findBrowser();
  log(`  renderer ${path.basename(browserPath)}`);

  let docs = MANIFEST.documents;
  if (ONLY) docs = docs.filter((d) => d.docId.includes(ONLY) || d.file.includes(ONLY));
  if (!docs.length) { console.error('No documents matched --only ' + ONLY); process.exit(1); }

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: 'new',
    args: ['--allow-file-access-from-files', '--font-render-hinting=none', '--disable-lcd-text'],
  });

  step('Rendering documents');
  const built = [];
  for (const doc of docs) built.push(await buildDoc(browser, doc));

  let manual = null;
  if (!NO_MANUAL && !ONLY) {
    step('Binding combined manual');
    manual = await buildManual(browser, MANIFEST.documents);
  }

  await browser.close();

  // Build record — machine-readable provenance for every artefact issued.
  const record = {
    project: MANIFEST.project,
    version: VERSION,
    builtAt: new Date().toISOString(),
    git: { commit: BUILD.commitFull, short: BUILD.commit, branch: BUILD.branch, clean: !BUILD.dirty },
    renderer: path.basename(browserPath),
    artefacts: [
      ...built.map((b) => ({
        docId: b.doc.docId, title: b.doc.title, status: b.doc.status,
        revision: b.revs[b.revs.length - 1].rev, revisions: b.revs.length,
        figures: b.figures, file: b.outName, sizeKB: b.kb,
      })),
      ...(manual ? [{ docId: 'CD-MANUAL', title: 'Technical Manual', file: manual.outName, sizeKB: manual.kb, figures: manual.figures }] : []),
    ],
  };
  fs.writeFileSync(path.join(DIST, 'build-record.json'), JSON.stringify(record, null, 2));

  const totalKb = record.artefacts.reduce((s, a) => s + a.sizeKB, 0);
  const totalFigs = built.reduce((s, b) => s + b.figures, 0);
  step('Done');
  log(`  ${record.artefacts.length} PDFs · ${totalFigs} vector diagrams · ${(totalKb / 1024).toFixed(1)} MB`);
  log(`  output   dist/`);
  log(`  record   dist/build-record.json`);
  if (BUILD.dirty) warn('Built from a dirty working tree — commit before issuing.');
  log('');
})().catch((e) => { console.error('\nBuild failed:', e); process.exit(1); });
