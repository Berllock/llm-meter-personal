# Codex session aggregation

`CodexSessionAggregator` aggregates the latest snapshots of identified independent
threads and proven own contributions of supported child threads. These totals
describe the supplied sessions, not daily usage,
account-wide usage, or quota.

The separate [daily usage endpoint](daily-usage.md) derives timestamped increments;
it does not treat these final snapshots as daily history.

- The deduplication key is `session_meta.payload.id`. Do not use the shared
  `session_id` field, which can be shared by a root thread and its subagents.
- Equal IDs, relationships, raw counters, and attributable metrics count once. Collection timestamps are ignored
  because they describe our file reads, not when Codex recorded the usage.
- Equal IDs with different metrics fail collection. Neither file order nor our
  collection timestamps establish which record is authoritative.
- Distinct independent IDs are summed. An empty input produces zero totals for
  that empty set of sessions.
- An unavailable metric in any session makes that metric's total unavailable
  (`null`); known metrics remain independently aggregatable.
- Missing IDs, negative counts, and arithmetic overflow fail collection.
- `parent_thread_id` and `forked_from_id` are preserved separately. Related threads
  require a proven `attributableUsage` snapshot. Unsupported histories still fail
  aggregation and are never silently discarded.

## Own versus inherited counters

`CodexSessionUsage.usage` preserves the final recorded cumulative snapshot.
`attributableUsage` is a separate, nullable snapshot containing only the contribution
that can be assigned to this child. The single-file adapter continues to return
the recorded snapshot; directory aggregation uses the attributable snapshot for
related sessions. No new field is added to the public `UsageSnapshot` JSON.

The streaming `CodexUsageAttributor` supports these constrained paginated formats:

| History | Required evidence | Contribution |
| --- | --- | --- |
| Fresh child | Parent identity, `history_mode=paginated`, no external `history_base`, no fork origin, and first known component totals equal their `last_token_usage` components | Final cumulative components from a proven zero baseline |
| Child with a copied prefix | Parent identity, paginated mode, valid `subagent_history_start_ordinal`, no external history reference, and a visible inherited token snapshot before that ordinal | Final cumulative components minus the last inherited component baseline |
| Child containing only that inherited prefix | The same visible baseline and explicit boundary | Zero contribution for known components |

For example, a parent snapshot of 1000 input tokens and a child whose cumulative
counter moves from inherited 600 to final 700 produce 1100 input tokens, not 1700.

Every processed token event needs a valid nonnegative ordinal. Owned counters must
not decrease; ordinal regressions and conflicting ordinal replays invalidate
attribution. An exact replay of an ordinal and counters is ignored. Repeated
cumulative counters never add another charge. Missing metric components remain
unknown, including components that disappear during owned history.

Legacy or unknown history modes, externally referenced history, standalone forks
without a supported child boundary, nonzero unmarked baselines, absent first-request
evidence, late/repeated metadata, resets, and invalid ordinals remain unsupported.
Copied subagent forks are supported only when their explicit boundary and visible
baseline meet the copied-prefix rule. A child's final counter is never blindly
added to its parent's final counter.

Only a small constant amount of attribution state is retained while reading a
file; no transcript or list of all token events is buffered.

### Evidence and scope

Reviewed against primary Codex sources on 2026-10-25:

- [Thread metadata](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/types.rs)
  distinguishes parent, fork origin, external history, and the first child-owned ordinal.
- [Child history persistence](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/live_thread.rs)
  writes the copied-prefix boundary before persisting inherited context.
- [Session initialization](https://github.com/openai/codex/blob/main/codex-rs/core/src/session/session.rs)
  distinguishes plain creation from inherited subagent context.
- [History initialization](https://github.com/openai/codex/blob/main/codex-rs/core/src/session/mod.rs)
  restores token state for resumed/forked histories.
- [Token usage protocol](https://github.com/openai/codex/blob/main/codex-rs/protocol/src/protocol.rs)
  adds request component counts to cumulative component counters; rate-limit updates
  may re-emit the current usage snapshot.

These observations support the constrained patterns above, not every historical
Codex format or a billing guarantee. Fixtures in `src/test/resources/codex/attribution/`
are synthetic; no real session content is stored in the repository.

## Configuration and HTTP collection

Configure exactly one property:

- `llm-meter.codex.session-file`: read a single file using the existing behavior.
- `llm-meter.codex.sessions-directory`: discover JSONL files recursively, read
  each session, deduplicate, and aggregate their usage into one Codex snapshot.

For example, pass `-Dllm-meter.codex.sessions-directory=/absolute/path/to/sessions`
in JVM options. Do not also set `session-file`. Both sources together, or a blank
source path, fail application startup. With neither property set, no Codex adapter
is registered.

`GET /api/usage` collects from the configured source on each request. A directory
produces one aggregated Codex snapshot, not one adapter or response item per file.
New session files are discovered on the next request. No cache or database is used.

An empty directory produces zero counters for its empty set of sessions. A read,
parse, identity, unsupported-attribution, conflict, or arithmetic failure aborts the entire
collection and returns the existing HTTP 503 problem response. Partial totals are
not returned. Archived files outside the configured tree are not included.

Metadata reference: [Codex thread metadata](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/types.rs).

## Local directory diagnostics

Run the `com.llmeter.personal.provider.codex.service.CodexUsageExample` main class from the test source tree with a sessions
directory as its single program argument. It uses `CodexDirectoryInspector` to
report discovered and readable file counts, related sessions, attributable related sessions, missing identities,
and collection problems. Files with read failures are numbered by sorted path
order; their names, paths, raw JSON, and exception causes are not printed.

The report returns a snapshot only when every discovered file can be read and the
entire set can be aggregated under the existing rules. Otherwise its `usage` is
`null` and its issues explain the failure. It never publishes a partial total.
Related counts include both supported and unsupported histories; attributed related
counts show which of those have proven own contributions. Related and unidentified
counts refer to successfully parsed files, so files that
cannot be read may contain additional unknown relationships or identities.

This is a local diagnostic tool. The public HTTP 503 contract and the production
adapter's strict collection behavior remain in effect.
