package com.mphasis.transaction;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessorTest {
    private Transaction tx(String id, String req, String account, TransactionType type, String amount, long seq) {
        return new Transaction(id, req, account, type, new BigDecimal(amount), "INR", seq);
    }

    @Test void processesValidCreditAndDebitInSequence() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"100",1));
        p.submit(tx("T2","R2","A1",TransactionType.DEBIT,"40",2));
        assertEquals(new BigDecimal("60"), p.balance("A1"));
        assertEquals(TransactionStatus.PROCESSED, p.status("T2"));
    }

    @Test void buffersOutOfOrderAndDrainsWhenGapArrives() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("T2","R2","A1",TransactionType.DEBIT,"40",2));
        assertEquals(TransactionStatus.PENDING, p.status("T2"));
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"100",1));
        assertEquals(TransactionStatus.PROCESSED, p.status("T1"));
        assertEquals(TransactionStatus.PROCESSED, p.status("T2"));
        assertEquals(new BigDecimal("60"), p.balance("A1"));
    }

    @Test void duplicateBusinessTransactionDoesNotApplyEffectTwice() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"100",1));
        p.submit(tx("T1","R1-REPLAY","A1",TransactionType.CREDIT,"100",2));
        assertEquals(TransactionStatus.DUPLICATE, p.statusByRequest("R1-REPLAY"));
        assertEquals(new BigDecimal("100"), p.balance("A1"));
    }

    @Test void duplicateRequestIdDoesNotApplySecondTransaction() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"100",1));
        p.submit(tx("T2","R1","A1",TransactionType.CREDIT,"50",2));
        assertEquals(new BigDecimal("100"), p.balance("A1"));
        assertEquals(TransactionStatus.DUPLICATE, p.statusByRequest("R1"));
    }

    @Test void rejectsNegativeAmountAndDoesNotChangeBalance() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("BAD","RB","A1",TransactionType.DEBIT,"-10",1));
        assertEquals(TransactionStatus.FAILED, p.status("BAD"));
        assertEquals(BigDecimal.ZERO, p.balance("A1"));
    }

    @Test void retriesTransientFailureWithoutDuplicateEffect() {
        TransactionProcessor p = new TransactionProcessor();
        p.simulateTransientFailureOnce("T1");
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"500",1));
        assertEquals(TransactionStatus.RETRY_PENDING, p.status("T1"));
        assertEquals(BigDecimal.ZERO, p.balance("A1"));
        p.retryPending();
        assertEquals(TransactionStatus.PROCESSED, p.status("T1"));
        assertEquals(new BigDecimal("500"), p.balance("A1"));
        assertEquals(2, p.result("T1").attempts());
    }

    @Test void retryExhaustionMovesTransactionToFailedWithoutApplyingEffect() {
        TransactionProcessor p = new TransactionProcessor(2);
        p.simulateTransientFailures("T1", 2);
        p.submit(tx("T1", "R1", "A1", TransactionType.CREDIT, "500", 1));
        assertEquals(TransactionStatus.RETRY_PENDING, p.status("T1"));
        p.retryPending();
        assertEquals(TransactionStatus.FAILED, p.status("T1"));
        assertEquals(BigDecimal.ZERO, p.balance("A1"));
        assertEquals(2, p.result("T1").attempts());
    }

    @Test void invalidSequenceDoesNotBlockNextValidSequence() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"100",1));
        p.submit(tx("BAD","RB","A1",TransactionType.DEBIT,"-10",2));
        p.submit(tx("T3","R3","A1",TransactionType.CREDIT,"25",3));
        assertEquals(TransactionStatus.FAILED, p.status("BAD"));
        assertEquals(TransactionStatus.PROCESSED, p.status("T3"));
        assertEquals(new BigDecimal("125"), p.balance("A1"));
    }

    @Test void summaryReportsStatusCountsAndBalances() {
        TransactionProcessor p = new TransactionProcessor();
        p.submit(tx("T1","R1","A1",TransactionType.CREDIT,"100",1));
        p.submit(tx("T1","R1X","A1",TransactionType.CREDIT,"100",2));
        assertEquals(1L, p.summary().counts().get(TransactionStatus.PROCESSED));
        assertEquals(1L, p.summary().counts().get(TransactionStatus.DUPLICATE));
        assertEquals("100", p.summary().balances().get("A1"));
    }
}
