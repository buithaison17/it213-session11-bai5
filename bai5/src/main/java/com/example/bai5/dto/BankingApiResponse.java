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
public class BankingApiResponse<T> {
    private boolean success;
    private String traceId;
    private long latencyMs;
    private BigDecimal costUsd;
    private String modelUsed;
    private String message;
    private T data;
}