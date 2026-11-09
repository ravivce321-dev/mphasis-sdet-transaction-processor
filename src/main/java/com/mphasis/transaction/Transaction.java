package com.mphasis.transaction;

import java.math.BigDecimal;

public record Transaction(String transactionId, String requestId, String accountId,
                          TransactionType type, BigDecimal amount, String currency,
                          long sequenceNumber) { }
