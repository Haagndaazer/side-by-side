"""Check a teammate's unread inbox."""

import argparse
import sys
from pathlib import Path

# Add scripts dir to path for common import
sys.path.insert(0, str(Path(__file__).parent))
from common import get_inboxes_dir, validate_agent_name, read_json_safe


def main():
    parser = argparse.ArgumentParser(description="Check unread messages for a teammate")
    parser.add_argument("--agent", required=True, help="Agent name to check inbox for")
    parser.add_argument("--count-only", action="store_true", help="Only show unread count")
    parser.add_argument("--team", default=None, help="Team name for namespaced inboxes")
    args = parser.parse_args()

    validate_agent_name(args.agent)

    inboxes_dir = get_inboxes_dir(args.team)
    unread_file = inboxes_dir / f"{args.agent}_unread.json"

    if not unread_file.exists():
        print(f"Error: No inbox found for '{args.agent}'.", file=sys.stderr)
        sys.exit(1)

    messages = read_json_safe(unread_file)

    if args.count_only:
        print(len(messages))
        return

    if not messages:
        print("No unread messages.")
        return

    print(f"=== {len(messages)} unread message(s) for {args.agent} ===\n")
    for msg in messages:
        priority_tag = " [URGENT]" if msg.get("priority") == "urgent" else ""
        print(f"--- ID: {msg['id']} | From: {msg['from']}{priority_tag} ---")
        print(msg["message"])
        print()


if __name__ == "__main__":
    main()
