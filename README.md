# VanKeyboard (v0.14)

*Formerly KeyMagic.*

An Android keyboard. All the keyboard logic is written in Rust (`keycore/`): layouts,
shift/caps, word and emoji prediction, the clipboard history and OTP detection.
A thin Kotlin layer (`app/`) draws the keys and talks to Android.

## Features
| | |
|---|---|
| **Clipboard history** | Keeps your last 10 copied items: text, numbers, images and new screenshots, all in one list. An 11th item replaces the oldest. Tap to paste, long-press or ✕ to delete. Images paste straight into apps that accept them; otherwise they go on the clipboard so you can long-press → Paste. |
| **Resize** | Toolbar ↕ (or Settings): 70–140 % height. |
| **Number row** | Toolbar **123** button or Settings. |
| **Emojis** | Google's **Noto Color Emoji** (the Android emoji set, Emoji 17.0) is built in and used by default, so emojis look the same on every phone. Settings → Emoji style: **My emoji font** or **System emojis**. You can also pick another colour emoji `.ttf` on the phone (Settings → Change emoji font). Only the chosen font draws emojis – no fallback; emojis it lacks are hidden. No downloads, no internet permission. |
| **Vibration** | One crisp, hard knock per key press using the phone's tuned haptic click (not a motor buzz). On/off with a strength slider; strong by default. |
| **Key sounds** | On/off with a volume slider. |
| **Word prediction** | On by default: completions, typo fixes ("helo" → hello) and next-word predictions learned from your typing. It learns your own words (names, Luganda). Long-press a suggestion to forget it. Double-space types ". ". |
| **Emoji prediction** | Typing "love", "happy", "pizza"… suggests matching emojis, ranked by the ones you use most. |
| **OTP codes** | Reads codes from SMS, WhatsApp, email and bank notifications. The code appears as a 🔑 chip in the suggestion bar; tap it to type it. A copied code shows up there too. |
| **Key-press gradient** | The background is solid when idle: black in dark mode, white in light mode. Each key press blooms into a soft two-colour gradient that swirls, grows and fades away in about 1.3 s. Fast typing gives every key its own bloom, up to 12 at once, each on its own timer. Nothing animates while idle. It's off with *Remove animations* or Battery Saver, and you can turn it off in Settings → Look. |
| **Minimize** | The round ⌄ button at the right of the top bar hides the keyboard. |
| **Key popup** | A larger bubble with the character appears above the key you press (rises into the toolbar area for the top row). Switch off in Settings → Look. |
| **Look** | Samsung-style design that follows the phone's dark/light mode: black, or a light grey key area with white keys. Keys have soft depth (a shadow below, a hairline edge, and a faint sheen in dark mode). Also: rounded keys with more spacing, a shorter number row, outline shift/backspace icons, and an Enter key that shows the app's action (Done, Send, Go…, or a 🔍 icon in search boxes). The toolbar has round buttons (emoji, clipboard, number row, settings, ⋯ = size & layout). |
| **WhatsApp fix** | See below. |

The emojis you send are normal Unicode, so the people you message see them in their own
phone's (or WhatsApp's) style – the emoji font only changes how they look on your keyboard.

### Built-in emoji font
`fonts/NotoColorEmoji.ttf` (Noto Color Emoji v2.051, SIL OFL 1.1 – see `fonts/OFL.txt`)
is copied into the APK as `assets/emoji_font.ttf`, stored uncompressed so the keyboard
memory-maps it instead of loading it into RAM. To build a different font into your own
APK, set its path in `local.properties` (git-ignored):
```properties
emoji.font=C\:/path/to/YourEmojiFont.ttf
```

The font must be a colour font Android can draw: **CBDT** (bitmap) or **COLR** (vector).
Apple `sbix` and OpenType-SVG fonts are rejected. Only bundle fonts you are licensed to
redistribute – don't publish APKs that contain a proprietary font.

## The WhatsApp "keyboard covers the chat" fix
Usually a keyboard causes this when it tells Android it's shorter than it really is, or changes
height while you type. WhatsApp then lays out the chat for the wrong size. VanKeyboard:
- never uses full-screen "extract" mode (`onEvaluateFullscreenMode = false`)
- reports its exact top edge, and keeps touches inside the keyboard (`onComputeInsets`)
- keeps the suggestion bar inside the keyboard window instead of using a separate
  "candidates view" that apps don't count
- has its **final height before it first appears**: the navigation-bar spacing measured last time is applied up front, so the keyboard never grows after sliding in. (A late height change made WhatsApp's chat stay hidden until the notification shade or volume slider made it re-measure.)
- if its height does change while showing (resize, number row), and once just after the slide-in animation, it asks Android to send its size to the app again
- stays the same height on every page and panel (letters, symbols, emoji, clipboard)
- keeps keys above the navigation bar: it measures how much of the keyboard really lies behind the bar and pads by exactly that, so there's no overlap and no double gap. If your phone still overlaps, use **Bottom space** (⋯ in the toolbar, or Settings) to raise the keys up to 48 dp

- **Fallbacks:** in Settings → *Chat apps & navigation bar*, turn off **Automatic navigation bar spacing** and set **Bottom space** by hand. The height then never changes by itself.
- **Backup mode:** if a chat still hides behind the keyboard on your phone, open Settings → *Chat apps & navigation bar* and turn on **Backup mode**. This stops my custom calculation and uses Android's own default instead. Close and reopen the chat.

**To test:** open a WhatsApp chat and type. Switch to symbols, the emoji panel and the
clipboard, then rotate the phone. The last message should stay visible above the
suggestion bar every time. If it still happens, note when (which screen, after which
button) so the fix can target it.

## One-time setup
### On your PC (to build)
1. Install **Android Studio**. In *SDK Manager → SDK Tools*, install **NDK (Side by side)**.
2. Install Rust (https://rustup.rs), then run:
   ```sh
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   cargo install cargo-ndk
   ```
3. Set `ANDROID_NDK_HOME` to the NDK folder
   (Windows example: `C:\Users\<you>\AppData\Local\Android\Sdk\ndk\<version>`).
4. Open the project folder in Android Studio and press **Run**. Gradle builds the Rust
   part automatically (task `buildRust`).

### On the phone
Open the **VanKeyboard** app. The *Get started* card shows your progress and ticks off each step:
1. Turn on the keyboard → 2. Switch to it → 3. Allow photos (for screenshots) →
4. Allow notification access (for OTPs).

If step 4 says **"Restricted setting"**: Android blocks this for apps you install yourself.
Open *App info → ⋮ → Allow restricted settings*, then do step 4 again.

### Android 15+: let the keyboard see OTP codes
Android 15 hides one-time codes in notifications from apps installed outside the Play
Store. It shows "Sensitive notification content hidden" instead. Allow it once over adb:
```sh
adb shell cmd appops set --user 0 com.ssonkoict.keyboard RECEIVE_SENSITIVE_NOTIFICATIONS allow
adb shell am force-stop com.ssonkoict.keyboard
```
Then restart the phone. If you get a `SecurityException ... MANAGE_APP_OPS_MODES`, turn on
*Developer options → Disable permission monitoring*, reboot, and run the command again. This
may not work on every brand of phone. Without it, tap **Copy code** on the SMS
notification: the keyboard picks the copied code up and shows the 🔑 chip.

## Good to know
- Android lets only the **active** keyboard read the clipboard, so copies are saved while VanKeyboard
  is your selected keyboard.
- Items that apps mark as sensitive (for example passwords from password managers) are never saved.
- Screenshots: new ones are added automatically. The first time, the last 3 from the past 3 days are
  added. On Android 14, if you allowed only *selected photos*, only those are seen.
- Nothing you type is learned in password fields or in fields that ask for no learning
  (for example incognito browser tabs). Everything stays on the phone.

## Checks done while building
```sh
cd keycore
cargo test                # 30 tests: layout, shift, number row, prediction, clipboard, OTP
./hosttest/run.sh         # JNI bridge tested from a real JVM (emoji / UTF-16 round trips)
```

## Project layout
```
keycore/src/lib.rs        keyboard engine (layouts, shift, number row)
keycore/src/predict.rs    word + emoji prediction and learning
keycore/src/clipboard.rs  10-item clipboard history
keycore/src/otp.rs        OTP code detection (ignores MoMo amounts, balances, TIDs)
keycore/src/jni.rs        JNI bridge (no external crates)
app/src/main/java/com/ssonkoict/keyboard/
  KeyMagicService.kt   the keyboard service, WhatsApp fix, typing logic
  KeyboardRoot.kt            window layout + suggestion bar / toolbar
  KeyboardView.kt            keys
  Panels.kt                  emoji, clipboard and resize panels
  Emoji.kt                   emoji list + emoji font (built-in / picked) + EmojiView
  ClipboardStore.kt          clipboard + screenshot watcher, image pasting
  Otp.kt                     notification listener for OTP codes
  Feedback.kt                vibration + sounds
  GradientBackgroundView.kt  solid background + per-key gradient blooms
  IconButton.kt              round toolbar icons
  Prefs.kt / MainActivity.kt settings + setup screen
app/src/main/assets/       words.txt (117k words), emoji.tsv (1,910 emojis)
```

## Credits
- Emoji font: Google Noto Color Emoji v2.051 (SIL Open Font License 1.1, `fonts/OFL.txt`),
  github.com/googlefonts/noto-emoji
- Emoji names and keywords: Unicode CLDR (Unicode licence)
- Word list: SCOWL / Hunspell en_US dictionary, plus common-word ranking and Ugandan words

## License
VanKeyboard's own code is released under the [MIT License](LICENSE).
The third-party data listed under Credits keeps its original license.
