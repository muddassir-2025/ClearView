# Media playback: one background player

What listens, what watches, and where the sleep timer lives.

## The problem this was built for

A YouTube video in this app plays through the official IFrame player inside a
WebView — the right player for *watching*, and the wrong one for *listening*.
The embed stops when the app leaves the foreground, so "press play and put the
phone in your pocket", which is most of what a long talk, lecture or recitation
is for, was impossible. Instagram clips had the same shape of limit from the
other direction: they played in a `MediaPlayer` owned by the screen.

Meanwhile, downloaded audio *did* keep playing (a foreground service with a
notification), so the app had background audio for the one kind of content that
had to be fetched in full first.

## One engine, two sources

```
                 AudioPlayback  (facade, Compose state)
                        │
        ┌───────────────┴───────────────┐
        │                               │
   downloaded file                 audio-only stream
   (filesDir/downloads)         (resolved on this device)
        │                               │
        └───────────────┬───────────────┘
                        │
              AudioPlaybackService
        (foreground, MediaPlayer, MediaSessionCompat,
         media notification, sleep timer)
```

`AudioPlayback` is the app-facing facade — every command is forwarded to the
service by intent, and the service writes observable state back into it, so
every screen, the notification and the lock screen agree. It was called
`OfflineAudioPlayer` while downloads were the only thing it could play; the name
was the one thing about it that had become a lie.

A second engine for streamed audio was the alternative, and it would have meant
a second notification, a second `STOP`, and two ways to end the thing — for a
reader doing one thing either way.

## Listen mode

Tapping the headphones in the player's transport (or **Listen in background** in
its ⋮ menu) hands the video's audio to the background player. Nothing is drawn;
the picture is not needed to hear a talk.

**How the audio is obtained, on this device:**

| Source | Route |
| --- | --- |
| YouTube | `OnDeviceStreamExtractor` — NewPipe's innertube client, the same call the audio downloader uses. Picks the highest-bitrate stream of the **original-language** track, so a dub is never what plays. |
| A downloaded video | The local file. No resolution, no network, and no expiry to run into — resolved before any network call is made. |
| Instagram reel / video post | Its own progressive `.mp4` (Meta publishes one muxed file; there is no audio-only stream to be had). Playing it without drawing it is still the win, because it now survives the app being backgrounded. |

Because the answer does not change between two taps, it is written down
(`ResolvedStreamStore`, shared with the Reel resolver and namespaced by key so
the two can never be confused). The store's expiry rule reads the URL's own
`oe` (Meta, hexadecimal seconds) or `expire` (Google, decimal seconds) parameter
— so a lapsed URL is re-resolved rather than handed to a player that would
buffer and then fail.

**When a stream dies mid-session** — an expired signature, a dropped connection
— the service resolves a fresh URL once and resumes from the position it was at.
Once per load, deliberately: a URL that is genuinely dead would otherwise loop,
waking the network forever in the background.

**Exclusivity.** Listening and watching the same video are mutually exclusive,
and both directions are enforced: starting listen mode pauses the in-screen
player first, and resuming the video stops the background audio. Two copies of
the same audio a few hundred milliseconds apart is the one failure of this
feature that is worse than not having it.

**Watch progress follows.** A stream played to the end is filed as watched, and a
stopped or paused one as partially watched — via the same `WatchProgressStore`
the player writes to, so a talk heard with the screen off shows up in the feed
with its progress bar instead of looking unopened. Written at the three moments
the position stops moving (pause, stop, finish), never per tick.

## The sleep timer

Lives in the service, not in a screen, because it has to keep counting with the
screen off and the phone face down — the only situation it is ever used in.

- **A deadline, not a total of listening.** `SystemClock.elapsedRealtime()`
  based, so pausing to take a call does not buy the audio more minutes.
- **The last three seconds fade out.** Audio stopping dead in a quiet room is the
  opposite of the point. The same clock the countdown reads decides the volume,
  so the pill and the fade can never disagree.
- **"End of this track"** is the other half of the same control, for the other
  shape of listening — one more video and then sleep. Mutually exclusive with a
  countdown: "stop in ten minutes and also when this ends" is two timers, and one
  of them always surprises the reader.
- **It is offered wherever audio can start**: the sleep-timer pill beside the
  speed pill in the offline player, and the ⋮ menu of the video player — the
  latter only while that video is what is playing, since a timer set on a session
  that is not running would do nothing and say nothing.
- **The countdown is on the notification too** — that is the screen people look
  at when they set one and put the phone down. It keeps counting while paused.
- **Setting a new source, or stopping, or finishing, clears it.** A timer belongs
  to a listening session, not to the service.

## What it costs

Resolution is on-device; nothing about a listening session or a sleep timer goes
anywhere. Choosing a 90-minute video as audio rather than video is a large
reduction in bytes, which is what makes "leave it running" reasonable. The
notification is rebuilt at most once a second, and only while playing or while a
countdown is running.

## Boundaries worth knowing

- **Live broadcasts cannot be listened to** — a live stream is a manifest, not a
  progressive audio stream, so there is nothing to point a player at. The button
  is not offered, and the resolver returns nothing rather than guessing.
- **The first listen to a video this device has never resolved takes a few
  seconds** (an innertube round trip). The transport draws that as a ring around
  the headphones button and says so with a toast if it fails; the second tap is
  instant.
- **A photo post has no audio**, so listen mode is not offered for one.
- **Without `POST_NOTIFICATIONS`** (Android 13+) the service still runs, but the
  media notification — and therefore the lock-screen controls — are absent.

## Manual checks

1. Open a long YouTube video, let it play, tap the headphones. Audio continues;
   the video pauses. Lock the phone: audio keeps playing, the notification shows
   the title and channel.
2. Tap **Stop listening** (or the notification's pause): the audio stops.
3. Open a reel, tap the headphones: it keeps playing with the app backgrounded.
   Reopen the video and press play — the background audio stops.
4. Tap the headphones while holding the video mid-way: listening starts at that
   position, not at zero.
5. Let a video finish while listening. The Media tab shows it as watched.
6. Set a 5-minute sleep timer from the sleep pill and from the video player's ⋮
   menu. Both count down on the pill and in the notification; audio fades out
   and the notification disappears at zero.
7. Set **End of this track** near the end of something: playback tears down when
   it finishes instead of staying loaded.
8. Turn on airplane mode and press the headphones on a video never played on this
   device: a toast, no spinner left running.
