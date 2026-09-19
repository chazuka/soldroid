# chatty

An Android client for the `kamartaj.xyz` avatar demo. Kotlin, Jetpack Compose, `minSdk 29`, one
activity, two screens.

It is the WoW-ID client (`../wow.ocbc.id/droid`) with the bank taken out of it. Where that app
reached a `wowid-server` that held the model, the gates and the vendor credentials, this one talks to
three services directly and has no backend at all.

```bash
./gradlew :app:assembleDebug     # the APK
./gradlew :core:ai:test          # the deterministic layer — 17 tests, no device
./gradlew :app:installDebug      # onto an attached handset
```

## The screens

**Agents.** `GET /v1/models` on the chat API, one row per persona. No face is previewed here: a
LiveAvatar session is billed per minute, and a list that opened three of them for thumbnails would
cost money before the customer had chosen anything.

**Companion**, in three full-screen modes. The avatar is mounted once and stays mounted behind all
of them — switching changes what is drawn *over* the face, never tearing down the billed session:

| Mode | What it is |
|---|---|
| `VIDEO` | The design's voice screen with the agent's face at its centre. The resting state. |
| `VOICE` | The same screen with the face replaced by the brand orb — camera off. |
| `TEXT` | The whole transcript, read rather than heard, on the design's chat thread. |

`VIDEO` and `VOICE` are one composition — the canvas's screen 14 — differing only in what stands at
its centre. They are one conversation with the camera on or off, not two destinations, and sharing a
layout is what keeps the toggle feeling like a toggle.

Two things the canvas could not anticipate:

- **The face is a rounded 9:16 card, not the canvas's circle.** The avatar video is 720×1280, and a
  9:16 rectangle cannot fit inside a circle whose diameter is its height — the corners fall outside,
  so a circle slices the top of the head off flat. Measured on device, after the first build did
  exactly that. The card keeps the canvas's place and its concentric glow and takes the shape its
  own aspect ratio demands.
- **Text mode silences the avatar.** A thread is meant to be read; an answer arriving out loud over
  it is startling, and on a phone in public worse than startling. Playout is suppressed for as long
  as the thread is open, through a reason independent of the customer's own mute so leaving the
  thread never clobbers a mute they set deliberately.

A face and a transcript compete for the same attention, so splitting the screen between them serves
neither: the face is too small to read as a person and the transcript too short to read as a
conversation. Each mode therefore takes the whole screen. Speaking a question from `AVATAR` moves to
`VOICE` on its own, so the customer can see what was heard.

Back steps out of a mode before it leaves the conversation.

## One turn, three vendors, overlapped

The order is the design, not an implementation detail:

| Step | Who | What crosses the wire |
|---|---|---|
| 1 | `kamartaj.xyz` | the question, and the answer **streamed back** — the only text this app sends anywhere |
| 2 | `sentences()` | those fragments regrouped into clauses |
| 3 | ElevenLabs | each clause, rendered to PCM 24 kHz as a stream |
| 4 | LiveAvatar | those samples, as base64 `agent.speak` frames. **Never a word of text** |

The overlap is the point: sentence one is already being spoken while the model is still writing
sentence three. `agent.speak_end` is still sent once, at the very end, so the provider treats the
whole answer as a single utterance and reports one `agent.speak_ended`.

Measured end to end on a Galaxy S25, by speaking real questions out of a laptop into the handset's
own microphone rather than by injecting text:

```
llm 3482ms/5563ms  tts 4130ms  wire 4141ms  lips 4962ms/30517ms  audible 5100ms  claim -138ms  4 sentence(s)
```

The first audio frame reached the avatar at 4130 ms — **1.4 s before the model finished generating**,
which is the overlap above doing its job. Sound reached the room at 5100 ms, and that mark comes off
the decoded audio rather than off the provider saying so; see `TurnTrace.claimSkewMs` for why the two
are not the same thing.

Where that time goes, on `Model 1`, median of the runs above:

| leg | cost | whose |
|---|---|---|
| first token | ~3.3 s | the model's |
| clause + synthesis | ~0.14 s | ours |
| encoding to the socket | ~0.01 s | ours |
| render and transport | ~1.07 s ± 0.10 | the avatar provider's |

About 150 ms of the wait belongs to this app. The rest is the model assembling a persona and a
customer record on every turn, and a renderer that is consistent but not fast.

Steps 3 and 4 are allowed to fail, and when they do the conversation carries on in text — the face
is an enhancement, never the channel. Long-press the agent's name to see the trace for the last turn.

LiveAvatar LITE has no speak-text command, which is why ElevenLabs is here at all. It is also why the
provider cannot reinterpret, rewrite or add to what the agent said: it receives 16-bit samples and
has nothing to reinterpret. FULL mode — where the provider would run its own ASR and its own LLM — is
refused in `LiveAvatarSession` with no configuration to turn it back on.

## The design canvas, and what was taken from it

The visual language comes from the **OCBC Conversational Banking** canvas (`v3 — "Chat-only banking:
the thread is the app"`), read through the Claude Design MCP. That canvas describes a *different
product*: one assistant rather than three personas, a full sign-in flow, guided transfers, OTP
sheets — and **no avatar anywhere**. Its voice mode is an audio-level bar visualiser.

So it was taken as a skin, not as a spec:

| Taken | Left behind |
|---|---|
| Palette: OCBC red `#E1251B`, slate ink `#22313A`, canvas `#F4F6F7`, the three hairline greys | Sign-in, passcode, guided transfer, OTP, receipts — this app has no transactions |
| Public Sans, and the sizes the canvas names (22/15/16/14/12) | The single `{{ assistantName }}` — three personas is the point here |
| The chat thread: solid header, "Secure session", timestamp divider, tailed bubbles, starter chips, pill composer between two circles | The bar visualiser — where the canvas shows it, this app shows the face |
| The outlined status pill, the 56dp glass controls, the 14/18/22 radii | *"Amounts and account numbers stay on screen — never spoken aloud"* |

That last omission is deliberate and worth stating: the canvas suppresses figures in speech for
shoulder-surfing reasons, and this app does the opposite — see *Numbers are spoken* below. A talking
companion that goes silent at the number is a stranger thing than one that says it. Flipping the rule
means inverting `spokenForm` from a speller into a redactor; nothing else would have to move.

## The stage

The provider renders the head against **chroma-key green**. A custom `RendererCommon.GlDrawer` keys
it out in the fragment shader and composites onto the stage colour, so the face stands on the app's
own ground instead of in a green box. Two details earn their keep:

- The key is matched on **chrominance**, not RGB distance, so a shadowed corner of the backdrop keys
  out along with the lit middle of it.
- Green **spill** — bounced light on hair and shoulders — survives keying, and desaturating the edge
  to grey just trades one artefact for another. Instead the green channel alone is capped at what red
  and blue say it should be, so the rim takes the subject's own colour.

The drawer lives in `core/avatar/src/main/kotlin/livekit/org/webrtc/`. That package declaration is
deliberate: `GlGenericDrawer`, which already knows how to bind OES/RGB/YUV textures and manage the
shader lifecycle, is package-private in WebRTC's relocated namespace.

LiveKit is also configured as a **listener, not a call participant**. Its defaults put the device in
communication mode, take `USAGE_VOICE_COMMUNICATION` focus, route to the earpiece and prewarm an
`AudioRecord` — which on a real phone means the system microphone indicator sits lit over an app that
never records, and the avatar talks quietly out of the earpiece. `AudioType.MediaAudioType()` +
`NoAudioHandler()` + `disableAudioPrewarming` fix all of it.

## "HeyGen" is LiveAvatar

HeyGen sunset `/v1/streaming.*` at the end of March 2026. LiveAvatar (`api.liveavatar.com`) is the
replacement: a separate product, a separate account, a separate key. A HeyGen key is rejected here
with code 4001, and HeyGen's 32-hex avatar ids do not resolve — LiveAvatar's are UUIDs.

A session is capped at `max_session_duration`, **300 seconds** on this account. A conversation
routinely outlives that, so `CompanionViewModel` watches the deadline and replaces the session *while
the customer is reading* — before a turn, and on a 15-second idle check. Replacing it during a pause
is invisible; replacing it after it dies is not.

The turn's end comes from the provider, not from guesswork. The LITE socket reports
`agent.speak_started` and `agent.speak_ended`, and the second is what returns the composer. The
fallback bound is derived from the audio's own length (`bytesSent / 48000`) plus ten seconds, so a
one-line answer and a six-sentence one are not given the same wait.

## Modules

| Module | Holds |
|---|---|
| `core:ai` | **Plain JVM.** The chat client, the speech synthesizer, the transcript and the turn phases. No Android, so it is tested without an emulator. |
| `core:avatar` | The LiveAvatar LITE session (HTTP handshake + command socket) and the LiveKit subscriber that renders the face. |
| `core:ui` | Theme tokens. Warm paper and OCBC red, light and dark, dynamic colour off. |
| `app` | The two screens, the ViewModels, and the DI graph. |

`core:ai` is a plain JVM library on purpose. Everything that decides *what* the companion says lives
there and is unit-testable; adding an Android dependency to it is the change that would end that.

## Who each agent is

Face, Indonesian voice and English voice per persona. A persona has to sound like the same person in
both languages, so each gets a matched pair rather than one voice and a fallback.

| Agent | Face (LiveAvatar) | Bahasa | English |
|---|---|---|---|
| **Daniel** — data-driven coach for a young professional | Pedro, black suit | Ongky — calm & deep, native `id-ID` | Eric — smooth, trustworthy |
| **Emma** — cheerful money buddy for a 16-year-old | Rika, young | Velora — warm, youthful, native `id-ID` | Jessica — playful, bright, warm |
| **Sophia** — calm family financial partner | Marianne, mature | Zorin — friendly, calm, soft, native `id-ID` | Sarah — mature, reassuring |

The `ID`/`EN` chip in the top bar picks all three at once: the speech recogniser's model, the voice
the answer is spoken in, and — because the personas answer in whatever language they are asked in —
the language of the reply. Splitting those into separate settings would let them disagree.

The voice is resolved once per turn, when the turn starts. Switching language mid-answer would
otherwise change voice mid-sentence.

## Configuration

Two kinds, kept apart because they have different threat models.

**Secrets** live in `.env` at the repo root (gitignored; see `.env.example`) and are read at build
time into `BuildConfig`. Three keys: `CHATTY_API_KEY`, `LIVEAVATAR_API_KEY`, `ELEVENLABS_API_KEY`.
CI can supply them as process environment variables instead — there is no `.env` on a build agent.

> **These keys ship inside the APK, and an APK is not a secret store.** Anyone holding the file can
> read them back out. That is the accepted cost of having no backend, and it is a demo-only bargain:
> the moment this is anything but a demo, the two vendor keys move behind a service that mints
> short-lived tokens, and only that service's URL ships in the app.

**Faces and voices** live in `app/src/main/assets/agents.json`, because they are not secrets, they
change more often than the code does, and an operator swapping a voice should not need a Kotlin file
open to do it:

```json
{
  "default": { "avatar_id": "<LiveAvatar UUID>", "voices": { "id": "<voice>", "en": "<voice>" } },
  "overrides": {
    "emma": { "avatar_id": "<uuid>", "voices": { "en": "<a brighter English voice>" } }
  }
}
```

Voices merge per language rather than wholesale, so an override naming only an English voice keeps
the default Indonesian one instead of silently losing it. A persona the chat API adds tomorrow
appears in the list immediately, wearing the default face and voices until someone gives it its own.

An ElevenLabs voice id is **workspace-scoped**: a voice from another account, or from an ElevenLabs
data-residency environment, carries a different id and will not resolve against this key. Ongky was
added to the workspace from the shared library with
`POST /v1/voices/add/{public_owner_id}/{voice_id}` — note the owner id is 64 hex characters, and a
truncated one fails as `public_user_not_found` rather than as a permissions error.

## Numbers are spoken, not spelled out

The transcript shows `Rp3.240.000` because that is what a customer matches against their own account.
A synthesizer handed those characters reads a run of digits and full stops — ElevenLabs' fast models
disable number normalisation by default and normalise to *English* conventions when it is enabled at
all. So `spokenForm` rewrites the numbers on their way to the voice and nowhere else:

```
"Saldo kamu Rp3.240.000, naik 1,2%."
→ "Saldo kamu tiga juta dua ratus empat puluh ribu rupiah, naik satu koma dua persen."
```

It never cuts text — cutting is how a rewriter manufactures a figure, by truncating `Rp852.300.000`
to `Rp852`. It replaces a token with a spelling of that same token, and refuses to return one it
cannot parse **back** to the original digits. A number it cannot spell with certainty is left as
digits: awkward, and correct. Dates (`2026-09-10`) and product ids (`SBN-SR021`) are invisible to it.

## Known limits

- **Speech recognition defaults to `id-ID`.** A handset with no on-device Indonesian model downloads
  one on first press.
- **Baseline Profile is generated, not shipped-from-CI.** `./gradlew :app:generateBaselineProfile`
  needs a connected device; nothing regenerates it automatically.
