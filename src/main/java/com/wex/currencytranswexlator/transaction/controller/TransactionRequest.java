package com.wex.currencytranswexlator.transaction.controller;

import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound DTO for POST /api/v1/transactions.
 *
 * Validation enforced by Bean Validation (@Valid on controller parameter).
 * Field-level errors produce structured 400 responses via GlobalExceptionHandler.
 *
 * transactionDate: future dates are rejected (@PastOrPresent).
 * amountUsd: must be positive and > 0. @Digits enforces max precision accepted.
 * description: printable characters enforced by @Pattern in addition to @Size.
 */
public record TransactionRequest(

    @NotBlank(message = "Description is required")
    @Size(max = 50, message = "Description must not exceed 50 characters")
    @Pattern(regexp = "^[\\x20-\\x7E]+$", message = "Description must contain only printable characters")
    @Schema(example = "Grocery shopping at Acme Market")
    String description,

    @NotNull(message = "Transaction date is required")
    @PastOrPresent(message = "Transaction date cannot be in the future")
    LocalDate transactionDate,

    @NotNull(message = "Purchase amount is required")
    @Positive(message = "Purchase amount must be a positive value")
    @Digits(integer = 15, fraction = 2, message = "Purchase amount must be a valid monetary amount rounded to the nearest cent")
    @Schema(example = "123.45")
    BigDecimal amountUsd

) {}
