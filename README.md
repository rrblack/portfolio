# portfolio

Personal portfolio site — **Kyle Porter**, full-stack web developer based in Tokyo.

Black/red aesthetic, signature-style hero nameplate with red glow, fully bilingual (EN/JP), with a live GitHub contribution heatmap, dedicated project detail modals, and a working contact form.

---

## ✨ Features

- **Bilingual EN/JP toggle** — every string, including project backstories, translates
- **Signature-style hero** — cursive nameplate with layered red glow
- **Hero dashboard** — current stack, AWS + JLPT N1 credentials, live projects at a glance
- **Live GitHub heatmap** — pulls real contribution data from `github-contributions-api.jogruber.de`, with a seeded fallback if the API is blocked
- **Project detail modals** — click any project card for a full overview: role, year, deploy target, stack, backstory, highlights
- **Contact form** — real POST → Cloudflare Pages Function → Resend, sent from a verified `kyleporter.dev` sender
- **Availability card** — green-dot status, Tokyo location, response window, language list
- **Reveal-on-scroll animations** and parallax red orbs in the hero
- **Responsive** down to 900px (mobile drops the hero dashboard)

## 🛠 Stack

| Layer | Tech |
|---|---|
| Markup / Styling | Plain HTML + CSS (single file, no build step) |
| Scripting | Vanilla JS (no framework runtime) |
| Fonts | Space Grotesk, Space Mono, Noto Sans JP, Kaushan Script |
| Serverless | Cloudflare Pages Functions (`/api/contact`) |
| Email | Resend (verified `kyleporter.dev` sender, SPF + DKIM) |
| Hosting | Cloudflare Pages |

## 🚀 Local development

No build step required.

```bash
# open the static page directly
start index.html    # Windows
open index.html     # macOS
```

For the contact form to work locally, use Wrangler so the Pages Function is served alongside the static files:

```bash
npx wrangler pages dev .
```

Wrangler reads local secrets from `.dev.vars`:

```
RESEND_API_KEY=your_key_here
```

`.dev.vars` and `.env` are gitignored — never commit secrets. The production `RESEND_API_KEY` lives in the Cloudflare Pages dashboard under **Settings → Environment variables**.

## 📁 Structure

```
portfolio/
├── index.html                Main page — all CSS/JS inline
├── functions/
│   └── api/
│       └── contact.js        Cloudflare Pages Function: POST /api/contact (Resend)
├── photos/                   Profile + project screenshots
│   ├── biopicture.jpeg
│   ├── moapro.png
│   ├── blogen.png
│   └── blogjp.png
├── modelsite.png             Model portfolio project screenshot
├── .gitignore                Excludes .env, .dev.vars, node_modules
└── README.md
```

## 🌐 Projects showcased

- **Model Portfolio Site** — Ruby on Rails / Hotwire / PostgreSQL — `github.com/rrblack/model-project-ruby`
- **Moapro** — Next.js 16 / TypeScript / Tailwind / Cloudflare Pages — `github.com/rrblack/client_project`
- **Kyle's Japan Life** — Next.js 14 / Supabase / MDX / DeepL — `github.com/rrblack/blog-starter-app`

## 📄 License

All rights reserved. Repository is public for portfolio display only — please don't copy, reuse, or redistribute without permission.
