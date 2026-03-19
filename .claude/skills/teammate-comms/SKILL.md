---
description: Teammate messaging system for agent-to-agent communication. Not user-invocable — agents call the scripts directly via Bash.
---

# Teammate Comms

File-based messaging system for agent-to-agent communication. The comms folder always lives in the **main repo** (not worktrees), so all agents can find it regardless of which worktree they're working in.

## How It Works

- Each agent has two JSON files: `_unread.json` (inbox) and `_read.json` (archive)
- Agents send messages by appending to the recipient's unread file
- Agents check their own inbox, process messages, then acknowledge (moves to read file)
- Messages have datetime-based IDs, sender info, and optional priority
- File operations use directory-based locking to prevent race conditions
- When `--team` is provided, inboxes are namespaced: `TeammateComms/<team>/inboxes/`

## Team Namespacing

When working in a team (e.g., via `tackle_issue`), **always pass `--team <TEAM-NAME>`** to all comms scripts. The team name is provided by the lead in the onboarding message.

This ensures each team's messages are isolated from other teams.

## Scripts

All scripts live in `.claude/skills/teammate-comms/scripts/`.

### setup.py — Register an agent

```bash
python .claude/skills/teammate-comms/scripts/setup.py --team <TEAM-NAME> --agent implementer
```

### send.py — Send a message

```bash
# Simple message
python .claude/skills/teammate-comms/scripts/send.py \
  --team <TEAM-NAME> \
  --from qa-validator \
  --to implementer \
  --message "Bug in CardWidget: null title not handled"

# Message with special characters (quotes, code, etc.) — use heredoc via stdin
python .claude/skills/teammate-comms/scripts/send.py \
  --team <TEAM-NAME> \
  --from qa-validator \
  --to implementer <<'EOF'
Bug in _buildHeader(): the "defaultValue" param isn't handled.
Fix: add `?? ''` fallback on line 45.
EOF
```

Options:
- `--team` (recommended): Team name for namespaced inboxes
- `--from` (required): Sender agent name
- `--to` (required): Recipient agent name
- `--message` (optional): Message content. If omitted, reads from stdin (use heredoc for messages with special characters)
- `--priority` (optional): `normal` (default) or `urgent`

**Note:** `send.py` auto-creates the recipient's inbox if it doesn't exist. No need to run `setup.py` for the recipient first.

### inbox.py — Check unread messages

```bash
# Full inbox
python .claude/skills/teammate-comms/scripts/inbox.py --team <TEAM-NAME> --agent implementer

# Count only (fast check for polling)
python .claude/skills/teammate-comms/scripts/inbox.py --team <TEAM-NAME> --agent implementer --count-only
```

### ack.py — Acknowledge messages

```bash
# Acknowledge a specific message
python .claude/skills/teammate-comms/scripts/ack.py --team <TEAM-NAME> --agent implementer --id "2026-03-08T12:34:05.123456"

# Acknowledge all unread messages
python .claude/skills/teammate-comms/scripts/ack.py --team <TEAM-NAME> --agent implementer --id all
```

### watch.py — Watch inbox for new messages (recommended)

Runs as a background process and exits when new messages arrive. Much more responsive than polling.

```bash
# Start as a background process (run_in_background=true in Bash tool)
python .claude/skills/teammate-comms/scripts/watch.py --team <TEAM-NAME> --agent implementer
```

**How it works:**
1. Checks for existing unread messages — if any, prints them and exits immediately
2. Watches the inbox file for changes (polls mtime every 500ms)
3. When a new message arrives, prints all unread messages and exits
4. Auto-exits after 90 seconds if no messages arrive (stays within Bash tool timeout limits)
5. Agent gets notified, processes messages (or sees "no new messages"), then restarts the watcher

**Output:** Same format as `inbox.py`. If no messages arrive within 90 seconds, prints "No new messages." with a restart reminder.

## Discovering Other Agents

```bash
ls <MAIN-REPO>/TeammateComms/<TEAM-NAME>/inboxes/*_unread.json
```

## Agent Inbox Check Protocol (MANDATORY)

Every agent **must** run the inbox watcher loop. This is not optional.

### On startup:
1. Run `setup.py --team <TEAM-NAME> --agent <NAME>` to register your inbox
2. Immediately start `watch.py --team <TEAM-NAME> --agent <NAME>` with `run_in_background=true, timeout: 120000`

### When the watcher notifies you:

**If messages received:**
1. Process the messages from the watcher output
2. Run `ack.py --team <TEAM-NAME> --agent <NAME> --id all` to clear inbox
3. Restart `watch.py` with `run_in_background=true, timeout: 120000`

**If "No new messages" (90-second cycle expired):**
1. Restart `watch.py` immediately — the watcher auto-cycles every 90 seconds to stay within Bash timeout limits

### Waking up idle agents:
If you send a message to a teammate who may be idle (finished their work and waiting), also send them a short `SendMessage` to wake them up:
1. Send your message via `send.py` (the real message goes to their inbox)
2. Use `SendMessage` to the teammate: "Check your inbox — you have a new message"
3. This triggers a new turn for the idle agent, who will then see the watcher output or check `inbox.py`

**Note:** `SendMessage` is unreliable for actual message content — that's why the real message goes via `send.py`. The `SendMessage` is just a nudge to wake the agent up.

### Important:
- The watcher auto-exits every 90 seconds to prevent Bash timeout kills
- `setup.py`, `send.py`, and `ack.py` all print reminders to keep your watcher running
- **Always restart the watcher after it exits** — you will miss messages if you don't
- Always set `timeout: 120000` on the Bash call to give the 90-second cycle headroom
