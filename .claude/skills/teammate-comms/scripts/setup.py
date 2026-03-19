"""Register a single agent's inbox in TeammateComms."""

import argparse
import sys
from pathlib import Path

# Add scripts dir to path for common import
sys.path.insert(0, str(Path(__file__).parent))
from common import get_inboxes_dir, validate_agent_name, ensure_inbox


def main():
    parser = argparse.ArgumentParser(description="Register an agent's inbox")
    parser.add_argument("--agent", required=True, help="Agent name (e.g., 'implementer', 'qa-validator-2')")
    parser.add_argument("--team", default=None, help="Team name for namespaced inboxes")
    args = parser.parse_args()

    validate_agent_name(args.agent)

    inboxes_dir = get_inboxes_dir(args.team)
    ensure_inbox(inboxes_dir, args.agent)
    print(f"Agent '{args.agent}' registered in {inboxes_dir}")

    # Remind agent to start their inbox watcher
    team_flag = f" --team {args.team}" if args.team else ""
    print(f">> Start your inbox watcher now: watch.py{team_flag} --agent {args.agent}")


if __name__ == "__main__":
    main()
