---
name: commit-skill
description: Use this any time a git commit is needed or requested. Never try to do a git commit without using this skill.
---

## CRITICAL: Subagent Required

**NEVER run `git status`, `git diff`, or analyze changes yourself.**

You lack the context to analyze diffs. The diff analyzer subagent has specialized instructions you do not have access to. Attempting analysis yourself will produce incorrect results.

### Prohibited Actions
- Running `git status` or `git diff`
- Reading file contents to understand changes
- Deciding commit groupings
- Writing commit messages

### Your ONLY Job
1. Review the conversation history and understand what was accomplished
2. Spawn the subagent and tell it what you were working on to give it context of the conversation history.
3. Parse its JSON response
4. Execute submodule commits first, then main repo commits OR ask user (if flagged)

---

## Step 1: Spawn Diff Analyzer (MANDATORY - DO THIS FIRST)

Invoke the Task tool IMMEDIATELY:

- `subagent_type`: `"general-purpose"`
- `description`: `"Analyze git changes"`
- `prompt`: Read `.claude/skills/commit-skill/DIFF_ANALYZER.md` and pass its ENTIRE contents + a summary of what you were working on.

DO NOT proceed to Step 2 until you have received the subagent response.

---

## Step 2: Parse Response

The subagent returns JSON:
```json
{
  "submodule_commits": [
    {
      "path": "<submodule-path>",
      "commits": [{"files": [...], "message": "..."}]
    }
  ],
  "commits": [{"files": [...], "message": "..."}],
  "flags": null | {"reason": "...", "detail": "..."}
}
```

---

## Step 3: Execute or Clarify

**If `flags` is not null**: Ask user about the flagged concern before proceeding.

**If `flags` is null**: Execute in this order:

### 3a. Commit Submodules First

For each entry in `submodule_commits`:
1. `cd` into the submodule path (relative to main repo root)
2. For each commit in that submodule's `commits` array:
   - `git add <files>` (file paths are relative to the submodule root)
   - `git commit -m "<message>"` (use HEREDOC for multi-line)
3. `git push` the submodule so the remote has the new commits
4. `cd` back to the main repo root

### 3b. Commit Main Repo

For each commit in `commits`:
1. `git add <files>` (explicit file list — this includes any submodule pointer paths)
2. `git commit -m "<message>"` (use HEREDOC for multi-line)

### 3c. Verify

After all commits: `git log --oneline -n <count>` in the main repo.

---

## Rules
- No co-author/AI attribution in commits
- Include all valid files (respect .gitignore)
- One commit by default, split only if clearly unrelated
- Submodule changes MUST be committed inside the submodule first, then the updated pointer committed in the parent repo
- Always push submodules after committing so the parent repo pointer references a publicly available commit
