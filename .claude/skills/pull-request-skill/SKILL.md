---
name: pull-request-skill
description: Use this any time a PR (pull request) is needed or requested. Always use this skill to create pull requests.
---

## Pull Request Creation

This skill creates a GitHub pull request for the current branch. No subagent is needed — commits have already been made via the commit skill, so we just summarize and create the PR.

### Your Job
1. Review the conversation history and Linear issue worked on to understand what was accomplished
2. Gather branch context
3. Craft the PR title and body
4. Create the PR

---

## Step 1: Gather Branch Context

Run these commands to understand the branch state:

1. `git branch --show-current` — confirm we're not on `main`
2. `git log main..HEAD --oneline` — see all commits on this branch
3. `git rev-list --count main..HEAD` — count commits ahead of main
4. `gh pr list --head $(git branch --show-current) --state open` — check if a PR already exists

### Stop Conditions
- **On `main` branch**: Tell the user they need to be on a feature branch. Do not proceed.
- **No commits ahead of main**: Tell the user there are no changes to open a PR for. Do not proceed.
- **PR already exists**: Tell the user a PR already exists, provide the URL from `gh pr view --web`, and ask if they want to update it.

---

## Step 2: Push Branch to Remote

Check if the branch has a remote tracking branch:

1. `git rev-parse --abbrev-ref --symbolic-full-name @{u}` — if this fails, the branch isn't pushed yet
2. If not pushed: `git push -u origin $(git branch --show-current)`
3. If already pushed: `git push` to ensure remote is up to date

---

## Step 3: Craft PR Title and Body

Using the Linear issue and conversation history and the commit log from Step 1:

- **Title**: Short (under 72 chars), starts with the Linear issue number, imperative mood, describes the overall change
- **Body**: Use this structure:

```markdown
## Summary
- Bullet point summarizing each logical change

## Test plan
- Steps to verify the changes work correctly
```

---

## Step 4: Create the PR

Run:
```
gh pr create --title "<title>" --body "$(cat <<'EOF'
## Summary
<bullets>

## Test plan
<bullets>
EOF
)"
```

After creation, output the PR URL so the user can see it.

---

## Rules
- **No AI attribution** — no "Generated with Claude", no "Co-Authored-By" lines, no AI mentions anywhere in the PR
- Write the PR title and body as if the user wrote them
- Always target `main` as the base branch unless the user specifies otherwise
- If the user provides a specific title or description, use it exactly
