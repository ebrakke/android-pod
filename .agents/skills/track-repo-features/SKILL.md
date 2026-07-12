---
name: track-repo-features
description: Maintain concise, current feature state in docs/HANDOFF.md. Use when planning, implementing, verifying, pausing, or handing off a repository feature; when architecture, roadmap, deployment, acceptance coverage, or known limitations change; or when preparing the repository for work in a fresh chat.
---

# Track Repository Features

Keep `docs/HANDOFF.md` as the single feature-status source of truth. Do not add a database, schema,
generated tracker, or extra planning layer unless the user asks for one.

## Workflow

1. Read `AGENTS.md`, the relevant `docs/HANDOFF.md` section, and the current diff.
2. Update only features materially affected by the work. Skip documentation churn for trivial edits.
3. Record the smallest useful set of facts:
   - current user-visible outcome and status;
   - important ownership boundaries and entry-point files;
   - exact verification performed, including device/environment and untested cases;
   - known limitations and the next one to three useful follow-ons;
   - special deploy or recovery commands only when another chat needs them.
4. Prefer present-tense capability summaries over a chronological debugging diary. Preserve a past
   failure only when it encodes a constraint that future implementation must remember.
5. Keep roadmap items close to the feature. Mark completed work and remove stale instructions rather
   than appending contradictory notes.
6. Before handoff, check that README setup instructions and `docs/HANDOFF.md` agree, then run
   `git diff --check`.

## Style

- Use plain Markdown headings and short lists.
- Use `implemented`, `partial`, `planned`, or checkboxes when status needs to be explicit.
- Name concrete files and commands; avoid speculative abstractions.
- State what was actually tested. Never turn one successful path into a claim about the full matrix.
- Keep credentials, tokens, device dumps, generated artifacts, and user library data out of docs.
