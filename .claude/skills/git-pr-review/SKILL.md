---
name: git-pr-review
description: Use this skill to review and process all open Git PRs (pull requests) sequentially. Analyzes diffs, checks for conflicts and issues, then approves and merges clean PRs or requests changes on problematic ones. Never try to review git pull requests without using this skill.
---

## CRITICAL: Subagent Required Per PR

**NEVER analyze PR diffs yourself.**

Each PR must be reviewed by a fresh subagent to avoid context contamination between reviews. The PR reviewer subagent has specialized review instructions you do not have access to.

### Prohibited Actions
- Running `gh pr diff` or reading PR diffs yourself
- Deciding whether a PR should be approved or rejected

### Your ONLY Job
1. Fetch the list of open PRs
2. For each PR, spawn a reviewer subagent
3. Parse its JSON response
4. Execute the verdict (approve+merge, comment with concerns, or skip)
5. After all PRs are processed, summarize results to the user

---

## Step 1: Fetch Open PRs (MANDATORY FIRST STEP)

Run:
```bash
gh pr list --state open --json number,title,author,isDraft,createdAt --jq 'sort_by(.createdAt)'
```

### Stop Conditions
- **No open PRs**: Tell the user there are no open PRs to review. Do not proceed.
- **Not authenticated**: If `gh auth status` fails, tell the user to run `gh auth login`.

Tell the user how many PRs will be reviewed.

---

## Step 2: Process Each PR Sequentially (oldest first)

### 2a. Skip Drafts
If `isDraft` is true, record as "skipped (draft)" and move to the next PR. Do not spawn a subagent.

### 2b. Spawn PR Reviewer Subagent

Invoke the Task tool:

- `subagent_type`: `"Bash"`
- `description`: `"Review PR #<number>"`
- `prompt`: Read `.claude/skills/git-pr-review/PR_REVIEWER.md` and pass its ENTIRE contents, plus the PR number, title, and author.

DO NOT proceed to step 2c until you have received the subagent response.

### 2c. Parse Response

The subagent returns JSON:
```json
{
  "pr_number": 42,
  "title": "...",
  "author": "...",
  "verdict": "approve" | "request_changes" | "skip",
  "reasons": ["..."],
  "concerns": [{"type": "...", "detail": "..."}],
  "review_body": "..."
}
```

### 2d. Execute Verdict

**If verdict is `"approve"`:**
1. `gh pr review <number> --approve`
2. `gh pr merge <number> --delete-branch` (uses repo default merge strategy, deletes branch after merge)
3. Record: "PR #<number> — APPROVED, MERGED, and BRANCH DELETED"

**If verdict is `"request_changes"`:**
1. `gh pr comment <number> --body "<review_body>"` (use HEREDOC for multi-line)
2. Record: "PR #<number> — CHANGES REQUESTED (comment left)"

**If verdict is `"skip"`:**
1. Record: "PR #<number> — SKIPPED: <reason>"

If `gh pr merge` fails (branch protection, required reviews, etc.), record the failure reason and move on. Do not retry or force.

If the PR is approved and merged, mark the Linear issue as status 'Done'.

### 2e. Move to Next PR

---

## Step 3: Summary

After all PRs are processed, present a summary:

```
PR Review Summary
=================
Total: X

Approved & Merged:
  - PR #12: Add login screen
  - PR #15: Fix navigation bug

Changes Requested:
  - PR #18: Update API keys — Exposed API key in config

Skipped:
  - PR #20: WIP refactor (draft)

Failed to Merge:
  - PR #22: Add tests — Branch protection requires 2 approvals
```

## Step 4: Pull latest

Now that you are done, pull latest from git so that this local main branch of the repo is fully up to date with the latest PRs.

## Step 5: Update Vibe Ragnar Indexes

In order to ensure that the project RAG search is accurate to the latest changes, trigger a re-indexing of the files modified in the most recent pull using the vibe-ragnar mcp server.

---

## Rules
- No AI attribution — no "Generated with Claude", no co-author, no AI mentions in review comments
- Write review comments as if the user wrote them
- Never force-merge or override branch protection rules
- Process PRs oldest-first
- If merge fails, record failure and move on
