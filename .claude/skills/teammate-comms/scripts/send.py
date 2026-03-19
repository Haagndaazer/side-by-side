"""Send a message to a teammate's unread inbox."""

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

# Add scripts dir to path for common import
sys.path.insert(0, str(Path(__file__).parent))
from common import get_inboxes_dir, validate_agent_name, ensure_inbox, read_json_safe, file_lock


def main():
    parser = argparse.ArgumentParser(description="Send a message to a teammate's inbox")
    parser.add_argument("--from", dest="sender", required=True, help="Sender agent name")
    parser.add_argument("--to", required=True, help="Recipient agent name")
    parser.add_argument("--message", default=None, help="Message content (or pipe via stdin)")
    parser.add_argument("--priority", default="normal", choices=["normal", "urgent"], help="Message priority")
    parser.add_argument("--team", default=None, help="Team name for namespaced inboxes")
    args = parser.parse_args()

    validate_agent_name(args.sender)
    validate_agent_name(args.to)

    # Read message from --message or stdin
    if args.message is not None:
        content = args.message.strip()
    elif not sys.stdin.isatty():
        content = sys.stdin.read().strip()
    else:
        print("Error: Provide --message or pipe content via stdin.", file=sys.stderr)
        sys.exit(1)

    if not content:
        print("Error: Message content cannot be empty.", file=sys.stderr)
        sys.exit(1)

    inboxes_dir = get_inboxes_dir(args.team)
    ensure_inbox(inboxes_dir, args.to)
    unread_file = inboxes_dir / f"{args.to}_unread.json"

    message = {
        "id": datetime.now().strftime("%Y-%m-%dT%H:%M:%S.%f"),
        "from": args.sender,
        "priority": args.priority,
        "message": content,
    }

    with file_lock(unread_file):
        messages = read_json_safe(unread_file)
        messages.append(message)
        with open(unread_file, "w", encoding="utf-8") as f:
            json.dump(messages, f, indent=2, ensure_ascii=False)

    print(f"Message sent to {args.to} (id: {message['id']})")

    # Remind sender to ensure their inbox watcher is running
    team_flag = f" --team {args.team}" if args.team else ""
    print(f">> Ensure your inbox watcher is running: watch.py{team_flag} --agent {args.sender}")


if __name__ == "__main__":
    main()
