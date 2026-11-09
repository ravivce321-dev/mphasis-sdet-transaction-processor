package com.mphasis.transaction;

import java.math.BigDecimal;
import java.util.*;

/** In-memory demonstration. Public methods are synchronized to make state transitions atomic in this single-JVM exercise. */
public class TransactionProcessor {
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, TransactionResult> results = new LinkedHashMap<>();
    private final Map<String, TransactionResult> resultsByRequest = new HashMap<>();
    private final Map<String, String> processedTransactionIds = new HashMap<>();
    private final Set<String> seenRequestIds = new HashSet<>();
    private final Set<String> acceptedTransactionIds = new HashSet<>();
    private final Map<String, Long> nextSequence = new HashMap<>();
    private final Map<String, NavigableMap<Long, Transaction>> pendingByAccount = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Integer> simulatedFailuresRemaining = new HashMap<>();
    private final int maxAttempts;

    public TransactionProcessor() { this(3); }
    public TransactionProcessor(int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        this.maxAttempts = maxAttempts;
    }

    /** Configure deterministic transient failure for demo/test; first attempt fails, next attempt can succeed. */
    public synchronized void simulateTransientFailureOnce(String transactionId) { simulateTransientFailures(transactionId, 1); }
    public synchronized void simulateTransientFailures(String transactionId, int failures) {
        if (failures < 0) throw new IllegalArgumentException("failures cannot be negative");
        simulatedFailuresRemaining.put(transactionId, failures);
    }

    public synchronized void submit(Transaction tx) {
        if (tx == null) return;
        if (isBlank(tx.transactionId()) || isBlank(tx.requestId()) || isBlank(tx.accountId())) {
            putResult(tx, new TransactionResult(tx.transactionId(), TransactionStatus.FAILED, 0, "Missing identifier"));
            return;
        }
        if (seenRequestIds.contains(tx.requestId()) || acceptedTransactionIds.contains(tx.transactionId())) {
            putResult(tx, new TransactionResult(tx.transactionId(), TransactionStatus.DUPLICATE, 0, "Duplicate request or business transaction"));
            return;
        }
        if (!valid(tx)) {
            putResult(tx, new TransactionResult(tx.transactionId(), TransactionStatus.FAILED, 0, "Invalid transaction fields"));
            // A terminally invalid event at the expected sequence is treated as a consumed sequence to avoid a permanent gap.
            long expected = nextSequence.getOrDefault(tx.accountId(), 1L);
            if (tx.sequenceNumber() == expected) { nextSequence.put(tx.accountId(), expected + 1); drain(tx.accountId()); }
            return;
        }
        seenRequestIds.add(tx.requestId());
        acceptedTransactionIds.add(tx.transactionId());
        long expected = nextSequence.getOrDefault(tx.accountId(), 1L);
        if (tx.sequenceNumber() < expected) {
            putResult(tx, new TransactionResult(tx.transactionId(), TransactionStatus.FAILED, 0, "Stale sequence; transaction was not applied"));
        } else if (tx.sequenceNumber() > expected) {
            pendingByAccount.computeIfAbsent(tx.accountId(), k -> new TreeMap<>()).putIfAbsent(tx.sequenceNumber(), tx);
            putResult(tx, new TransactionResult(tx.transactionId(), TransactionStatus.PENDING, 0, "Waiting for sequence " + expected));
        } else {
            processAtExpectedSequence(tx);
            drain(tx.accountId());
        }
    }

    private void drain(String accountId) {
        NavigableMap<Long, Transaction> pending = pendingByAccount.get(accountId);
        while (pending != null) {
            long expected = nextSequence.getOrDefault(accountId, 1L);
            Transaction next = pending.remove(expected);
            if (next == null) break;
            processAtExpectedSequence(next);
        }
        if (pending != null && pending.isEmpty()) pendingByAccount.remove(accountId);
    }

    private void processAtExpectedSequence(Transaction tx) {
        String id = tx.transactionId();
        putResult(tx, new TransactionResult(id, TransactionStatus.PROCESSING, attempts.getOrDefault(id, 0), "Processing"));
        int attempt = attempts.merge(id, 1, Integer::sum);
        int failuresLeft = simulatedFailuresRemaining.getOrDefault(id, 0);
        if (failuresLeft > 0) {
            simulatedFailuresRemaining.put(id, failuresLeft - 1);
            if (attempt < maxAttempts) {
                putResult(tx, new TransactionResult(id, TransactionStatus.RETRY_PENDING, attempt, "Simulated transient failure"));
                retryTransactions.put(id, tx);
                // Keep sequence cursor unchanged; explicit retry resumes this transaction.
                return;
            }
            putResult(tx, new TransactionResult(id, TransactionStatus.FAILED, attempt, "Retry limit reached"));
            nextSequence.put(tx.accountId(), tx.sequenceNumber() + 1);
            return;
        }
        applyEffect(tx);
        retryTransactions.remove(id);
        processedTransactionIds.put(id, tx.accountId());
        putResult(tx, new TransactionResult(id, TransactionStatus.PROCESSED, attempt, "Transaction applied"));
        nextSequence.put(tx.accountId(), tx.sequenceNumber() + 1);
    }

    public synchronized void retryPending() {
        List<String> retryIds = results.values().stream().filter(r -> r.status() == TransactionStatus.RETRY_PENDING).map(TransactionResult::transactionId).toList();
        for (String id : retryIds) {
            Transaction tx = findPendingTransaction(id);
            if (tx == null) continue;
            if (attempts.getOrDefault(id, 0) >= maxAttempts) {
                putResult(tx, new TransactionResult(id, TransactionStatus.FAILED, attempts.get(id), "Retry limit reached"));
                nextSequence.put(tx.accountId(), tx.sequenceNumber() + 1);
                drain(tx.accountId());
            } else {
                // Retry does not re-run earlier successful effects; only RETRY_PENDING items are eligible.
                processAtExpectedSequence(tx);
                if (status(id) != TransactionStatus.RETRY_PENDING) drain(tx.accountId());
            }
        }
    }

    private Transaction findPendingTransaction(String id) {
        for (NavigableMap<Long, Transaction> map : pendingByAccount.values()) for (Transaction tx : map.values()) if (tx.transactionId().equals(id)) return tx;
        // The current retry item is held separately because it has been removed from the out-of-order buffer.
        return retryTransactions.get(id);
    }
    private final Map<String, Transaction> retryTransactions = new HashMap<>();

    private void applyEffect(Transaction tx) {
        BigDecimal current = balances.getOrDefault(tx.accountId(), BigDecimal.ZERO);
        BigDecimal delta = tx.type() == TransactionType.DEBIT ? tx.amount().negate() : tx.amount();
        balances.put(tx.accountId(), current.add(delta));
    }
    private boolean valid(Transaction tx) {
        return !isBlank(tx.transactionId()) && !isBlank(tx.requestId()) && !isBlank(tx.accountId())
                && tx.type() != null && tx.amount() != null && tx.amount().compareTo(BigDecimal.ZERO) > 0
                && "INR".equals(tx.currency()) && tx.sequenceNumber() > 0;
    }
    private boolean isBlank(String s) { return s == null || s.isBlank(); }
    private String key(Transaction tx) { return tx == null ? "null" : String.valueOf(tx.transactionId()); }
    private void putResult(Transaction tx, TransactionResult result) {
        String key = tx == null ? "null" : String.valueOf(tx.transactionId());
        if (results.containsKey(key) && tx != null && results.get(key).status() != TransactionStatus.PROCESSING
                && result.status() == TransactionStatus.DUPLICATE) key = key + "#" + tx.requestId();
        results.put(key, result);
        if (tx != null && tx.requestId() != null) resultsByRequest.put(tx.requestId(), result);
    }

    public synchronized TransactionStatus status(String transactionId) {
        TransactionResult r = results.get(transactionId); return r == null ? null : r.status();
    }
    public synchronized BigDecimal balance(String accountId) { return balances.getOrDefault(accountId, BigDecimal.ZERO); }
    public synchronized TransactionResult result(String transactionId) { return results.get(transactionId); }
    public synchronized TransactionStatus statusByRequest(String requestId) { TransactionResult r = resultsByRequest.get(requestId); return r == null ? null : r.status(); }
    public synchronized Map<String, TransactionResult> results() { return Collections.unmodifiableMap(new LinkedHashMap<>(results)); }
    public synchronized ProcessingSummary summary() {
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionResult r : results.values()) counts.merge(r.status(), 1L, Long::sum);
        Map<String, String> balanceView = new TreeMap<>();
        balances.forEach((k,v) -> balanceView.put(k, v.toPlainString()));
        return new ProcessingSummary(Collections.unmodifiableMap(counts), Collections.unmodifiableMap(balanceView));
    }

    // Called internally when a sequence is blocked by a transient failure.
    
}
