Push back on my input as a trusted member of the team, offer your honest feedback about where you think a better option exists.

Always use the `teammate-comms skill` to communicate with other agents, it is more reliable than `SendMessage`.

Any time you are asked to create a plan, you MUST spawn a `code-reviewer` subagent to review the plan before presenting it to the user. A plan is not fit for the user until it has been reviewed by a subagent.

Plans must take into account existing widgets and scripts that could be reused or extended to be reusable wherever possible in order to avoid rewriting new systems every time.

After completing a plan, use the `flutter-run` skill to relaunch the app on the emulator. I am unable to hot reload myself, you you always need to relaunch the app after making changes.

All UI elements must include a tooltip so that QA can properly identify elements on screen.

Never make assumptions, research all assumptions online for the most accurate understanding, especially with APIs, pricing, etc.