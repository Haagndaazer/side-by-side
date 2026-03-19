# Release

End-to-end command that builds a release, updates the changelog, commits, and posts to Discord.

---

## Step 1: Build the Release

1. Run the build script via PowerShell (required for output to display):
   ```
   powershell.exe -Command "Set-Location 'C:\Users\colto\Documents\Projects\LingLang'; & cmd.exe /c '.\build_release_advanced.bat'"
   ```
2. If the build fails, report the error to the user and **stop** — do not continue.
3. Read `pubspec.yaml` and extract the new version string (e.g., `1.0.0+67`).

---

## Step 2: Generate Changelog Entry

1. Find the previous build number by checking git history for the last time the `version:` line in `pubspec.yaml` changed:
   ```
   git log --oneline --all -1 --diff-filter=M -S "version:" -- pubspec.yaml HEAD~1
   ```
   Use `git log` to find the commit where the build number was last different, then get all commits between that point and HEAD.
2. Gather all commit messages since that commit.
3. Read the existing `CHANGELOG.md` to understand the format and style of previous entries.
4. Write a new changelog entry at the top of `CHANGELOG.md` (below the `# Changelog` header) following the existing format:
   - Header: `## <version> (<YYYY-MM-DD>) — <Short Title>`
   - Bullet points: `- **Feature Name** — Description`
   - Consolidate related commits into meaningful user-facing features
   - Write a 2-sentence Play Store summary (title + description) that captures the highlights
   - DO NOT INCLUDE anything related to development tooling, only include items related to the actual customer facing app experience.
5. **GATE: Show the user the changelog entry AND the Play Store summary for approval.** Use `AskUserQuestion` with options to approve or request edits. If they request edits, revise and re-present until approved.

---

## Step 3: Commit, Tag, and Push

1. Stage only `CHANGELOG.md` and `pubspec.yaml`:
   ```
   git add CHANGELOG.md pubspec.yaml
   ```
2. Commit with message: `Release v<version>`
3. Create a git tag:
   ```
   git tag v<version>
   ```
4. Push the commit and tag:
   ```
   git push && git push --tags
   ```

---

## Step 4: Wait for Play Store Upload

1. Use `AskUserQuestion` to ask the user to confirm they have uploaded the AAB to the Google Play Store.
   - Provide the AAB path: `build\app\outputs\bundle\release\app-release.aab`
   - Options: "Upload complete" or "Cancel release"
2. If cancelled, stop and report that the release was partially completed (committed and tagged but not announced).

---

## Step 5: Post to Discord

1. Post a release announcement to the `#release-log` channel using the Discord MCP:
   ```
   mcp__discord__discord_send
   channelId: "1478831831905210543"
   ```
2. Format the message as:
   ```
   ## 🚀 A new version has appeared! <version> 
   **<Release Title>**
   <2-sentence Play Store summary>
   ```

---

## Step 6: Summary

Output a summary of everything done:
- any issues you had during the execution of this workflow or aspects that were unclear, these will be used to improve the next run.
- Version released
- Changelog entry added
- Commit hash
- Git tag created
- Discord announcement posted
- Play Store summary (for the user to copy-paste into the Play Store listing)
