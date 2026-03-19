#!/usr/bin/env python3
import json
import os
import sys

# Fix Windows encoding for emojis
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')

# Constant
CONTEXT_LIMIT = int(1000000 * 0.78)  # CC triggers /compact at ~78% context utilization

# Read JSON from stdin
data = json.load(sys.stdin)

# Extract values
model = data["model"]["display_name"]
current_dir = os.path.basename(data["workspace"]["current_dir"])

# Check for git branch
git_branch = ""
if os.path.exists(".git"):
    try:
        with open(os.path.join(".git", "HEAD"), "r") as f:
            ref = f.read().strip()
            if ref.startswith("ref: refs/heads/"):
                git_branch = f" |⚡️ {ref.replace('ref: refs/heads/', '')}"
    except Exception:
        pass


transcript_path = data["transcript_path"]

# Parse transcript file to calculate context usage
context_used_token = 0

try:
    with open(transcript_path, "r") as f:
        lines = f.readlines()

        # Iterate from last line to first line
        for line in reversed(lines):
            line = line.strip()
            if not line:
                continue

            try:
                obj = json.loads(line)
                # Check if this line contains the required token usage fields
                if (
                    obj.get("type") == "assistant"
                    and "message" in obj
                    and "usage" in obj["message"]
                    and all(
                        key in obj["message"]["usage"]
                        for key in [
                            "input_tokens",
                            "cache_creation_input_tokens",
                            "cache_read_input_tokens",
                            "output_tokens",
                        ]
                    )
                ):
                    usage = obj["message"]["usage"]
                    input_tokens = usage["input_tokens"]
                    cache_creation_input_tokens = usage["cache_creation_input_tokens"]
                    cache_read_input_tokens = usage["cache_read_input_tokens"]
                    output_tokens = usage["output_tokens"]

                    context_used_token = (
                        input_tokens
                        + cache_creation_input_tokens
                        + cache_read_input_tokens
                        + output_tokens
                    )
                    break  # Break after finding the first occurrence

            except json.JSONDecodeError:
                # Skip malformed JSON lines
                continue

except FileNotFoundError:
    # If transcript file doesn't exist, keep context_used_token as 0
    pass

context_used_rate = (context_used_token / CONTEXT_LIMIT) * 100

# Create progress bar
bar_length = 20
filled_length = int(bar_length * context_used_token // CONTEXT_LIMIT)
bar = "█" * filled_length + "░" * (bar_length - filled_length)
context_usage = f" | [{bar}] {context_used_rate:.1f}% ({context_used_token:,})"

print(f"[{model}] 📁 {current_dir}{git_branch}{context_usage}")