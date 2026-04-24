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
- **Contact form** — real POST → Vercel serverless → Resend email delivery
- **Availability card** — green-dot status, Tokyo location, response window, language list
- **Reveal-on-scroll animations** and parallax red orbs in the hero
- **Responsive** down to 900px (mobile drops the hero dashboard)

## 🛠 Stack

| Layer | Tech |
|---|---|
| Markup / Styling | Plain HTML + CSS (single file, no build step) |
| Scripting | Vanilla JS (no framework runtime) |
| Fonts | Space Grotesk, Space Mono, Noto Sans JP, Kaushan Script |
| Serverless | Vercel Functions (`/api/contact`) |
| Email | Resend |
| Hosting | Vercel |

## 🚀 Local development

No build step required.

```bash
# open in browser directly
open index.html     # macOS
start index.html    # Windows
```

For the contact form to work locally, use the Vercel CLI:

```bash
vercel dev
```

Requires a `.env` file with:

```
RESEND_API_KEY=your_key_here
```

`.env` is gitignored — never commit secrets.

## 📁 Structure

```
portfolio/
├── index.html            Main page — all CSS/JS inline
├── api/
│   └── contact.js        Vercel serverless POST /api/contact (Resend)
├── photos/               Profile + project screenshots
│   ├── biopicture.jpeg
│   ├── moapro.png
│   ├── blogen.png
│   └── blogjp.png
├── modelsite.png         Model portfolio project screenshot
├── vercel.json           Vercel routing config
├── .gitignore            Excludes .env, node_modules, .vercel
└── README.md
```

## 🌐 Projects showcased

- **Model Portfolio Site** — Ruby on Rails / Hotwire / PostgreSQL — `github.com/rrblack/model-project-ruby`
- **Moapro** — Next.js 16 / TypeScript / Tailwind / Cloudflare Pages — `github.com/rrblack/client_project`
- **Kyle's Japan Life** — Next.js 14 / Supabase / MDX / DeepL — `github.com/rrblack/blog-starter-app`

## 📄 License

All rights reserved. Repository is public for portfolio display only — please don't copy, reuse, or redistribute without permission.
