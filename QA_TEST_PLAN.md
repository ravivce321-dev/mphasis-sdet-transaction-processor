# QA Test Strategy and Regression Coverage

## Scope
Validate the transaction intake, validation, idempotency, ordering, retry, state transitions, ledger effects, and processing summary. The current executable implementation is an in-memory single-JVM assessment prototype.

## Test levels
- Unit: validation rules, idempotency decisions, sequence cursor, retry attempts, balance calculation, summary aggregation.
- Component/integration: submit a batch through the processor and verify statuses plus balances across accounts.
- Regression: rerun all previously passing core scenarios after every business-rule or processor change.
- Non-functional: concurrency, restart durability, throughput, and multi-instance behavior are production-hardening tests; current in-memory prototype cannot claim to pass restart or multi-instance durability.

## Executable coverage
The dependency-free `VerificationRunner` executes 9 core scenarios and currently reports `9/9 passed`. `TransactionProcessorTest` contains corresponding JUnit 5 tests plus retry-exhaustion coverage. Run JUnit with `mvn clean test` in a Maven-enabled environment.

## Detailed test cases
| ID | Priority | Scenario / test data | Expected result | Automation status |
|---|---|---|---|---|
| TC-001 | P0 | Valid CREDIT amount 100, sequence 1 | PROCESSED; balance +100 | Executed in runner |
| TC-002 | P0 | Valid DEBIT amount 40 after credit 100 | PROCESSED; balance 60 | Executed in runner |
| TC-003 | P0 | Sequence 2 arrives before sequence 1 | Sequence 2 PENDING; no balance effect until sequence 1 arrives | Executed in runner |
| TC-004 | P0 | Sequence 1 arrives after sequence 2 | Both process in order; final balance correct | Executed in runner |
| TC-005 | P0 | Same transactionId with a new requestId | Second delivery DUPLICATE; no second ledger effect | Executed in runner |
| TC-006 | P0 | Same requestId with a different transactionId | Second delivery DUPLICATE; no second ledger effect | Executed in runner |
| TC-007 | P0 | Negative amount | FAILED validation; no balance effect | Executed in runner |
| TC-008 | P0 | Transient failure once, then retry | RETRY_PENDING then PROCESSED; exactly one balance effect | Executed in runner |
| TC-009 | P0 | Transient failure reaches max attempts | FAILED; no balance effect | Executed in runner |
| TC-010 | P0 | Invalid event at the next expected sequence followed by valid next sequence | Invalid event FAILED; cursor advances by documented policy; later event processes | Executed in runner |
| TC-011 | P1 | Zero amount | FAILED validation; no balance effect | JUnit candidate / extend runner |
| TC-012 | P1 | Missing transactionId, requestId, or accountId | FAILED validation; no balance effect | JUnit candidate / extend runner |
| TC-013 | P1 | Unsupported currency | FAILED validation | JUnit candidate / extend runner |
| TC-014 | P1 | Null transaction type or amount | FAILED validation without uncaught exception | JUnit candidate / extend runner |
| TC-015 | P1 | Sequence number zero or negative | FAILED validation | JUnit candidate / extend runner |
| TC-016 | P1 | Same sequence number used by two distinct transactions for one account | Reject/flag sequence conflict; never apply both silently | Design gap: add explicit conflict handling |
| TC-017 | P1 | Same sequence number in two different accounts | Process independently | Extend runner |
| TC-018 | P1 | Stale sequence for an unseen transaction | FAILED as stale; no balance effect | Extend runner |
| TC-019 | P1 | Duplicate arrives while original is still PENDING | Duplicate delivery must not create a second pending event | Extend runner |
| TC-020 | P1 | Retry one account while another account has valid work | Retry isolation; unrelated account continues | Extend runner |
| TC-021 | P1 | Retry call repeated after transaction PROCESSED | No further balance change | Extend runner |
| TC-022 | P1 | Multiple missing sequence numbers | Future events remain pending until contiguous gap is filled | Extend runner |
| TC-023 | P1 | Batch contains one malformed event and later valid events | Malformed event isolated; batch continues | Extend runner |
| TC-024 | P1 | Summary after mixed processed/duplicate/failed/retry states | Counts match per-delivery results; balances match ledger effects | Executed in runner for processed/duplicate |
| TC-025 | P2 | Large batch / throughput | Establish throughput and latency baseline | Not run; performance test required |
| TC-026 | P2 | Concurrent duplicate submissions | Exactly one effect across workers | Not proven by in-memory prototype; DB uniqueness/locking required |
| TC-027 | P2 | Process restart after successful transaction | Durable idempotency and ledger state retained | Not supported by in-memory prototype |
| TC-028 | P2 | Crash between ledger write and status update | Atomic transaction prevents partial state | Not supported by in-memory prototype |
| TC-029 | P2 | Sensitive account data in logs | No unnecessary PII or secrets in logs | Review required when structured logging is added |
| TC-030 | P2 | Retry storm / downstream unavailable | Backoff, max attempts, DLQ and alerting behave correctly | Design recommendation; no real queue in scope |

## Regression suite entry criteria
Run the full P0 and P1 suite whenever validation, duplicate detection, sequence handling, retry logic, or ledger updates change. Run P2 tests before production release or when replacing in-memory storage with a database/queue implementation.

## Defect severity examples
- Critical: duplicate debit/credit or corrupted account balance.
- High: out-of-order transaction applied before its predecessor; retry applies effect twice; valid transaction permanently lost.
- Medium: incorrect status/report count while ledger effect remains correct; invalid record blocks unrelated accounts.
- Low: unclear diagnostic message or nonessential log formatting.

## Known implementation gaps to disclose
1. Sequence collisions for two different transaction IDs at the same account sequence need explicit conflict handling.
2. Duplicate delivery reporting is retained separately, but a production event store should persist every delivery attempt with its own immutable event key.
3. Reversal is represented as a positive adjustment in this small model; a real reversal must reference and validate the original transaction and prevent multiple reversals.
4. In-memory maps and Java synchronization do not provide restart durability or multi-instance exactly-once effects.
5. Currency is currently restricted to INR for scope control; production must validate supported ISO currency and scale rules.
