#!/usr/bin/env python3
"""
Promptpad NL->command dataset generator.

Produces natural-language -> structured-command JSON pairs for fine-tuning
Gemma 3 270M so the launcher search bar becomes natural-language capable.

Design (see notes/PLAN.md sections 10-11):
  - INPUT side is natural language (never !-syntax).
  - OUTPUT side is canonical JSON constrained to the CommandParser vocabulary.
  - 13 commands + list forms + negatives (decline -> {"command":"none"}).
  - Deterministic template x slot permutation for coverage; a light
    paraphrase/noise pass for natural variety; dedup; stratified split.

Output: JSONL in chat form: {"messages":[{role:system},{role:user},{role:assistant}]}
Run:    python3 generate_dataset.py --out-dir . --seed 42
"""
import argparse
import json
import random
import re
from pathlib import Path

# ----------------------------------------------------------------------------
# System prompt embedded in exported task metadata and used by the runtime template.
# ----------------------------------------------------------------------------
SYSTEM_PROMPT = (
    "You are the command interpreter for a minimal Android launcher. "
    "Convert the user's natural-language request into a single JSON object "
    "describing one launcher command. Respond with ONLY the JSON object, no prose. "
    "Valid commands: call, text, hermes, timer, alarm, todo, todos, note, notes, "
    "grocery, groceries, event, torch, camera. "
    "If the request does not match any command, respond {\"command\":\"none\"}. "
    "Normalize times to 24-hour HH:MM and durations to a compact form like 30s, 5m, 1h30m."
)

# ----------------------------------------------------------------------------
# Slot value pools
# ----------------------------------------------------------------------------
CONTACTS = ["mom", "dad", "the office", "John", "Sarah", "grandma", "Alex",
            "my boss", "the dentist", "Priya", "Tom", "the plumber", "Emma",
            "David", "the landlord", "Chris", "Maria"]
GROCERIES = ["milk", "eggs", "bread", "coffee", "bananas", "rice", "chicken",
             "tomatoes", "butter", "cheese", "onions", "apples", "pasta",
             "yogurt", "olive oil", "toilet paper", "cereal", "orange juice"]
TODO_TASKS = ["call the plumber", "submit the report", "pay rent",
              "book flights", "renew passport", "reply to Sarah",
              "water the plants", "back up my laptop", "cancel the subscription",
              "pick up the dry cleaning", "finish the slides", "email the client"]
NOTE_TEXTS = ["wifi password is hunter2", "parking spot B14", "idea for the app",
              "gate code 4471", "meeting moved to room 3", "book rec: Dune",
              "flight confirmation XZ42", "Anna's birthday is March 3",
              "reminder the car is due for service"]
MESSAGES = ["I'll be late", "on my way", "running 10 late", "call me when free",
            "dinner at 8?", "landed safely", "can you grab milk", "see you soon",
            "meeting pushed to 3", "happy birthday!"]
GROUP_MSGS = ["I'm omw", "running late sorry", "who's bringing snacks",
              "dinner's ready", "movie at 9 tonight", "count me in"]
EVENT_TITLES = ["dentist", "team standup", "lunch with Sam", "gym", "haircut",
                "project review", "call with client", "parents visiting"]

# Times: (natural, normalized HH:MM)
TIMES = [
    ("10pm", "22:00"), ("7:30", "07:30"), ("7:30am", "07:30"), ("6 am", "06:00"),
    ("quarter past 7", "07:15"), ("half past 8", "08:30"), ("noon", "12:00"),
    ("midnight", "00:00"), ("9pm", "21:00"), ("5:45pm", "17:45"),
    ("8 o'clock", "08:00"), ("11:15", "11:15"), ("3pm", "15:00"), ("6:30am", "06:30"),
]
# Durations: (natural, normalized)
DURATIONS = [
    ("30s", "30s"), ("30 seconds", "30s"), ("half a min", "30s"),
    ("5 minutes", "5m"), ("5 min", "5m"), ("ten minutes", "10m"),
    ("20 mins", "20m"), ("an hour", "1h"), ("an hour and a half", "1h30m"),
    ("45 seconds", "45s"), ("2 minutes", "2m"), ("90 seconds", "90s"),
    ("15 minutes", "15m"), ("3 mins", "3m"),
]
DATES = ["today", "tomorrow", "friday", "monday", "next tuesday", "the 5th",
         "saturday", "this thursday"]

FILLERS = ["", "", "", "hey ", "umm ", "ok ", "please ", "can you ", "could you ",
           "just ", "quick ", "yeah "]
TAILS = ["", "", "", "", " please", " thanks", " for me", " real quick", " now"]

# ----------------------------------------------------------------------------
# Template banks:  each returns (nl_text, output_dict)
# Templates use {slot} placeholders filled by the generator.
# ----------------------------------------------------------------------------
def T(templates):
    return templates

TIMER_T = T([
    "set a timer for {d}", "timer for {d}", "{d} timer", "start a {d} timer",
    "ping me in {d}", "remind me in {d}", "count down {d}",
    "set timer {d}", "wake me in {d}", "give me {d} on the timer",
])
ALARM_T = T([
    "set an alarm for {t}", "wake me at {t}", "alarm for {t}", "alarm at {t}",
    "get me up at {t}", "set alarm {t}", "wake me up at {t}",
    "i need an alarm at {t}", "alarm {t}",
])
CALL_T = T([
    "call {c}", "ring {c}", "dial {c}", "phone {c}", "give {c} a call",
    "call up {c}", "get {c} on the phone", "ring up {c}",
])
TEXT_T = T([
    "text {c} {m}", "message {c} saying {m}", "send {c} a text {m}",
    "tell {c} {m}", "shoot {c} a text {m}", "sms {c} {m}", "text {c} that {m}",
    "let {c} know {m}",
])
HERMES_T = T([
    "tell the family group {m}", "message the group {m} on telegram",
    "telegram the group {m}", "send to the group {m}", "post to the group chat {m}",
    "hermes {m}", "tell everyone {m} on telegram",
])
TODO_T = T([
    "remind me to {task}", "add a task to {task}", "todo {task}",
    "don't let me forget to {task}", "add to my todos {task}",
    "i need to {task}", "put {task} on my todo list", "task: {task}",
])
NOTE_T = T([
    "note down {n}", "jot down {n}", "make a note {n}", "note {n}",
    "write down {n}", "save a note {n}", "remember that {n}", "note to self {n}",
])
GROCERY_T = T([
    "buy {g}", "get {g}", "add {g} to my list", "pick up {g}", "we need {g}",
    "add {g} to the grocery list", "grab {g}", "put {g} on the shopping list",
    "need to buy {g}", "shopping: {g}",
])
EVENT_T = T([
    "event {title} {date} {t}", "add {title} to my calendar {date} at {t}",
    "schedule {title} {date} {t}", "calendar {title} {date} at {t}",
    "new event {title} on {date} {t}", "put {title} on the calendar {date} at {t}",
])
# no-arg / list forms
TODOS_T = T(["show my todos", "what's on my todo list", "open my todos",
             "list my tasks", "what do i need to do", "my todos"])
NOTES_T = T(["show my notes", "open my notes", "list my notes", "my notes",
             "what notes do i have"])
GROCERIES_T = T(["show my grocery list", "what's on my grocery list",
                 "open my shopping list", "list my groceries", "my grocery list",
                 "what do i need to buy"])
TORCH_T = T(["turn on the flashlight", "flashlight", "torch on", "turn on torch",
             "light", "switch on the flashlight", "enable torch", "torch"])
CAMERA_T = T(["open camera", "take a photo", "camera", "open the camera",
              "take a picture", "launch camera", "snap a photo"])

# Negatives -> {"command":"none"}
NEGATIVES = [
    "what's the weather tomorrow", "open spotify", "how tall is the eiffel tower",
    "play some music", "what time is it in tokyo", "launch chrome", "open settings",
    "who won the game last night", "tell me a joke", "what's 25 times 4",
    "open whatsapp", "switch to dark mode", "how do i get to the airport",
    "what's the capital of france", "start my run tracking", "open the app store",
    "define serendipity", "translate hello to spanish", "check my email",
    "open youtube", "what's on tv tonight", "search for pizza near me",
    "increase brightness", "turn off wifi", "what's the news",
]

# These are deliberately incomplete command phrases. A small model otherwise
# tends to invent the missing slot and trigger a real command.
HARD_NEGATIVES = [
    "wake", "wake me", "wake me up", "remind", "remind me",
    "buy", "get", "add", "call", "ring", "dial", "text", "message",
    "note", "alarm", "set an alarm", "timer", "set a timer", "event",
    "calendar", "hermes", "todo", "grocery", "shopping", "schedule",
    # Short fragments must remain app search, not model commands.
    "sh", "ala", "cal", "rem", "tim", "gro", "tex", "not", "cam", "tor",
    "pho", "sms", "eve", "war", "sho",
]

def fill(tmpl, **kw):
    return tmpl.format(**kw)

def noise(text, rng):
    """Light natural noise: filler prefix, tail, occasional lowercase/typo."""
    t = rng.choice(FILLERS) + text + rng.choice(TAILS)
    t = t.strip()
    # occasional casual typo
    if rng.random() < 0.06:
        t = t.replace("remind", "rmind").replace("tomorrow", "tmrw")
    if rng.random() < 0.5:
        t = t[0].lower() + t[1:] if t else t
    return t

def build(rng):
    rows = []  # (nl, out_dict, cluster)

    def add(nl, out, cluster):
        rows.append((noise(nl, rng), out, cluster))

    # timer
    for tmpl in TIMER_T:
        for nat, norm in DURATIONS:
            add(fill(tmpl, d=nat), {"command": "timer", "duration": norm}, "timer")
    # alarm
    for tmpl in ALARM_T:
        for nat, norm in TIMES:
            add(fill(tmpl, t=nat), {"command": "alarm", "time": norm}, "alarm")
    # call
    for tmpl in CALL_T:
        for c in CONTACTS:
            add(fill(tmpl, c=c), {"command": "call", "contact": c}, "call")
    # text
    for tmpl in TEXT_T:
        for _ in range(8):
            c = rng.choice(CONTACTS); m = rng.choice(MESSAGES)
            add(fill(tmpl, c=c, m=m), {"command": "text", "contact": c, "body": m}, "text")
    # hermes (telegram group)
    for tmpl in HERMES_T:
        for m in GROUP_MSGS:
            for _ in range(2):
                add(fill(tmpl, m=m), {"command": "hermes", "body": m}, "hermes")
    # todo
    for tmpl in TODO_T:
        for task in TODO_TASKS:
            add(fill(tmpl, task=task), {"command": "todo", "text": task}, "todo")
    # note
    for tmpl in NOTE_T:
        for n in NOTE_TEXTS:
            add(fill(tmpl, n=n), {"command": "note", "text": n}, "note")
    # grocery
    for tmpl in GROCERY_T:
        for g in GROCERIES:
            add(fill(tmpl, g=g), {"command": "grocery", "item": g}, "grocery")
    # grocery multi-item
    for _ in range(40):
        items = ", ".join(rng.sample(GROCERIES, rng.randint(2, 3)))
        tmpl = rng.choice(GROCERY_T)
        add(fill(tmpl, g=items), {"command": "grocery", "item": items}, "grocery")
    # event
    for tmpl in EVENT_T:
        for _ in range(8):
            title = rng.choice(EVENT_TITLES); date = rng.choice(DATES)
            nat, norm = rng.choice(TIMES)
            add(fill(tmpl, title=title, date=date, t=nat),
                {"command": "event", "date": date, "time": norm, "title": title}, "event")
    # list / no-arg forms
    for tmpl in TODOS_T:
        for _ in range(3): add(tmpl, {"command": "todos"}, "todos")
    for tmpl in NOTES_T:
        for _ in range(3): add(tmpl, {"command": "notes"}, "notes")
    for tmpl in GROCERIES_T:
        for _ in range(3): add(tmpl, {"command": "groceries"}, "groceries")
    for tmpl in TORCH_T:
        for _ in range(4): add(tmpl, {"command": "torch"}, "torch")
    for tmpl in CAMERA_T:
        for _ in range(4): add(tmpl, {"command": "camera"}, "camera")
    # negatives
    for neg in NEGATIVES:
        for _ in range(4): add(neg, {"command": "none"}, "none")
    # Incomplete command phrases and short fragments are a separate cluster so
    # the held-out split measures the model's ability to decline them.
    for neg in HARD_NEGATIVES:
        for _ in range(8): add(neg, {"command": "none"}, "hard_none")

    return rows

def dedup(rows):
    seen = set(); out = []
    for nl, d, cluster in rows:
        key = (nl.strip().lower(), json.dumps(d, sort_keys=True))
        if key in seen:
            continue
        seen.add(key); out.append((nl.strip(), d, cluster))
    return out

def to_chat(nl, out):
    return {"messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": nl},
        {"role": "assistant", "content": json.dumps(out, ensure_ascii=False)},
    ]}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=".")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--eval-frac", type=float, default=0.06)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    rows = dedup(build(rng))
    rng.shuffle(rows)

    # Stratified split by cluster so eval covers every command + negatives.
    by_cluster = {}
    for r in rows:
        by_cluster.setdefault(r[2], []).append(r)
    train, eval_ = [], []
    for cluster, items in by_cluster.items():
        n_eval = max(3, int(len(items) * args.eval_frac))
        eval_.extend(items[:n_eval]); train.extend(items[n_eval:])
    rng.shuffle(train); rng.shuffle(eval_)

    out_dir = Path(args.out_dir)
    with open(out_dir / "train.jsonl", "w") as f:
        for nl, d, _ in train:
            f.write(json.dumps(to_chat(nl, d), ensure_ascii=False) + "\n")
    with open(out_dir / "eval.jsonl", "w") as f:
        for nl, d, _ in eval_:
            f.write(json.dumps(to_chat(nl, d), ensure_ascii=False) + "\n")

    # Stats
    from collections import Counter
    tc = Counter(r[2] for r in train); ec = Counter(r[2] for r in eval_)
    print(f"TOTAL={len(rows)}  train={len(train)}  eval={len(eval_)}")
    print("per-command (train / eval):")
    for c in sorted(set(list(tc) + list(ec))):
        print(f"  {c:12s} {tc.get(c,0):4d} / {ec.get(c,0):3d}")

if __name__ == "__main__":
    main()
