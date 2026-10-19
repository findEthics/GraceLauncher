---
name: set-alarm
description: Set one explicitly requested alarm using the device Clock app.
---

# Set alarm

For an authorized, standalone alarm request, call the `run_js` tool with:

- `scriptName`: exactly `index.html`
- `data`: a JSON string with `time` as exact 24-hour `HH:mm` and `label` as a string (empty when no label was requested)

Use only details from the latest `!` command. The time must be explicit. When no day is stated, use the next occurrence: today if the time is still ahead, otherwise tomorrow if it has passed. For example, `wake me up at 7am` uses tomorrow when it is already past 7am; still pass `07:00` to the script, without adding a date or day field. Explicit today must be ahead today; explicit tomorrow is supported only after that time has passed today. A bare `7:00` is ambiguous; `7am`, `7:00am`, and `07:00` identify a time. Never invent a time or repeat an alarm. The host independently validates the request and the skill result before opening Clock. A returned proposal is not confirmation that Clock created the alarm.
