package com.example.bai5.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDetails {
    private String sender;
    private String receiver;
    private String bankCode;
    private BigDecimal amount;
    private String description;
    private String status;
}