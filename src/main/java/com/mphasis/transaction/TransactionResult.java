package com.mphasis.transaction;

public record TransactionResult(String transactionId, TransactionStatus status, int attempts, String message) { }
