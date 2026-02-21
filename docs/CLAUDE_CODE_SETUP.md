# Claude Code Setup on VoidTerm

This guide covers installing and running Claude Code inside VoidTerm on Meta Quest.

## Prerequisites

- VoidTerm installed and running on Quest (see [QUEST_SETUP.md](QUEST_SETUP.md))
- Phantom process killing disabled (see [QUEST_SETUP.md](QUEST_SETUP.md#3-disable-phantom-process-killing-critical))
- An Anthropic API key from [console.anthropic.com](https://console.anthropic.com)
- Internet connection on Quest (Wi-Fi)

## 1. Install Dependencies

Open VoidTerm and run:

```bash
pkg update && pkg install nodejs git
```

This installs Node.js (required by Claude Code) and Git.

## 2. Install Claude Code

```bash
npm i -g @anthropic-ai/claude-code
```

## 3. Configure API Key

```bash
echo 'export ANTHROPIC_API_KEY=sk-ant-...' >> ~/.bashrc
source ~/.bashrc
```

Replace `sk-ant-...` with your actual API key from the Anthropic console.

### Security Note

Your API key is stored in `~/.bashrc` within Termux's private storage directory (`/data/data/com.voidterm/files/home/`). This directory is:

- **Sandboxed** by Android: no other app can access it
- **Encrypted** by Android Full-Disk Encryption (FBE) when the device is locked
- **Not accessible** without developer/root access

This is the standard storage method used by Claude Code on all platforms.

## 4. First Run

```bash
claude
```

Claude Code launches and displays the interactive prompt. You can type or use Push-to-Talk to dictate your prompts.

## 5. Using Voice with Claude Code

1. Press and hold **A** or **X** on your Quest controller
2. Speak your prompt
3. Release the button
4. Review the transcribed text in the overlay
5. Press **Enter** to send, or **Escape** to cancel
6. Edit the text with a Bluetooth keyboard before sending if needed

Voice text is injected into the terminal but never auto-executed. You always confirm before sending.

## Known Issues

### npm permission errors

If `npm i -g` fails with permission errors:

```bash
npm i -g @anthropic-ai/claude-code --unsafe-perm
```

### node-gyp compilation on ARM64

Some npm packages with native addons may fail to compile. If you encounter `node-gyp` errors:

```bash
pkg install python make clang
```

Then retry the installation.

### Claude Code crashes after a few minutes

This is almost certainly the phantom process killing issue. Ensure you have run the ADB fix described in [QUEST_SETUP.md](QUEST_SETUP.md#3-disable-phantom-process-killing-critical).

### Slow response rendering

Claude Code streams responses in real time. If rendering seems slow, this is normal for long responses on ARM64. The terminal handles ANSI color codes and cursor movement correctly.

### "Cannot find module" errors

Ensure Node.js is up to date:

```bash
pkg upgrade nodejs
```
