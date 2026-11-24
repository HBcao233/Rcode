# Contributing

Read [`.agent/README.md`](.agent/README.md) first. The rules there are binding for humans and agents alike.

## Workflow

1. Pick a task from `.agent/tasks/backlog.md` (Ready section).
2. Create a branch: `feat/<slug>`, `fix/<slug>`, or `chore/<slug>`.
3. Write failing tests first where practical.
4. Keep changes small (about 400 changed lines or fewer).
5. Use Conventional Commits, e.g. `feat(core): add ping export`.

## Definition of Done

See [`.agent/conventions.md`](.agent/conventions.md) §7. In short:

- `cargo fmt --check`, `cargo clippy -D warnings`, `cargo test --workspace` pass in `core/`
- `./gradlew :app:ktlintCheck :app:testDebugUnitTest :app:assembleDebug` pass
- `CHANGELOG.md` updated for user-visible changes
- Task file status updated

## Dependencies

New dependencies require the checklist in `.agent/conventions.md` §6, and human approval.
