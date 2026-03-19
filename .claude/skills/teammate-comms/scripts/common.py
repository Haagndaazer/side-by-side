"""Shared utilities for teammate-comms scripts."""

import json
import os
import re
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

# Valid agent name pattern: alphanumeric, hyphens, underscores, dots
AGENT_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$")


def get_main_repo_root():
    """Get the main repo root (not worktree root) using git-common-dir."""
    result = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print("Error: Not in a git repository.", file=sys.stderr)
        sys.exit(1)
    git_dir = Path(result.stdout.strip())
    # --git-common-dir returns <main-repo>/.git — go up one level
    return git_dir.parent


def get_inboxes_dir(team=None):
    """Get the TeammateComms/inboxes directory, always in the main repo.

    If team is provided: <main-repo>/TeammateComms/<team>/inboxes/
    If no team:          <main-repo>/TeammateComms/inboxes/
    """
    root = get_main_repo_root()
    if team:
        return root / "TeammateComms" / team / "inboxes"
    return root / "TeammateComms" / "inboxes"


def validate_agent_name(name):
    """Validate agent name to prevent path traversal and invalid filenames."""
    if not AGENT_NAME_PATTERN.match(name) or ".." in name:
        print(f"Error: Invalid agent name '{name}'. Use alphanumeric, hyphens, underscores, dots only.", file=sys.stderr)
        sys.exit(1)


def ensure_inbox(inboxes_dir, agent):
    """Create inbox files for an agent if they don't exist."""
    inboxes_dir.mkdir(parents=True, exist_ok=True)
    for suffix in ["_unread.json", "_read.json"]:
        filepath = inboxes_dir / f"{agent}{suffix}"
        if not filepath.exists():
            with open(filepath, "w", encoding="utf-8") as f:
                json.dump([], f)


def read_json_safe(filepath):
    """Read a JSON file with error recovery."""
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, ValueError):
        print(f"Warning: Corrupted JSON in {filepath.name}, resetting to empty.", file=sys.stderr)
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump([], f)
        return []


@contextmanager
def file_lock(lock_path, timeout=10):
    """Simple cross-platform file lock using mkdir (atomic on all OSes)."""
    lock_dir = Path(str(lock_path) + ".lock")
    start = time.time()
    while True:
        try:
            lock_dir.mkdir(parents=False, exist_ok=False)
            break
        except FileExistsError:
            if time.time() - start > timeout:
                # Stale lock — force remove and retry once
                try:
                    lock_dir.rmdir()
                except OSError:
                    pass
                try:
                    lock_dir.mkdir(parents=False, exist_ok=False)
                    break
                except FileExistsError:
                    print(f"Error: Could not acquire lock on {lock_path.name} after {timeout}s.", file=sys.stderr)
                    sys.exit(1)
            time.sleep(0.05)
    try:
        yield
    finally:
        try:
            lock_dir.rmdir()
        except OSError:
            pass
