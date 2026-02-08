# Voice2SMS — Project Instructions

## Current Task: Fix Autocomplete Dropdown (inject.js)

The primary task is getting inject.js to trigger the Google Voice Angular Material autocomplete dropdown using **pure JS** (no isTrusted events). See `RALPH-PROMPT.md` for full context.

### Workflow: Playwright MCP First, Android Second

1. **Phase 1: Playwright MCP Browser Testing** — Use the Playwright MCP tools (browser_navigate, browser_snapshot, browser_evaluate, browser_click, etc.) to test inject.js in a real browser.
   - Test **desktop view** first (at least 10 iterations of trying approaches)
   - Test **mobile view** second (at least 10 iterations)
   - Use a Task agent with `subagent_type: "general-purpose"` to run the Playwright MCP back-and-forth with GV web
   - Goal: find a JS-only approach that creates a recipient chip and enables the send button

2. **Phase 2: Android WebView** — Only after Phase 1 succeeds.
   - **Prompt the user before switching to Android testing** — do NOT start ADB/build/install without asking first
   - Port the working JS approach to inject.js and test on the real device

### Credentials

- **Prompt the user for a fresh cookie string** if you need to authenticate with Google Voice in the Playwright MCP browser
- Cookies go on `.google.com` domain, `__Secure-*` cookies need `sameSite: 'None'`
- Reference: `test-pw-local.js` has the cookie parsing logic and last-known cookies

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/assets/inject.js` | Main WebView injection script |
| `app/src/main/java/com/voice2sms/GVoiceWebViewActivity.java` | WebView activity |
| `app/src/main/assets/fingerprint-mask.js` | Anti-detection |
| `test-pw-local.js` | Reference Playwright script with cookie auth |
| `RALPH-PROMPT.md` | Ralph Loop task description |
| `DEBUG-SESSION-2026-02-08.md` | Debug session log |
| `PLAN.md` | Architecture plan & debug log |
| `MEMORY.md` (in memory dir) | Persistent memory across sessions |

### Reference Docs

- See `MEMORY.md` for verified selectors, mobile vs desktop differences, and all prior findings
- See `DEBUG-SESSION-2026-02-08.md` for the 7 failed approaches and queued research
