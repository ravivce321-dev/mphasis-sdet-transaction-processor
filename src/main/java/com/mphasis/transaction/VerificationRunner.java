package com.mphasis.transaction;

import java.math.BigDecimal;

/** Dependency-free executable verification of the core acceptance scenarios. */
public class VerificationRunner {
    private static int passed;
    public static void main(String[] args) {
        run("credit/debit", VerificationRunner::creditDebit);
        run("out-of-order buffer and drain", VerificationRunner::ordering);
        run("duplicate transaction ID", VerificationRunner::duplicateBusinessId);
        run("duplicate request ID", VerificationRunner::duplicateRequestId);
        run("negative amount validation", VerificationRunner::invalidAmount);
        run("transient retry is idempotent", VerificationRunner::retrySuccess);
        run("retry exhaustion", VerificationRunner::retryExhaustion);
        run("invalid sequence does not block", VerificationRunner::invalidDoesNotBlock);
        run("summary metrics", VerificationRunner::summary);
        System.out.println("VERIFICATION RESULT: " + passed + "/9 scenarios passed");
    }
    private static void run(String name, Runnable test) { test.run(); passed++; System.out.println("PASS: " + name); }
    private static Transaction tx(String id, String req, String account, TransactionType type, String amount, long seq) {
        return new Transaction(id, req, account, type, new BigDecimal(amount), "INR", seq);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void eq(Object expected, Object actual, String message) { check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual); }
    private static void creditDebit() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("1","r1","a",TransactionType.CREDIT,"100",1)); p.submit(tx("2","r2","a",TransactionType.DEBIT,"40",2)); eq(new BigDecimal("60"),p.balance("a"),"balance"); }
    private static void ordering() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("2","r2","a",TransactionType.DEBIT,"40",2)); eq(TransactionStatus.PENDING,p.status("2"),"pending"); p.submit(tx("1","r1","a",TransactionType.CREDIT,"100",1)); eq(TransactionStatus.PROCESSED,p.status("2"),"drained"); eq(new BigDecimal("60"),p.balance("a"),"balance"); }
    private static void duplicateBusinessId() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("1","r1","a",TransactionType.CREDIT,"100",1)); p.submit(tx("1","r2","a",TransactionType.CREDIT,"100",2)); eq(TransactionStatus.DUPLICATE,p.statusByRequest("r2"),"duplicate"); eq(new BigDecimal("100"),p.balance("a"),"no double effect"); }
    private static void duplicateRequestId() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("1","r1","a",TransactionType.CREDIT,"100",1)); p.submit(tx("2","r1","a",TransactionType.CREDIT,"50",2)); eq(TransactionStatus.DUPLICATE,p.statusByRequest("r1"),"duplicate delivery"); eq(new BigDecimal("100"),p.balance("a"),"no second effect"); }
    private static void invalidAmount() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("1","r1","a",TransactionType.DEBIT,"-10",1)); eq(TransactionStatus.FAILED,p.status("1"),"failed validation"); eq(BigDecimal.ZERO,p.balance("a"),"no effect"); }
    private static void retrySuccess() { TransactionProcessor p=new TransactionProcessor(); p.simulateTransientFailureOnce("1"); p.submit(tx("1","r1","a",TransactionType.CREDIT,"500",1)); eq(TransactionStatus.RETRY_PENDING,p.status("1"),"retry pending"); p.retryPending(); eq(TransactionStatus.PROCESSED,p.status("1"),"processed after retry"); eq(new BigDecimal("500"),p.balance("a"),"single effect"); eq(2,p.result("1").attempts(),"attempt count"); }
    private static void retryExhaustion() { TransactionProcessor p=new TransactionProcessor(2); p.simulateTransientFailures("1",2); p.submit(tx("1","r1","a",TransactionType.CREDIT,"500",1)); p.retryPending(); eq(TransactionStatus.FAILED,p.status("1"),"exhausted"); eq(BigDecimal.ZERO,p.balance("a"),"no effect"); }
    private static void invalidDoesNotBlock() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("1","r1","a",TransactionType.CREDIT,"100",1)); p.submit(tx("bad","rb","a",TransactionType.DEBIT,"-10",2)); p.submit(tx("3","r3","a",TransactionType.CREDIT,"25",3)); eq(TransactionStatus.PROCESSED,p.status("3"),"later event"); eq(new BigDecimal("125"),p.balance("a"),"balance"); }
    private static void summary() { TransactionProcessor p=new TransactionProcessor(); p.submit(tx("1","r1","a",TransactionType.CREDIT,"100",1)); p.submit(tx("1","r2","a",TransactionType.CREDIT,"100",2)); eq(1L,p.summary().counts().get(TransactionStatus.PROCESSED),"processed count"); eq(1L,p.summary().counts().get(TransactionStatus.DUPLICATE),"duplicate count"); }
}
