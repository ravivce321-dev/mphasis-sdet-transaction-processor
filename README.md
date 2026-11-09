# Mphasis SDET Practical Assessment – Idempotent Background Transaction Processor

## Run
Requires JDK 17+ and Maven 3.8+.

```bash
mvn clean test
mvn -q package
java -cp target/classes com.mphasis.transaction.Application
```

## Design
- `TransactionProcessor` is the business processing component; the `Application` class is only a replaceable input adapter/demo.
- Idempotency is enforced using both `transactionId` (business operation identity) and `requestId` (delivery/retry identity).
- Sequence numbers are tracked per account, starting at 1. Future events are buffered until the missing sequence arrives.
- A terminally invalid event at the expected sequence consumes that sequence, preventing an invalid event from blocking the account forever. This is a deliberate policy choice; production systems may instead require an operator-approved skip/dead-letter action.
- A transient failure is recorded as `RETRY_PENDING`; `retryPending()` retries without applying any prior effect. Maximum attempts are configurable (default 3).
- Balance mutation and processed-state update happen in one synchronized in-memory critical section for this single-process demo.
- Summary reports counts by latest delivery status and balances.

## Important limitations / production hardening
This is intentionally an in-memory, single-JVM assessment implementation, not a claim of distributed exactly-once processing. Restart loses state. For production, use a durable database with unique constraints on transaction ID and request ID, an atomic database transaction for ledger + idempotency record + sequence cursor, row/account-level locking or optimistic concurrency, a durable retry queue with exponential backoff and jitter, a dead-letter queue, schema/version validation, currency-aware decimal rules, structured logs with correlation IDs, metrics/tracing/alerts, and reconciliation jobs. For multi-worker execution, replace synchronized in-memory state with database/queue coordination. Never rely on an in-memory lock for cross-instance safety.

## Sequence policy and trade-off
Ordering is per account, not global. Sequence 2 arriving before sequence 1 becomes `PENDING`; when sequence 1 arrives the processor drains contiguous events. An invalid event at the expected sequence is marked `FAILED` and the sequence cursor advances. A transient failure does *not* advance the cursor until retry succeeds or retry exhaustion is explicitly handled. A production design should make skip/dead-letter decisions auditable.

## Test coverage
JUnit 5 tests cover:
1. Valid credit/debit processing.
2. Out-of-order buffering and automatic drain.
3. Duplicate business transaction ID with a different request ID.
4. Duplicate request ID with a different transaction ID.
5. Negative amount validation.
6. Transient failure followed by retry, with no duplicate effect.
7. Invalid expected sequence does not block the next valid sequence.
8. Processing summary counts and balance reporting.

## AI-assisted development disclosure
AI assistance was used to accelerate scaffolding and test-case brainstorming. The implementation, business rules, test assertions, and limitations should be reviewed and explained by the candidate. Do not claim AI-generated code is correct without executing tests and reviewing edge cases.


A dependency-free `VerificationRunner` is also included and can be run with `java -cp target/classes com.mphasis.transaction.VerificationRunner` after compiling the main sources. It currently executes 9 core scenarios (9/9 passed). See `QA_TEST_PLAN.md` for the wider regression inventory and known gaps.
