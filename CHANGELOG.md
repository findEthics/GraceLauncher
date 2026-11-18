# Changelog

## 1.2.10

- Added an optional Health chat source that answers questions about your steps, sleep, resting heart rate and weight trends from your personal health dashboard, with answers generated on-device.
- Health replies show whether data is live, cached (kept encrypted for up to 7 days) or unavailable.
- Incomplete or suspicious health data is flagged instead of presented as reliable.
- Messages describing urgent symptoms get emergency guidance instead of coaching.
- Added setup in Advanced: paste an access token to enable the Health source; it appears only once a token is saved and can be removed at any time.

## 1.2.9

- Added Wikipedia as a chat source for grounded answers with section-level sources.
- Showed up to 10 web source cards per reply with expandable excerpts and Open source links.
- Added "source N" follow-ups (English and German) that answer from a saved source without a new search.
- Improved how much conversation and evidence fits in a reply.
- Fixed the keyboard staying focused when returning to Home.

## 1.2.7

- Improved web search for news and recent topics, with cleaner sources and fewer duplicates.
- Fixed web search not working in release builds.
- Replaced the voice assistant with on-device text chat; microphone access is no longer requested.
- Added text chat on Home, opened with Space, plus an option to hide the chat button.
- Added keyboard commands on Home: @ to message, # to call, + to draft a calendar event, ! for alarms and timers, and Alt shortcuts for quick notes and to-dos.
- Added saved confirmations with emojis, and new chat drafts for contacts without an existing chat.
- Refreshed the launcher icon and fixed notification dismissal and Home layout issues.

## 1.2.6

- Fixed the assistant crashing on its first reply in release builds.
- Made assistant actions more consistent and predictable.

## 1.2.5

- Kept model downloads running when paused or when the screen is closed.
- Moved download status to the top of Advanced so errors are easier to see.

## 1.2.3

- Added resuming of interrupted model downloads.
- Showed the real reason when a download fails.
- Added a show/hide toggle for the download token.

## 1.2.2

- Fixed the assistant falling back to the CPU reliably if the GPU fails.
- Stopped showing temporary speech errors as missing language packs.

## 1.2.1

- Made the assistant faster by running it on the GPU where available.
- Offered a shortcut to speech settings when on-device recognition is unavailable.

## 1.2.0

- Added an offline voice assistant that runs on the device and can perform simple actions.
- Improved launcher profiles, notifications, gestures and the agenda.
- Added notification previews and replies for work-profile apps.
- Fixed the battery display disappearing when the calendar agenda is empty.
- Fixed the status bar hide animation.

## 1.1.4

- Built-in default widgets can now be disabled, including the clock, agenda, music controls and Grace Button.
- Made it easier to add folders and app shortcuts to favorites and reorder them.
- Updated the display logic for all lists.
- Fixed home screen layout shifts when showing or hiding the status bar.
- Made minor UI adjustments.

## 1.1.3

- Improved Private Space with support for more modes.
- Added search engine selection to the search component.
- Added a Launcher category at the bottom of the app list for recently installed apps and settings.
- Added home screen app actions to copy package names and choose which store page to open.
- Added German translations by @dev-inside and partial Greek translations by @iByteABit256.

## 1.1.2

- Updated Icon Designer with support for adjusting home screen icon sizes.
- Added a new search component for apps and contacts, with fuzzy search support.
- Fixed minor UI issues and bugs.

## 1.1.1

- Added Private Space support.
- Fixed a bug where hiding apps from the app list also hide them elsewhere.
- Added quick search to all app pickers.
- Fixed minor UI issues.

## 1.1.0

- Added icon pack support and Icon Designer.
- Added app shortcuts.
- Added widget support.
- Added experimental support for some system animations.
- Added new clock styles.

## 1.0.0

First release of Grace Launcher.

- List-based home screen with favorite apps and alphabetical browsing.
- Calendar agenda, notification previews and music controls.
- Material You colors, icon packs, folders and hidden apps.
- Optional weather integration with Breezy Weather.
