# Flaky-test assessment

## Concurrency scheduling

The concurrency test uses a `CountDownLatch` start barrier and accepts either 2xx or the specified
business `409`; it never uses sleep to decide which revision should win. Five independent Maven/JVM
runs were executed, each with a fresh PostgreSQL 16 Testcontainer:

- Runs: 5
- Pass: 5
- Fail/error: 0
- Final invariant checked every run: revision 20 and payload `C-20`

This gives limited repeat evidence, not proof that the test can never be flaky. OS scheduling,
container startup and resource pressure can still expose failures in a different environment.

## Shared database state and time

- `@BeforeEach resetDrafts()` truncates `order_drafts` when present and removes the injected failure
  trigger/function, so tests do not depend on method order.
- Each independent repeat used a new database container; the normal class also resets state between
  methods.
- The concurrency assertion depends on revision ordering enforced by PostgreSQL, not wall-clock
  ordering.
- Replay compares the returned persisted `savedAt` values for equality; it does not assert a timing
  window or sleep. Database `clock_timestamp()` is used only for accepted writes.
- The injected-failure test explicitly drops its trigger before reading the committed old snapshot.

Remaining risk: abrupt test termination before cleanup can leave a trigger inside that process's
container, and five repeats are too few to estimate a very low intermittent failure rate.
