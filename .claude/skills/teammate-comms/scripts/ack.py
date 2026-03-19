"""Acknowledge a message: move it from unread to read inbox."""

import argparse
import json
import sys
from pathlib import Path

# Add scripts dir to path for common import
sys.path.insert(0, str(Path(__file__).parent))
from common import get_inboxes_dir, validate_agent_name, ensure_inbox, read_json_safe, file_lock


def main():
    parser = argparse.ArgumentParser(description="Acknowledge a message (move from unread to read)")
    parser.add_argument("--agent", required=True, help="Agent name")
    parser.add_argument("--id", dest="msg_id", required=True, help="Message ID (datetime) to acknowledge, or 'all'")
    parser.add_argument("--team", default=None, help="Team name for namespaced inboxes")
    args = parser.parse_args()

    validate_agent_name(args.agent)

    inboxes_dir = get_inboxes_dir(args.team)
    ensure_inbox(inboxes_dir, args.agent)
    unread_file = inboxes_dir / f"{args.agent}_unread.json"
    read_file = inboxes_dir / f"{args.agent}_read.json"

    with file_lock(unread_file):
        unread = read_json_safe(unread_file)
        read = read_json_safe(read_file)

        if not unread:
            print("No unread messages to acknowledge.")
            return

        if args.msg_id == "all":
            read.extend(unread)
            acked_count = len(unread)
            unread = []
            print(f"Acknowledged all {acked_count} message(s).")
        else:
            to_ack = None
            remaining = []
            for msg in unread:
                if msg["id"] == args.msg_id:
                    to_ack = msg
                else:
                    remaining.append(msg)

            if to_ack is None:
                print(f"Error: No unread message with ID '{args.msg_id}'.", file=sys.stderr)
                print("Available message IDs:", file=sys.stderr)
                for msg in unread:
                    print(f"  - {msg['id']} (from: {msg['from']})", file=sys.stderr)
                sys.exit(1)

            read.append(to_ack)
            unread = remaining
            print(f"Acknowledged message {args.msg_id} from {to_ack['from']}.")

        with open(unread_file, "w", encoding="utf-8") as f:
            json.dump(unread, f, indent=2, ensure_ascii=False)

        with open(read_file, "w", encoding="utf-8") as f:
            json.dump(read, f, indent=2, ensure_ascii=False)

    # Remind agent to restart their inbox watcher
    team_flag = f" --team {args.team}" if args.team else ""
    print(f">> Restart your inbox watcher: watch.py{team_flag} --agent {args.agent}")


if __name__ == "__main__":
    main()
