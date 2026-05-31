package com.wex.currencytranswexlator.transaction.controller;

import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(
    UUID id,
    String description,
    LocalDate transactionDate,
    BigDecimal amountUsd,
    Instant createdAt
) {
    public static TransactionResponse from(PurchaseTransaction tx) {
        return new TransactionResponse(
            tx.getId(),
            tx.getDescription(),
            tx.getTransactionDate(),
            tx.getAmountUsd(),
            tx.getCreatedAt()
        );
    }
}
