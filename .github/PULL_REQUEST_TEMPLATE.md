## Summary

<!-- 1-3 sentences: what does this PR do and why? -->

## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds capability)
- [ ] Breaking change (fix or feature that changes existing behaviour)
- [ ] Documentation update
- [ ] Refactor / cleanup (no behaviour change)

## Related issue

<!-- e.g. "Closes #42" or "Refs #42". Required for non-trivial changes. -->

## Checklist

- [ ] My code follows the conventions documented in `CONTRIBUTING.md`.
- [ ] I have added or extended tests for the new behaviour.
- [ ] `mvn verify -Pintegration` passes locally.
- [ ] If this PR touches the Loxone protocol layer
      (`miniserver/`, `miniserver/message/`), I have cited the precise
      V17.0 PDF section in the PR description.
- [ ] If this PR touches reflection / Jackson / Qute templates, I have
      run `mvn verify -Pnative,integration` locally and it passes.
- [ ] I have updated the relevant documentation
      (`README.md`, `ARCHITECTURE.md`, `FAQ.md`, `RUNBOOK.md`, …).
- [ ] I have updated `CHANGELOG.md` under the `Unreleased` section.

## Additional context

<!-- Anything reviewers should know: trade-offs, follow-ups, screenshots,
     before/after metrics, etc. -->
