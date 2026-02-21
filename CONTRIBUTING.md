# Contributing to VoidTerm

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Stable releases only |
| `develop` | Integration branch for next release |
| `feature/*` | Individual features and fixes |

## How to Contribute

1. **Fork** the repository
2. **Clone** your fork with `--recursive` (for the whisper.cpp submodule)
3. **Create a branch** from `develop`:
   ```bash
   git checkout develop
   git checkout -b feature/your-feature-name
   ```
4. **Make your changes**
5. **Push** to your fork
6. **Open a Pull Request** targeting `develop`

## Pull Request Guidelines

- Keep PRs focused on a single change
- Reference related issues in the PR description
- Ensure the APK builds successfully (`./gradlew assembleDebug`)
- Test on a Quest device if your change affects UI or voice input
- Update documentation if your change affects user-facing behavior

## Code Style

- **Java:** Standard Java conventions (Oracle Code Conventions)
- **JNI/C++:** Follow the existing whisper_jni.cpp style
- **Indentation:** 4 spaces (no tabs) for Java, 4 spaces for C++
- **Naming:** `camelCase` for methods/variables, `PascalCase` for classes
- **No unused imports or variables**

## Testing

VoidTerm is tested manually on Quest devices. There is no automated test suite at this time.

When submitting changes, describe your testing in the PR:

- Which Quest model you tested on (Quest 2 / 3 / Pro)
- What scenarios you verified
- Screenshots or recordings if relevant

## Issues

Use the issue templates:

- **[Bug Report](.github/ISSUE_TEMPLATE/bug_report.md)** -- for bugs and regressions
- **[Feature Request](.github/ISSUE_TEMPLATE/feature_request.md)** -- for new features

## Labels

| Label | Description |
|-------|-------------|
| `bug` | Something is broken |
| `voice` | Related to voice input / whisper.cpp |
| `quest` | Quest-specific issue |
| `enhancement` | Feature request |
| `good-first-issue` | Suitable for new contributors |

## License

By contributing, you agree that your contributions will be licensed under the [GPL-3.0 License](LICENSE).
