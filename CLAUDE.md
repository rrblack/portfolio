# CLAUDE.md

Guidance for Claude Code working in this repo. Read this BEFORE exploring — most edits do not require any exploration beyond what is documented here.

## What this repo is

A single-page static portfolio site (Kyle Porter, full-stack web developer, Tokyo). Black/red aesthetic, fully bilingual EN/JP, deployed on Cloudflare Pages. There is **no build step, no framework, no `src/`, no `package.json`.**

```
portfolio-site/
├── index.html              <-- THE WHOLE SITE: HTML + inline <style> + inline <script>
├── functions/api/contact.js  <-- Cloudflare Pages Function (POST /api/contact → Resend)
├── photos/                 <-- profile + project screenshots
├── modelsite.png           <-- one screenshot at repo root for legacy reasons
├── README.md
└── CLAUDE.md               <-- this file
```

If you find yourself thinking "where is the React component for X" or "let me find the data file" — stop. **It's all in `index.html`.**

## Anatomy of `index.html` (~1100 lines)

Everything is in one file. The line ranges below are approximate but stable:

| Range | What lives there |
|---|---|
| `<head>`, top of file | Meta, Google Fonts, favicon |
| Inline `<style>` (≈ lines 30–580) | All CSS. Search by class name (e.g. `.hero-live-item`, `.card-status`, `.modal-status`). |
| `<body>` markup (≈ lines 590–820) | Static page structure: hero, dashboard, about, projects grid container, contact, footer. **Hero "Live Projects" list is hand-rolled HTML here** (≈ lines 637–660), not data-driven. |
| Inline `<script>` (≈ lines 830–end) | `const dict = { en: {...}, jp: {...} }` then i18n + render code. |

The script is structured as **two parallel dictionaries**, EN (`en:{...}` ≈ line 840) and JP (`jp:{...}` ≈ line 940). Both contain the same shape: all UI copy keys, plus arrays for `projects`, `certs`, `skills`, `repos`.

### Critical rule: EN and JP must be edited together

Any data change — new project, new tag, new status, new copy string — **must land in both dictionaries** or the JP toggle will drift. Every grep for an EN string should be matched by a grep for its JP equivalent.

## The `projects` array

Each project object has this shape (see line ≈ 891 for the live example):

```js
{
  name, img, url, github, desc,
  tags: ['Stack','Production','Client Work'],
  status: true,                            // drives the green "Live in production" badge
  detail: {
    tagline, role, year, deploy,
    statusLabel: 'Live',                   // shown in the modal meta cell
    stack: [...], backstory, highlights: [...]
  }
}
```

- `status: true` → renders `<div class="card-status">Live in production</div>` on the card AND the modal. Gated by `p.status ? ... : ''` at lines ≈ 1058 and 1091. Omit the field (or set falsy) to hide.
- `statusLabel` is a separate localized string shown in the modal's meta grid ("Live" / "稼働中"). Keep it consistent with `status`.
- `liveStatusBadge` (top of each dictionary, ≈ line 881 EN / 989 JP) is the *text* of the green badge: `'Live in production'` / `'本番稼働中'`.

## Recipe: flip a project to "live in production"

Touch four places, two per language:

1. **EN project entry**: add `status:true`, set `statusLabel:'Live'`, swap a placeholder tag for `'Production'`.
2. **JP project entry** (matching index in the JP `projects` array): add `status:true`, set `statusLabel:'稼働中'`, swap the JP tag to `'本番環境'`.
3. **Hero "Live Projects" list** (hand-rolled HTML, ≈ lines 637–660): add a new `<div class="hero-live-item">` with a `data-i18n="heroLiveSubN"` attribute on the subtitle line.
4. **Both dictionaries**: add the new `heroLiveSubN` key with EN and JP copy.

The order of projects in the `en.projects` array MUST match `jp.projects` (the renderer indexes by position).

## Recipe: add a new project

Add a new object at the same index in both `en.projects` and `jp.projects`. Add an image to `photos/` (or repo root, matching existing convention). Optionally add a GitHub repo entry to both `en.repos` and `jp.repos`. If it's live, also do the "hero live list" step above.

## Recipe: change UI copy

The string lives in BOTH dictionaries under the same key, referenced from markup via `data-i18n="keyName"`. To find it: grep for the `data-i18n` key, then edit it in `en:{...}` AND `jp:{...}`.

## The contact form

- `functions/api/contact.js` — Cloudflare Pages Function. POSTs to Resend with `from: 'Portfolio Contact <contact@kyleporter.dev>'`, recipient `rrblack701@gmail.com`.
- The sender domain `kyleporter.dev` is verified in Resend (SPF + DKIM). Do not change the `from` to an unverified address.
- Production `RESEND_API_KEY` is set in the Cloudflare Pages dashboard → Settings → Environment variables. Local dev reads from `.dev.vars`.
- The form `fetch`s `/api/contact` from inline JS in `index.html` — grep for `/api/contact` to find the client side.

## Running locally

- **Static-only preview** (everything works except the contact form): open `index.html` in a browser.
- **Full preview** (contact form works): `npx wrangler pages dev .` — Wrangler serves the static page and the Pages Function together, reading `.dev.vars` for `RESEND_API_KEY`.

There is no `npm install`, no test suite, no linter, no typecheck. Verification = open the page and click around.

## Deployment

Cloudflare Pages, auto-deployed from `main`. The repo also contains `.gitignore` excluding `.env`, `.dev.vars`, `node_modules`. Push to `main` → it ships. There is no staging.

## Conventions to keep

- **Single-file architecture is a feature, not debt.** Don't propose splitting `index.html` into a framework, adding a bundler, or extracting components. The "no build step" is part of the project's identity.
- **Bilingual symmetry.** Every change in `en:` must have a JP counterpart. If you're not sure how to translate, ask — don't skip it.
- **Match the existing style.** Compact object literals on single lines (see lines 891–900). Inline CSS classes. Vanilla JS, no dependencies.
- **Status flag pattern.** `status:true` for live projects; omit for in-progress. Don't introduce a new enum or a separate "stage" field.
- **Don't add comments to `index.html`.** It's already comment-free; preserve that.

## Things to NOT do

- Don't `npm init` or add a `package.json`. This is intentionally dependency-free.
- Don't add a framework, bundler, or transpiler.
- Don't split `index.html` into multiple files.
- Don't add `<!-- TODO -->` or `// removed`-style comments.
- Don't update only one language dictionary.
- Don't change the Resend `from` address away from a verified `kyleporter.dev` sender.
- Don't commit `.env`, `.dev.vars`, or any file with `RESEND_API_KEY` in it.

## When you DO need to explore

The only things NOT documented here that might require a fresh look:
- New CSS class names (grep the inline `<style>` block).
- Specific copy you don't recognize (grep the dictionary).
- Recent git history (`git log --oneline -20`) for context on in-flight changes.

Everything else should be derivable from this file.
