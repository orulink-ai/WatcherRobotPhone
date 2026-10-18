---
name: configure
description: Configure the Android Remote Control channel plugin
---

Configure the Android Remote Control channel plugin.

Usage: /android-remote-control:configure <auth_token> [listen_port] [prompt_template]

Arguments:
- auth_token (required): Bearer token for authenticating requests from the Android app
- listen_port (optional, default 9090): Port for the HTTP listener
- prompt_template (optional): Prompt prepended to events sent to Claude

This command writes configuration to ~/.claude/channels/android-remote-control/.env with 0o600 permissions.

When invoked:
1. Create the directory ~/.claude/channels/android-remote-control/ if it doesn't exist
2. Write the .env file with the provided values:
   AUTH_TOKEN=<auth_token>
   LISTEN_PORT=<listen_port>
   PROMPT_TEMPLATE=<prompt_template>
3. Set file permissions to 0o600 (owner read/write only)
4. Inform the user they need to restart Claude Code with the --channels flag:
   claude --channels plugin:android-remote-control@<marketplace>
   or for development:
   claude --dangerously-load-development-channels server:android-remote-control
