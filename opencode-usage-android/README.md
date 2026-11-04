# AI Usage — OpenCode Go, Claude, Codex and Grok limits on Android

One home screen with your usage for four services, plus an OpenCode page laid out
like the console's Activity page.

| Card | Shows | Where the numbers come from |
|---|---|---|
| **OpenCode Go** | 5-hour, weekly, monthly | `GET opencode.ai/zen/go/v1/usage` with your Go API key |
| **Claude** | 5-hour, weekly | claude.ai's own usage endpoint, using your claude.ai sign-in |
| **Codex** | 5-hour, weekly | chatgpt.com's Codex usage endpoint, using your ChatGPT sign-in |
| **Grok** (experimental) | weekly | The text of the Grok page you pick that shows your usage |

Tap the OpenCode card for **Models and details**: Go usage %, limits, most-used
models, requests, tokens and extra usage, read from the OpenCode console (sign in
with GitHub).

## Manual refresh only

Nothing runs in the background. The app contacts a site only when you tap
**Refresh**: not on launch, not after signing in, and never from the widget.
Every number shows when it was fetched ("As of 14:05", with the date if older).

## When a site needs you

Refresh normally reads each site invisibly. If a site shows a "verify you're
human" check, signs you out, or shows a page the app can't read, that site comes
up full screen instead of failing. Sign in or tap through the check, then tap
**Continue**. For Grok and the OpenCode console, Continue reads the page you're
on, so you can also navigate to the right tab first. If it still can't read the
numbers, the card shows the error. **Cancel** (or Back) skips that site.

## Setup (Accounts)

- **OpenCode Go API key**: from opencode.ai/console.
- **Claude / ChatGPT / Grok**: sign in with email in the in-app browser, then tap
  **Done**. Google sign-in is blocked inside in-app browsers. If a site emails
  you a sign-in link, long-press it in your mail app → Copy, then tap
  **Paste link**.
- **OpenCode console**: sign in with GitHub, open the Activity tab, tap **Done**.
- **Grok**: after signing in, navigate to the page that shows your weekly usage,
  then tap **Done**. That page is what Refresh reads.

Sign-ins live in the app's private WebView storage. "Sign out" deletes that
site's cookies; "Sign out of all websites" clears everything.

**Show data** (in Accounts and on the OpenCode page) shows the last raw response
from a site. If a card shows an error or wrong numbers, copy that and send it
over so the parser can be fixed.

## Caveats

None of these endpoints are official public APIs; they're what each site's own
pages use. They can change without notice. When a response can't be read, the
card shows an error instead of guessing.

## Widget

Long-press the home screen → Widgets → **AI Usage**. It shows the last fetched
numbers for all four and never contacts any server. Tap it to open the app.

## Build

```sh
./gradlew assembleDebug        # JDK 17+, Android SDK platform 35
./gradlew testDebugUnitTest    # parser tests + Robolectric screenshots (app/build/screens)
```

Requires Android 11+.
