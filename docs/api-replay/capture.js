/**
 * capture.js — Voice2SMS API-REPLAY-PLAN.md §6 ground-truth HAR capture.
 *
 * Records two HARs of a Google Voice SMS send — one via Pixel 7 mobile
 * emulation, one via default desktop Chromium — so we can reverse-engineer
 * the send RPC (batchexecute / clients6.google.com).
 *
 * Usage (from a creds-broker agent that has already called use_session):
 *   SESSION_PATH=/tmp/creds-xxxx.json node docs/api-replay/capture.js
 *
 * Optional env:
 *   RECIPIENT=+15551234567   # override auto-discovered linked number
 *   HEADLESS=0               # run headful for debugging
 *
 * Safety: never logs cookies, SAPISID, or full phone numbers. Writes HARs
 * with content:'embed' to the sibling directory; the parent agent handles
 * sanitization in a later step.
 */

const { chromium, devices } = require('playwright');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const SESSION_PATH = process.env.SESSION_PATH;
const EXPLICIT_RECIPIENT = process.env.RECIPIENT || null;
const HEADLESS = process.env.HEADLESS !== '0';

const OUT_DIR = __dirname;
const MOBILE_HAR = path.join(OUT_DIR, 'send-mobile.har');
const DESKTOP_HAR = path.join(OUT_DIR, 'send-desktop.har');

const GV_MESSAGES_URL = 'https://voice.google.com/u/0/messages';
const GV_SETTINGS_URL = 'https://voice.google.com/u/0/settings';

// --- Small helpers ---

function redactNumber(n) {
  if (!n) return '<none>';
  const digits = String(n).replace(/\D/g, '');
  return digits.length >= 4 ? `...${digits.slice(-4)}` : '<short>';
}

function log(...args) { console.log('[capture]', ...args); }

async function safeClick(page, selector, timeout = 5000) {
  const el = await page.waitForSelector(selector, { timeout }).catch(() => null);
  if (!el) return false;
  await el.click().catch(() => {});
  return true;
}

// --- Discovery: find the target recipient from GV settings ---

async function discoverRecipient(page) {
  if (EXPLICIT_RECIPIENT) {
    log('Using explicit RECIPIENT from env:', redactNumber(EXPLICIT_RECIPIENT));
    return EXPLICIT_RECIPIENT;
  }

  log('Navigating to settings to discover linked number...');
  await page.goto(GV_SETTINGS_URL, { waitUntil: 'domcontentloaded', timeout: 30000 });
  // Give the SPA a few seconds to render.
  await page.waitForTimeout(4000);

  // Strategy 1: any phone-number-ish text under a "Linked" or "Forwarding" heading.
  // GV renders these inside sections; fall back to scanning page body.
  const candidates = await page.evaluate(() => {
    const phoneRe = /\+1\s?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}/g;
    const matches = [];
    const bodyText = document.body ? document.body.innerText : '';
    const found = bodyText.match(phoneRe) || [];
    for (const m of found) matches.push(m.replace(/\D/g, ''));
    return matches;
  });

  // Normalize + dedupe. First is most likely "linked/forwarding" per DOM order;
  // GV number (own) usually appears at the top in "Account".
  const unique = [...new Set(candidates.map(d => d.startsWith('1') ? `+${d}` : `+1${d}`))];
  if (unique.length === 0) {
    return null;
  }
  log(`Found ${unique.length} phone-like strings on settings page. Using first.`);
  return unique[0];
}

// --- The send flow (matches MEMORY.md verified mobile flow) ---

async function performSend(page, phone, body) {
  log('Navigating to messages...');
  await page.goto(GV_MESSAGES_URL, { waitUntil: 'domcontentloaded', timeout: 30000 });

  // Wait for SPA ready.
  await page.waitForSelector('gv-side-nav, text=Send new message', { timeout: 20000 });
  log('SPA ready.');

  // Click the "new message" FAB.
  const fabClicked = await safeClick(page, 'button[aria-label*="new" i]', 10000)
    || await safeClick(page, '[aria-label="Send new message"]', 5000);
  if (!fabClicked) {
    log('WARN: could not locate new-message FAB.');
  }

  // Type phone number directly into recipient input.
  const recipient = await page.waitForSelector(
    'input[placeholder="Type a name or phone number"]',
    { timeout: 10000 }
  ).catch(() => null);
  if (!recipient) throw new Error('recipient input not found');
  await recipient.click();
  await recipient.type(phone, { delay: 50 });

  // Click the CDK overlay "send-to" suggestion.
  const suggestion = await page.waitForSelector(
    '.cdk-overlay-container .send-to-button',
    { timeout: 10000 }
  ).catch(() => null);
  if (suggestion) {
    await suggestion.click().catch(() => {});
  } else {
    // Fall back: press Enter to commit as a chip.
    await page.keyboard.press('Enter');
  }

  // Dismiss lingering CDK backdrop.
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(300);

  // Fill message body.
  const textarea = await page.waitForSelector(
    'textarea[placeholder="Type a message"]',
    { timeout: 10000 }
  );
  await textarea.click();
  await textarea.type(body, { delay: 30 });

  // Wait for Send button to become enabled, then click.
  const sendBtn = await page.waitForSelector(
    'button[aria-label="Send message"]:not([disabled])',
    { timeout: 10000 }
  ).catch(() => null);
  if (!sendBtn) {
    log('WARN: Send button never enabled — attempting click anyway for HAR coverage.');
    await safeClick(page, 'button[aria-label*="Send" i]', 2000);
  } else {
    await sendBtn.click();
  }

  // Wait for confirmation OR timeout (proceed regardless — request likely fired).
  try {
    await page.waitForSelector('text=/sent/i', { timeout: 15000 });
    log('Send confirmation observed.');
  } catch {
    log('WARN: no "sent" confirmation within 15s — continuing, request likely captured anyway.');
  }
}

// --- Per-capture runner ---

async function runCapture({ label, harPath, contextOpts, body }) {
  log(`=== Capture: ${label} -> ${path.basename(harPath)} ===`);
  const browser = await chromium.launch({
    headless: HEADLESS,
    args: ['--no-sandbox'],
  });
  const context = await browser.newContext({
    ...contextOpts,
    storageState: SESSION_PATH,
    recordHar: { path: harPath, content: 'embed' },
  });
  const page = await context.newPage();

  let recipient;
  try {
    recipient = await discoverRecipient(page);
    if (!recipient) {
      log('ERROR: no recipient discovered — aborting this capture.');
      await context.close();
      await browser.close();
      return { ok: false, recipient: null };
    }
    await performSend(page, recipient, body);
  } catch (err) {
    log(`ERROR during ${label} send:`, err.message);
  } finally {
    // Closing context flushes HAR to disk.
    await context.close();
    await browser.close();
  }
  return { ok: true, recipient };
}

// --- Main ---

(async () => {
  if (!SESSION_PATH) {
    console.error('[capture] SESSION_PATH env var required (path to storage_state JSON).');
    process.exit(2);
  }
  if (!fs.existsSync(SESSION_PATH)) {
    console.error(`[capture] SESSION_PATH does not exist: ${SESSION_PATH}`);
    process.exit(2);
  }

  const runId = crypto.randomUUID();
  const ts = Date.now();
  const mobileBody = `v2s-har-mobile-${ts}-${runId.slice(0, 8)}`;
  const desktopBody = `v2s-har-desktop-${ts}-${runId.slice(0, 8)}`;
  log('Run ID:', runId);
  log('Mobile body marker:', mobileBody);
  log('Desktop body marker:', desktopBody);

  // Capture 1 — Pixel 7 emulation.
  const pixel7 = devices['Pixel 7'] || {
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 2.625,
    isMobile: true,
    hasTouch: true,
    userAgent:
      'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 ' +
      '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
  };
  const mobileResult = await runCapture({
    label: 'mobile-pixel7',
    harPath: MOBILE_HAR,
    contextOpts: { ...pixel7 },
    body: mobileBody,
  });

  // Capture 2 — Desktop Chromium (default UA, default viewport).
  const desktopResult = await runCapture({
    label: 'desktop-chromium',
    harPath: DESKTOP_HAR,
    contextOpts: {
      viewport: { width: 1280, height: 800 },
    },
    body: desktopBody,
  });

  // --- Post-capture summary ---

  function summarizeHar(harPath) {
    if (!fs.existsSync(harPath)) return { size: 0, batchexecute: 0, clients6: 0 };
    const size = fs.statSync(harPath).size;
    let batchexecute = 0, clients6 = 0;
    try {
      const har = JSON.parse(fs.readFileSync(harPath, 'utf8'));
      const entries = har.log && har.log.entries ? har.log.entries : [];
      for (const e of entries) {
        const url = e.request && e.request.url ? e.request.url : '';
        const method = e.request && e.request.method ? e.request.method : '';
        if (url.includes('batchexecute')) batchexecute++;
        if (method === 'POST' && url.includes('clients6.google.com')) clients6++;
      }
    } catch (err) {
      log('WARN: could not parse', harPath, '-', err.message);
    }
    return { size, batchexecute, clients6 };
  }

  const mobileStats = summarizeHar(MOBILE_HAR);
  const desktopStats = summarizeHar(DESKTOP_HAR);

  console.log('\n====== CAPTURE SUMMARY ======');
  console.log('Mobile recipient:  ', redactNumber(mobileResult.recipient));
  console.log('Desktop recipient: ', redactNumber(desktopResult.recipient));
  console.log('Mobile body:       ', mobileBody);
  console.log('Desktop body:      ', desktopBody);
  console.log('Mobile HAR:        ', MOBILE_HAR);
  console.log('  size:            ', mobileStats.size, 'bytes');
  console.log('  batchexecute:    ', mobileStats.batchexecute);
  console.log('  clients6 POSTs:  ', mobileStats.clients6);
  console.log('Desktop HAR:       ', DESKTOP_HAR);
  console.log('  size:            ', desktopStats.size, 'bytes');
  console.log('  batchexecute:    ', desktopStats.batchexecute);
  console.log('  clients6 POSTs:  ', desktopStats.clients6);
  console.log('==============================\n');
})().catch((err) => {
  console.error('[capture] FATAL:', err);
  process.exit(1);
});
