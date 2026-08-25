package com.example.bai5.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class LlmCostCalculator {

    // Giá quy ước trên 1 triệu token (USD)
    private static final BigDecimal GEMINI_INPUT_PER_M = new BigDecimal("0.075");
    private static final BigDecimal GEMINI_OUTPUT_PER_M = new BigDecimal("0.30");
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    public BigDecimal calculateCost(String modelName, int promptTokens, int completionTokens) {
        if (modelName == null || modelName.contains("qwen") || modelName.contains("llama") || modelName.contains("ollama")) {
            // Mô hình On-Premise/Local không tốn phí API token
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }

        BigDecimal promptCost = new BigDecimal(promptTokens)
                .multiply(GEMINI_INPUT_PER_M)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

        BigDecimal completionCost = new BigDecimal(completionTokens)
                .multiply(GEMINI_OUTPUT_PER_M)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

        return promptCost.add(completionCost).setScale(6, RoundingMode.HALF_UP);
    }
}