package com.mphasis.transaction;

import java.math.BigDecimal;
import java.util.List;

public class Application {
    public static void main(String[] args) {
        TransactionProcessor processor = new TransactionProcessor(3);
        processor.simulateTransientFailureOnce("TXN-2005");
        List<Transaction> batch = List.of(
            tx("TXN-2002", "REQ-2", "ACC-201", TransactionType.DEBIT, "40", 2),
            tx("TXN-2001", "REQ-1", "ACC-201", TransactionType.CREDIT, "100", 1),
            tx("TXN-2002", "REQ-2-DUP", "ACC-201", TransactionType.DEBIT, "40", 2),
            tx("TXN-2003", "REQ-3", "ACC-201", TransactionType.CREDIT, "25", 3),
            tx("TXN-2004", "REQ-4", "ACC-202", TransactionType.DEBIT, "-10", 4),
            tx("TXN-2005", "REQ-5", "ACC-203", TransactionType.CREDIT, "500", 1)
        );
        batch.forEach(processor::submit);
        System.out.println("Before retry: " + processor.summary());
        processor.retryPending();
        System.out.println("After retry:  " + processor.summary());
        processor.results().forEach((key, value) -> System.out.println(key + " => " + value));
    }
    private static Transaction tx(String id, String request, String account, TransactionType type, String amount, long seq) {
        return new Transaction(id, request, account, type, new BigDecimal(amount), "INR", seq);
    }
}
