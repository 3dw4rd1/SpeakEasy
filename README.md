# SpeakEasy

A personal voice keyboard for Android, along the lines of Wispr Flow.
Tap the mic once, speak, pause: cleaned-up text appears wherever the cursor is.

```
phone (Android keyboard)  --audio-->  VPS proxy  --> Groq Whisper (speech to text)
                          <--text---             --> Claude Haiku (tidy up)
```

- `android/` – the keyboard app (Kotlin, no AndroidX, OkHttp only).
- `server/` – FastAPI proxy. Holds the API keys; the phone only has a shared token.
- `.github/workflows/android.yml` – builds a signed APK on every push to `main`
  and attaches it to a GitHub release.

## Install on the phone

1. Download the latest APK:
   https://github.com/3dw4rd1/SpeakEasy/releases/latest/download/SpeakEasy.apk
2. Open it and allow "install unknown apps" for your browser when asked.
3. Open SpeakEasy and do steps 1–3, paste the access token, then **Save and test**.

Updates: download the new APK and install over the top (same signing key, settings kept).

## Using it

- Tap the mic. It turns red while listening.
- Stop talking for ~2.5 s (or tap again) and the text is typed in.
- ⌨ goes back to your normal keyboard. ⌫ holds to repeat.
- Hold a letter for its accents (ā ē ī ō ū first, for te reo Māori): slide to pick, let go to type.
- Say "new paragraph", "bullet point", "full stop" etc. for formatting.
- Correct yourself out loud ("at 3, no, 4 o'clock") and it keeps the correction.

## Features

- **Styles** – Formal / Casual / Very casual per app type (Email, Work, Personal, Other),
  set in the app. The app type comes from the app you're typing in.
- **Dictionary** – words to always spell your way, plus `misheard -> Correct` rules.
  Edited in the app, stored on the server (`data/vocab.txt`).
- **Slang** – built-in Kiwi and Aussie lists (`server/kiwi_slang.txt`, `server/aussie_slang.txt`:
  chur, sweet as, arvo, servo…). The cleanup keeps your slang instead of "fixing" it (in every
  style, Formal included), and the keyboard suggests these words and won't autocorrect them.
  See "Updating the slang lists" below.
- **Snippets** – say "insert my email" and the saved text goes in exactly. Edited in the app
  (`data/snippets.txt`: trigger line, text, `---` between snippets).
- **Brain dump** – tap *Dump*, then the mic, ramble for as long as you like, tap to finish:
  you get an organised note with an Actions list (Claude Sonnet).
- **History** – every dictation on the phone. Tap to copy; edit it and *Save correction*
  to teach SpeakEasy; failed dictations keep their audio and can be retried.

## How it gets more accurate

1. Whisper large-v3 (not turbo), biased with your dictionary as a glossary.
2. The cleanup model gets your profile (`data/about.txt`), dictionary, snippets, and the
   text already in the field before the cursor.
3. Every History correction is stored (`data/examples.jsonl`) and the latest 15 are shown to
   the model as examples of what you wanted; new names/terms from it go into the dictionary.
4. `misheard -> Correct` rules are applied word-for-word after cleanup as a final net.
5. The server keeps `data/history.jsonl` (raw + cleaned) so recurring mistakes can be reviewed
   and turned into dictionary rules. Turn off with `SAVE_HISTORY=0`.

## Updating the slang lists

The lists are part of the code (unlike your personal dictionary, which is edited in the app).

1. Edit `server/kiwi_slang.txt` or `server/aussie_slang.txt` (on GitHub, the pencil icon works).
   One word per line as `word = meaning`; the meaning helps Claude spot it. `#` lines are notes.
2. Commit the change to `main`.
3. On the VPS: `sh /opt/speakeasy/server/deploy/deploy.sh` (pulls the code and restarts).
4. The phone picks up new words for suggestions within an hour. Dictation uses them straight away.

To add a whole new list, create `server/<name>_slang.txt` and add its name to `SLANG_FILES`
in `server/app.py`.

## Server

Runs as the `speakeasy` Docker container on the VPS, on the
`nginx-proxy-manager_default` network, behind Cloudflare at
`https://speakeasy.kauri-systems.com`.

- Code: `/opt/speakeasy` (git checkout). Deploy: `sh /opt/speakeasy/server/deploy/deploy.sh`
- Secrets: `/opt/speakeasy/.env` (see `server/.env.example`). Not in git.
- Personal dictionary: `/opt/speakeasy/data/vocab.txt` (one term per line) and
  `/opt/speakeasy/data/about.txt`. Read on every request, so edits apply immediately.
- Nginx: `server/deploy/nginx-speakeasy.conf`, installed as NPM's custom `http.conf`.
- Logs: `docker logs speakeasy` (timings and character counts only, never the text).

API: `POST /v1/dictate` multipart `audio`, `app`, `duration_ms`, header
`Authorization: Bearer <SPEAKEASY_TOKEN>` → `{"text", "raw", "ms"}`.
