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
- Say "new paragraph", "bullet point", "full stop" etc. for formatting.
- Correct yourself out loud ("at 3, no, 4 o'clock") and it keeps the correction.

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
