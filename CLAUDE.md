# Working in this repository

SMARTIE Quote Desk — the native Android replacement for the live PWA. The port
runs in phases (N0…N8) against a staging Firebase project, while the PWA stays
in production use.

## Start every session here

**Read `docs/PROJECT-STATUS.md` before planning anything or changing any file.**
It is the single record of what is true right now: the active branch, the last
verified head, which phases are done, and the one next action. Nothing else —
not an earlier chat, not a branch name, not a delivery document — is the
current state.

Then, before your first edit:

1. **Verify where you are.** `git rev-parse --abbrev-ref HEAD` and
   `git rev-parse --short HEAD`, and check both against
   `docs/PROJECT-STATUS.md`.
2. **Never assume an older chat or branch is current.** A session may be handed
   a designated branch that is stale, or that points at `main`. Branch names
   carry no information about recency; only `docs/PROJECT-STATUS.md` and the
   git history do.
3. **If your designated branch is not the active one**, base it on the active
   verified head first — `git fetch origin <active-branch>` then
   `git checkout -B <your-branch> origin/<active-branch>` — so the work sits on
   top of the real history. **Never force-push and never discard commits** to
   make a branch fit. If that would lose anything, stop and ask.
4. **If anything in `docs/PROJECT-STATUS.md` contradicts what you find in the
   repository, believe the repository** and say so — then fix the file.

## Standing constraints

- **Production Firebase is never written.** Staging is the only write target.
  Read production only through a viewer-only credential, and only when the task
  says to.
- **Service-account credentials are not available by default.** The temporary
  production and staging keys were revoked and their local files deleted. Never
  assume a credential exists; a session that needs one asks the Owner.
- **The approved V8C4 `index.html` is the source authority** for PWA
  behaviour, and is deliberately not in this repository. Do not guess what the
  PWA does — `tools/catalogue-import/inspect-v8c4.mjs` reads it read-only on
  the Owner's machine and prints the answers.
- Stock writing is **online-only**: a Firestore transaction that re-reads the
  stored quantity, with no offline queue and no optimistic quantity change. See
  `docs/N3-plan.md`.

## Keeping the status file honest

`docs/PROJECT-STATUS.md` is updated **only after work is verified and
committed**, never in advance of it, and it carries exactly one "Current next
action". Its own maintenance rules are at the bottom of that file.
