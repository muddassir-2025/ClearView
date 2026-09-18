# ClearView — Google Play Store Listing Texts

Everything below is copy-paste ready.

---

## 1. Short description (max 80 characters)

```
Privacy-first parental controls: block adult content, keywords & incognito.
```
(75 chars ✓)

Alternative (mentioning productivity and content hub):
```
Parental controls, clean Media, Quran, Todo reminders & Phone Limit.
```
(69 chars ✓)

---

## 2. Full description (max 4,000 characters)

```
ClearView — Privacy-first parental controls, clean Media, Quran, Todo reminders & Phone Limit.

Protect your family's browsing while maintaining focus and discipline — all in one private, ad-free app with no accounts, no cloud sync, and on-device processing.

🛡️ Blocks what should be blocked
• Adult and explicit content across Chrome, the Google app, YouTube, and in-app browsers
• Custom blocked keywords and user-defined website blocklists
• Incognito mode in Chrome, preventing private browsing from bypassing protection
• Strict Modes for tighter safety filtering
• Community-updated channel and keyword blocklists

🔍 How it works — on your device, not on a server
• Real-time on-device text and URL screening via the Android Accessibility Service, used solely for content blocking
• Screen content is analyzed in volatile memory and is never logged, saved, or uploaded
• No accounts, no sign-ups, and no personal data ever leaves your phone

📖 Media & Islamic Content Hub
• Media: Follow your favorite YouTube channels and Instagram creators in a clean, distraction-free feed. Watch Shorts, Reels, and videos, or download audio for offline listening with background playback and lock-screen controls.
• Quran: Daily verses in IndoPak Arabic script and Dr. Mustafa Khattab's English translation ("The Clear Quran"), bookmarks, and a home-screen widget.
• Worship & Mindfulness: Dhikr counter with haptic feedback, Umm al-Qura Islamic Hijri calendar, and live Makkah & Madinah broadcasts.

⏱️ Productivity & Phone Limit
• Todo & Task Manager: Organize tasks with Normal, Attempted, and Duration-based task tracking, exact alarm alerts, full-screen notifications, and permanent revision history.
• Phone Limit: Monitor daily screen time locally and set daily countdown limits that automatically lock the screen when expired to encourage healthy digital habits.

🔒 Made for families
• Protect the Block settings with a password of your choice (secured with SHA-256 cryptographic hashing)
• Device Admin uninstall protection to prevent unauthorized removal
• Full transparency: Your settings, tasks, and media stay strictly in your device's private storage. Uninstalling the app deletes everything.

Permissions used:
• Accessibility service — for real-time on-device content blocking
• Usage access (optional) — reads today's screen time locally for the Phone Limit countdown
• Notifications — for channel-upload alerts, Quran reminders, and task alerts
• Exact alarms & Full-screen intent — for on-time Todo reminders and lock-screen alarms
• Foreground services — for background offline audio playback and phone limit timer
• Internet — to fetch user-requested media, Quran texts, and community blocking rules
• Device admin (optional) — for uninstall protection and screen locking on timer expiry
• Vibrate — for Dhikr counter haptic feedback and alarm ringing
```

(~2,450 chars ✓)

---

## 3. Accessibility declaration (Play Console → App content → Accessibility)

Question: "Does your app use the accessibility services API?"

Answer: **Yes**

Declaration (paste into the provided field):

```
ClearView uses the Android AccessibilityService solely for its core content-blocking feature. When the user enables protection, the service inspects on-screen text, URLs, search queries, and window states within Chrome, the Google app, and embedded WebViews in real time to detect and block: (1) adult and explicit content via built-in and user-defined keywords, (2) user-blocked websites and domains, (3) inappropriate search results and video content on YouTube, and (4) incognito browsing sessions. All analysis happens locally on the device in volatile memory: screen content is never recorded, stored on disk, logged, or transmitted to any server, and is used for no other purpose. The service remains disabled until explicitly enabled by the user in system settings and can be turned off at any time. ClearView does not use the accessibility API to access data outside this blocking function, does not modify other apps or their interfaces, and complies fully with Google Play Accessibility API policy.
```

Then tick the checkbox confirming the app complies with the policy.

---

## 4. Usage Access declaration (Play Console → App content → Usage Access / Sensitive Permissions)

If Play Console prompts regarding `PACKAGE_USAGE_STATS`:

```
ClearView uses the PACKAGE_USAGE_STATS permission solely for the optional "Phone Limit" feature. With explicit user consent in system Usage Access settings, the app reads the device's daily screen-on time via UsageStatsManager to calculate remaining allowance against a user-configured daily limit. All usage event calculations occur strictly on-device in real time. ClearView does not track individual app usage, does not store application history, and never transmits any usage data off the device.
```

---

## 5. Device admin declaration (Play Console → App content → Device Admin)

```
ClearView includes an optional Device Administrator component used only to provide uninstall protection (preventing unauthorized deactivation of parental protection) and to lock the screen (lockNow) when the user's daily Phone Limit timer expires. It performs no other device-administration functions and can be deactivated by the user at any time in device settings.
```

---

## 6. Category, Data Safety & Privacy Policy URL

- App category: **Parenting** or **Tools** / **Productivity**
- Content rating: complete the questionnaire honestly; expect a mature/parental guidance rating for the blocking nature
- Data safety:
  - **App info and performance / Crash logs / Diagnostics**: Disclose Firebase Analytics diagnostics if bundled in the build.
  - **Device or other IDs**: Pseudonymous app instance ID (collected automatically by Firebase Analytics for app stability).
  - **Personal data**: **No** personal data (no name, email, phone, location, or browsing history is collected or shared).
- Privacy policy URL: Paste your hosted GitHub Pages or public URL pointing to `docs/privacy-policy.html` (or `github.html`).
