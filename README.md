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
- Typing shows three words above the keys: what you typed, then the likely word. The bold one
  is what space will put in. Backspace straight after a correction puts your spelling back,
  and SpeakEasy won't correct that word again.
- Hold a letter for its accents (ā ē ī ō ū first, for te reo Māori): slide to pick, let go to type.
- Say "new paragraph", "bullet point", "full stop" etc. for formatting.
- Correct yourself out loud ("at 3, no, 4 o'clock") and it keeps the correction.

## Features

- **Styles** – Formal / Casual / Very casual per app type (Email, Work, Personal, Other),
  set in the app. The app type comes from the app you're typing in.
- **Dictionary** – words to always spell your way, plus `misheard -> Correct` rules.
  Edited in the app, stored on the server (`data/vocab.txt`).
- **Kiwi slang** – a built-in list (`server/kiwi_slang.txt`: chur, sweet as, togs, whānau…)
  so the cleanup keeps your slang instead of "fixing" it. Edit the file and redeploy to change it.
- **Suggestions and autocorrect** – a built-in list of 60,000 words in NZ spelling
  (`android/app/src/main/assets/words.txt`), plus your Dictionary and the Kiwi slang list. It
  finishes words, fixes typos, and adds capitals and apostrophes (monday → Monday, dont → don't).
  It doesn't depend on the phone's own spell checker.
- **Snippets** – say "insert my email" and the saved text goes in exactly. Edited in the app
  (`data/snippets.txt`: trigger line, text, `---` between snippets).
- **Brain dump** – tap *Dump*, then the mic, ramble for as long as you like, tap to finish:
  you get an organised note with an Actions list (Claude Sonnet).
- **History** – every dictation on the phone. Tap to copy; edit it and *Save correction*
  to teach SpeakEasy; failed dictations keep their audio and can be retried.
- **Private fields** – in incognito tabs (or any field an app marks as private) dictation still
  works, but nothing is saved: no phone History, no server log, no recent emojis.

## How it gets more accurate

1. Whisper large-v3 (not turbo) hears every recording twice at the same time: once primed with
   your dictionary, slang and the text before the cursor, and once with no priming. The primed
   pass spells your words right; the plain pass can't be talked into hearing them when they
   weren't said. If the two disagree, the cleanup model sees both and picks what makes sense.
   If one fails, the other is used, and if Groq's main model is down the turbo model steps in.
2. The cleanup model gets your profile (`data/about.txt`), dictionary, snippets, and the
   text already in the field before the cursor.
3. Every History correction is stored (`data/examples.jsonl`) and the latest 15 are shown to
   the model as examples of what you wanted; new names/terms from it go into the dictionary.
4. `misheard -> Correct` rules are applied word-for-word after cleanup as a final net.
5. The server keeps `data/history.jsonl` (raw + cleaned, the second transcript when it
   differed, and Whisper's confidence) so recurring mistakes can be reviewed and turned into
   dictionary rules. Turn off with `SAVE_HISTORY=0`.
6. The cleanup model's answer is checked before it's typed: if it wrote *about* the transcript
   ("I can't make sense of this") instead of cleaning it, the plain transcript is used.

### Going further: a better speech engine

The biggest remaining gap to Wispr Flow is the speech engine itself. Whisper large-v3 is no
longer the most accurate: on Artificial Analysis's independent benchmark (late 2026) ElevenLabs
**Scribe v2** makes roughly half the word errors (about 2% against about 4%). We're staying on
Groq for now. To switch later:

- Add an `ELEVENLABS_API_KEY` to `.env` and a second engine beside `whisper()` in
  `server/app.py` (it returns the same `Heard` object, so nothing else changes). Scribe takes
  the dictionary as "keyterms" rather than a prompt.
- Best of both: keep Whisper as the second opinion, so the two engines cross-check each other.
- Before switching, set `KEEP_HARD_AUDIO=1` for a week or two. The server then keeps the
  recordings Whisper struggled with (`data/audio/`, newest 50, never from private fields), and
  those can be replayed through both engines to see whether Scribe really is better on your
  voice, rather than trusting a benchmark.

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

Tests: `pip install -r server/requirements.txt pytest && pytest server` (Groq and Claude are
faked, so no keys are needed). The keyboard's spelling tests: `gradle testDebugUnitTest` in `android/`.

API: `POST /v1/dictate` multipart `audio`, `app`, `duration_ms`, header
`Authorization: Bearer <SPEAKEASY_TOKEN>` → `{"text", "raw", "ms"}`.

## Credits

Headings use Source Serif 4 by Adobe, under the SIL Open Font License 1.1
(`android/app/src/main/assets/licenses/SourceSerif4-OFL.txt`, shipped inside the app).

The keyboard's word list combines SymSpell's English word frequencies (MIT) with the SCOWL
Australian English spelling dictionary (`assets/licenses/WordList-licenses.txt`). Rebuild it
with `android/tools/build_wordlist.py`.
