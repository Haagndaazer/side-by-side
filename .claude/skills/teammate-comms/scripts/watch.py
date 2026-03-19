"""Watch an agent's inbox for new messages. Exits when messages arrive."""

import argparse
import sys
import time
from pathlib import Path

# Add scripts dir to path for common import
sys.path.insert(0, str(Path(__file__).parent))
from common import get_inboxes_dir, validate_agent_name, read_json_safe


def main():
    parser = argparse.ArgumentParser(description="Watch inbox for new messages (one-shot)")
    parser.add_argument("--agent", required=True, help="Agent name to watch")
    parser.add_argument("--team", default=None, help="Team name for namespaced inboxes")
    args = parser.parse_args()

    validate_agent_name(args.agent)

    inboxes_dir = get_inboxes_dir(args.team)
    unread_file = inboxes_dir / f"{args.agent}_unread.json"

    if not unread_file.exists():
        print(f"Error: No inbox found for '{args.agent}'.", file=sys.stderr)
        sys.exit(1)

    # Check for existing unread messages first
    messages = read_json_safe(unread_file)
    if messages:
        _print_messages(args.agent, messages)
        return

    # Record current mtime and poll for changes
    # Auto-exit after 90 seconds to stay within Bash tool timeout limits
    last_mtime = unread_file.stat().st_mtime
    start = time.time()
    cycle_limit = 90

    while True:
        time.sleep(0.5)

        if (time.time() - start) > cycle_limit:
            team_flag = f" --team {args.team}" if args.team else ""
            print("No new messages.")
            print(f">> Restart your inbox watcher: watch.py{team_flag} --agent {args.agent}")
            return

        try:
            current_mtime = unread_file.stat().st_mtime
        except OSError:
            continue

        if current_mtime != last_mtime:
            messages = read_json_safe(unread_file)
            if messages:
                _print_messages(args.agent, messages)
                return
            # File changed but no messages (e.g., ack cleared it) — keep watching
            last_mtime = current_mtime


def _print_messages(agent, messages):
    """Print messages in the same format as inbox.py."""
    print(f"=== {len(messages)} unread message(s) for {agent} ===\n")
    for msg in messages:
        priority_tag = " [URGENT]" if msg.get("priority") == "urgent" else ""
        print(f"--- ID: {msg['id']} | From: {msg['from']}{priority_tag} ---")
        print(msg["message"])
        print()


if __name__ == "__main__":
    main()
