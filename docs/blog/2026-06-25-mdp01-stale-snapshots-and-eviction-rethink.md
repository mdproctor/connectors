## Stale SNAPSHOTs and an eviction rethink

The devtown session reported three breaking changes in published SNAPSHOTs — `ConflictResolverStrategy.DEEP_MERGE` removed, `SubjectSequenceStats` constructor mismatch, `ChannelService.create()` signature change. Two of the three turned out to be phantom failures: DEEP_MERGE is still in the schema enum and has tests, `SubjectSequenceStats` is now a record with the right constructor shape. The real problem was stale JARs in `~/.m2`, not API changes. A local `mvn install -DskipTests` through the full dependency chain — parent through engine — cleared all three.

Engine was the only module that needed `-Dmaven.test.skip=true` to get past test compilation. The test references `WorkerFunction` and `WorkerResult` in the old `io.casehub.api.model` package — exactly the migration that engine#543 exists to fix. Production code compiles clean.

The idle eviction daemon I built last session was killing sessions too aggressively. Changed the threshold from 1 hour to 3, then killed the timer entirely. The launchd daemon is now unloaded, but the Stop hook still timestamps every interaction to `~/.claude_idle/<pid>.touch`. The eviction script takes an argument: `evict-idle-claude.sh 3h` kills sessions idle longer than 3 hours, no argument lists idle times. On-demand is the right model — I know when sessions need cleaning, the timer doesn't.

Closed the auth retrofit epic. All four harnesses done: devtown#90, openclaw#41, life#40, clinical#88. The parent issue (#251) had listed casehub-life as still needing work, but life#40 was already closed — it was the reference implementation the other harnesses were following. The epic body was just stale.

The cross-repo refactoring problem came up again. Each CaseHub repo runs in its own IntelliJ window, so renames that span repos require manual coordination. IntelliJ supports attaching multiple repos into one project window — full cross-repo rename, find-usages, and move-refactoring. The plan: do it ad-hoc when a refactoring spans repos, not as a permanent mega-project. The IntelliJ MCP can't automate this (no `ide_attach_project` equivalent), so it stays manual for now.
