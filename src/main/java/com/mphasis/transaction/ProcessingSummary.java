package com.mphasis.transaction;

import java.util.Map;
public record ProcessingSummary(Map<TransactionStatus, Long> counts, Map<String, String> balances) { }
