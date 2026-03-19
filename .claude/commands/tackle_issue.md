# Tackle Issue

End-to-end command that takes a Linear issue from planning through to a merged PR. You are the **team lead** orchestrating this entire process.

## Parameters

- `$ARGUMENTS` — A Linear issue identifier (e.g., ACR-42)

---

## IRON RULE: The Lead NEVER Does Work — Only Orchestrates

You are the orchestrator. Your job is to delegate, coordinate, and communicate. Your context window stays clean.

- **NEVER** read source files or investigate the codebase yourself — delegate to **planner**
- **NEVER** edit source files yourself (`lib/`, `test/`, or any project code) — delegate to **implementer** or **ui-designer**
- **NEVER** run `flutter analyze` yourself — delegate to **qa-validator**
- **NEVER** fix bugs or make code changes — message the **implementer** or **ui-designer** with what to fix
- **NEVER** write tests — delegate to **unit-test-writer**
- **NEVER** review PR diffs — delegate to **pr-reviewer**
- **NEVER** spawn research subagents (codebase-analyzer, etc.) — the **planner** does that
- If a teammate reports an issue, your response is **ALWAYS** to message another teammate to handle it
- The ONLY tools you should use are: `Bash` (for comms scripts and workspace setup), `SendMessage` (for pr-reviewer and for waking up idle teammates), Linear MCP tools (for tracking), Task tools (for managing the team), and `Skill` tool (for `flutter-run`, `commit-skill`, and `pull-request-skill` — these are lead-only responsibilities)
- **NEVER use `SendMessage` for actual message content to non-pr-reviewer teammates** — use `send.py` via Bash instead. You may use `SendMessage` as a short wake-up nudge ("Check your inbox") to poke idle teammates after sending them a message via `send.py`.
- When in doubt: **delegate, don't do**

---

## Teammate Comms System

This workflow uses a file-based messaging system for reliable agent-to-agent communication. See `.claude/skills/teammate-comms/SKILL.md` for full details.

### Communication Rules

| Direction | Method |
|---|---|
| Team-Lead → Teammate | `send.py` to teammate inbox |
| Teammate → Team-Lead | `send.py` to `team-lead`'s inbox |
| Teammate → Teammate | `send.py` to recipient's inbox |

## Step 0: Workspace Setup

1. If no Linear issue ID was provided in `$ARGUMENTS`, ask for one and stop.
2. Read the Linear issue using `mcp__linear__get_issue` with `includeRelations: true`.
3. Ensure the worktree parent directory exists:
   ```
   mkdir -p C:\Users\colto\Documents\Projects\Worktrees\linglang
   ```
4. Create a git worktree for isolated work:
   ```
   git worktree add "C:\Users\colto\Documents\Projects\Worktrees\linglang\<ISSUE-ID>" -b <ISSUE-ID>
   ```
   Replace `<ISSUE-ID>` with the Linear issue identifier (e.g., `ACR-42`).
5. **Initialize submodules** in the new worktree:
   ```
   cd "C:\Users\colto\Documents\Projects\Worktrees\linglang\<ISSUE-ID>" && git submodule update --init --recursive
   ```
6. **Register the lead's inbox** (your comms agent name is `team-lead`):
   ```
   python .claude/skills/teammate-comms/scripts/setup.py --team <ISSUE-ID> --agent team-lead
   ```
   Use the issue ID as the team name (e.g., `--team LL-287`).
7. **Start your inbox watcher** as a background process:
   ```
   python .claude/skills/teammate-comms/scripts/watch.py --team <ISSUE-ID> --agent team-lead
   ```
   Run this with `run_in_background=true`. When it exits, you'll be notified with new messages. Process them, ack, then restart the watcher. See `.claude/skills/teammate-comms/SKILL.md` for the full protocol.
8. **All subsequent work happens in the worktree directory**: `C:\Users\colto\Documents\Projects\Worktrees\linglang\<ISSUE-ID>`

### Teammate Onboarding Message

When messaging any new teammate (except pr-reviewer), include:

> Your agent name is `<AGENT-NAME>`. The lead's comms name is `team-lead`. You are working in the worktree at `C:\Users\colto\Documents\Projects\Worktrees\linglang\<ISSUE-ID>`. **Always pass `--team <ISSUE-ID>` to all comms scripts.** Read `.claude/skills/teammate-comms/SKILL.md` for messaging details.

---

## Step 1: Research & Plan (Delegated to planner teammate)

> **Remember the Iron Rule: you do NOT do the research or planning yourself. Delegate to the planner.**

1. **Spawn the team** using the Teammate tool with `spawnTeam` (if another team was already present, force shutdown the existing team and create a new team)
2. **Spawn the planner** — `subagent_type: "teammate-planner"` with `team_name`
3. **Message the planner** with:
   - The Linear issue ID
   - Any context the user provided
   - Any constraints or preferences you already know
   - The plan must account for the on-device testing strategy
   - The **Teammate Onboarding Message** (see above) with agent name `planner`
4. **Wait for your inbox watcher** to notify you of planner updates (the planner sends questions and findings via `send.py`)
5. **Relay questions to the user** — present the planner's questions and get answers
6. **Relay answers back to the planner** — message the planner with the user's decisions
7. **Repeat** steps 4-6 until the planner has no more questions
8. **Receive the plan outline** — the planner writes the full plan to `tempPlan.md` in the worktree root (`C:\Users\colto\Documents\Projects\Worktrees\linglang\<ISSUE-ID>\tempPlan.md`) and sends you a summary via `send.py`
9. **Plan Review** — spawn **code-reviewer** and **code-simplifier** agents in parallel to review the plan:
   - Tell both agents to review `tempPlan.md` for: guideline compliance, architectural issues, over-engineering, reuse opportunities, and missing cross-cutting system considerations
   - Collect their suggestions
   - **Message the planner** with the combined feedback to incorporate
   - Wait for the planner to update `tempPlan.md`
10. **Present the reviewed plan to the user** — summarize the plan and note that the full document is at `tempPlan.md`. Get user feedback.
11. **Relay feedback to the planner** — the planner writes the final plan to Linear only once the user has approved the final plan. (parent issue + sub-issues for each phase, tagged as UI or Logic)

### Your Role in This Step

You are a **relay** between the planner, review agents, and the user:
- Planner sends you updates via `send.py` → you check your inbox → you present them to the user
- User answers → you message the planner with the answers
- Planner writes plan to `tempPlan.md` → you spawn **code-reviewer** and **code-simplifier** to review it
- Review agents send suggestions → you relay combined feedback to the planner
- Planner updates plan → you present the reviewed plan to the user for final approval
- User gives feedback → you relay to planner
- **NEVER** read source files, spawn research agents, or do codebase investigation yourself

### GATE: User Approval Required

**Do NOT proceed past this step until the user explicitly approves the plan.**

---

## Step 2: Spawn Team & Implement

Once the user approves:

> **Remember the Iron Rule: you delegate ALL implementation work. You NEVER write code yourself.**

1. **Create tasks** in the task list for each phase from the plan (use the team created in Step 1 — do NOT create a second team)
2. **Spawn teammates** using the Agent tool with `team_name`:
   - **implementer** — `subagent_type: "teammate-implementer"` — implements logic/service phases in `lib/`
   - **ui-designer** — `subagent_type: "teammate-ui-designer"` — implements UI/presentation phases in `lib/`
   - **unit-test-writer** — `subagent_type: "teammate-unit-test-writer"` — writes tests staggered behind both implementer and ui-designer in `test/`
3. **Send each teammate the Teammate Onboarding Message** (via `send.py`) with their assigned agent name and their phase assignments
4. **Assign phases to the right teammate**:
   - UI phases → message **ui-designer**
   - Logic phases → message **implementer**
   - Both can work in parallel on independent phases
5. **Monitor the staggered pipeline via your inbox**:
   - Implementer/ui-designer completes Phase N → sends message to unit-test-writer AND lead via `send.py`
   - Unit-test-writer writes Tests N while the next phase begins
   - Track progress via task list and your inbox
6. **Update Linear sub-issue statuses** as phases complete

### If a teammate reports being blocked
- You'll see the blocker in your inbox
- Assess the blocker
- Make a decision or consult the user if it's a significant deviation
- **Message the appropriate teammate** with the resolution — do NOT fix it yourself

### If a teammate reports a bug or issue
- **ALWAYS delegate the fix** to the appropriate teammate (implementer for logic, ui-designer for UI)
- Never attempt to fix code yourself, no matter how small the change seems

### Wait for completion
- All implementation phases done (implementer and ui-designer confirm via your inbox)
- All unit tests written and passing (unit-test-writer confirms via your inbox)

---

## Step 3: Validate

After all implementation and tests are complete, you are entrusted with validating thoroughly the resulting changes your team has completed:

1. **Launch the app** using the `flutter-run` skill (invoke via Skill tool) so the QA tester has a running app to interact with. Wait for the app to fully launch before proceeding.
2. **Spawn the QA validator** — `subagent_type: "teammate-qa-validator"` with `team_name`
3. **Send the Teammate Onboarding Message** (via `send.py`) with agent name `qa-validator`, then message the **qa-validator** to begin validation, providing:
   - The Linear issue ID
   - A summary of all changes made
   - Success criteria from the plan phases
4. The QA validator will:
   - Run `flutter analyze` and send a summary to your inbox via `send.py`
   - If errors: **you message the implementer or ui-designer** to fix (never fix yourself), then tell **qa-validator** to re-run
   - Run QA testing on the emulator and send results to your inbox
5. **Review the QA summary** from your inbox

### If App Launch Fails

If the `flutter-run` skill fails or the app doesn't start:
1. **Do NOT debug it yourself** — report the error to the user immediately
2. Include: the exact error message, which skill/command failed, and the worktree path
3. **Do NOT proceed to QA testing** — the app must be running first
4. If the user resolves the issue, retry the `flutter-run` skill before continuing

### Fix Cycle (if needed)
- Message **implementer** or **ui-designer** (whichever is appropriate) with specific issues to fix
- Message **unit-test-writer** to update/add tests for the fixes
- Message **qa-validator** to re-validate
- Your inbox watcher will notify you of updates — process, ack, and restart the watcher
- Repeat until all validation passes

---

## Step 4: Ship

Once validation passes:

1. **Commit** using the `commit-skill` (invoke via Skill tool)
2. **Push and create PR** using the `pull-request-skill` (invoke via Skill tool)
3. **Update Linear issue** to "In Review" using `mcp__linear__update_issue`

---

## Step 5: PR Review & Merge

After the PR is created:

1. **Spawn the PR reviewer** — `subagent_type: "teammate-pr-reviewer"` with `team_name`
   - The pr-reviewer works from the **main project directory** (`C:\Users\colto\Documents\Projects\LingLang`), NOT the worktree
2. **Send the Teammate Onboarding Message** (via `send.py`) with agent name `pr-reviewer`, then message them with the PR number and a brief summary of what was changed
3. The pr-reviewer has **full autonomy** to:
   - Analyze the diff (spawns its own Bash subagent with PR_REVIEWER.md)
   - Approve and merge the PR
   - Delete the branch after merge
   - Update Linear to "Done"
   - OR request changes if issues are found

### If pr-reviewer approves and merges:
- Send `shutdown_request` to all teammates (implementer, ui-designer, unit-test-writer, qa-validator, pr-reviewer)
- Clean up the team via Teammate tool `cleanup`
- Close the Flutter connection to the emulator:
  ```
  adb -s <DEVICE-ID> forward --remove-all
  ```
  Use `adb devices` to find the emulator device ID (e.g., `emulator-5554`). If unsure which device, run `adb forward --list` to see active forwards.
- Remove the worktree:
  ```
  git worktree remove "C:\Users\colto\Documents\Projects\Worktrees\linglang\<ISSUE-ID>"
  ```
- Delete the local branch if needed:
  ```
  git branch -d <ISSUE-ID>
  ```
- Report to the user that the issue is complete
- Delete the agent team using the `TeamDelete` tool

### If pr-reviewer requests changes:
- Read the concerns the pr-reviewer reported (via your inbox)
- **Re-brief the teammate** before assigning fix work — teammates may have lost context while idle. Include:
  - A reminder of which files they worked on
  - The specific PR feedback that needs addressing
  - The worktree path they should be working in
- **Message the implementer or ui-designer** (whichever is appropriate) with the specific changes needed — **do NOT fix it yourself**
- Message **unit-test-writer** to update/add tests for the fixes
- Message **qa-validator** to re-validate after fixes
- Once fixes are committed and pushed, message **pr-reviewer** to review again
- Repeat until the PR is approved and merged

---

## Guidelines

- **You are the orchestrator** — delegate ALL work to teammates, keep your own context minimal and focused on coordination
- **The Iron Rule applies at ALL times** — you never edit code, never run flutter analyze, never fix bugs, never read source files for research. Always delegate.
- **Use `teammate-comms` skill to talk to teammates** — this is reliable because it's file-based and persistent
- **Delegate all research and planning** to the planner teammate — never read source files, spawn research agents, or investigate the codebase yourself
- **Delegate all implementation** to the implementer and ui-designer teammates — never write code yourself
- **Delegate all testing** to the unit-test-writer teammate — never write tests yourself
- **Delegate all validation** to the qa-validator teammate — never run flutter analyze or QA yourself
- **Delegate all PR review** to the pr-reviewer teammate — never review diffs yourself
- **Launch the app via the `flutter-run` skill** before QA testing — never try to launch the app manually
- **Be a relay during planning** — pass questions from the planner to the user, pass answers back
- **Be autonomous during implementation** — only escalate to the user for blockers or major deviations from the plan
- **Track everything** — use the task list, update Linear statuses, keep the user informed of major progress milestones
- Never create SnackBar popups in the app code
- Tweakable settings go in `lib/core/config/app_settings.dart`
- Look for existing widgets to reuse before creating new ones
