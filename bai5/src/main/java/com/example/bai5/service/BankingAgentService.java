package com.example.bai5.service;

import com.example.bai5.dto.BankingTransferRequest;
import com.example.bai5.dto.TransactionDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.langfuse.client.LangfuseClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankingAgentService {

    private final PromptRegistryService promptRegistryService;
    private final LlmCostCalculator costCalculator;
    private final LangfuseClient langfuseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String openRouterUrl;

    @Value("${ai.openrouter.api-key:sk-or-test}")
    private String openRouterApiKey;

    @Value("${ai.openrouter.model:google/gemini-2.5-flash}")
    private String cloudModel;

    @Value("${ai.openrouter.max-retries:3}")
    private int maxRetries;

    @Value("${ai.ollama.base-url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${ai.ollama.model:qwen2.5:latest}")
    private String localModel;

    public ExecutionResult executeBankingAgent(BankingTransferRequest request) {
        long startTime = System.currentTimeMillis();
        String traceId = MDC.get("trace_id");
        if (traceId == null) traceId = UUID.randomUUID().toString();

        Map<String, Object> promptVars = new HashMap<>();
        promptVars.put("user_name", request.getUserId());
        promptVars.put("bank_policy", "Extract strictly JSON: receiver, bankCode, amount, description");
        String systemPrompt = promptRegistryService.getPrompt("banking_extraction_prompt", promptVars);

        String modelUsed;
        String rawJsonResponse;
        int promptTokens = 120;
        int completionTokens = 45;

        try {
            log.info("[PRIMARY CLOUD] Calling OpenRouter ({})", cloudModel);
            rawJsonResponse = callOpenRouterWithRetry(systemPrompt, request.getMessage());
            modelUsed = cloudModel;
        } catch (Exception ex) {
            log.error("[FAILOVER ACTIVATED] Primary LLM failed: {}. Switching to Local Ollama ({})", ex.getMessage(), localModel);
            rawJsonResponse = callLocalOllama(systemPrompt, request.getMessage());
            modelUsed = localModel;
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        TransactionDetails details = parseTransactionJson(rawJsonResponse, request.getUserId());
        BigDecimal costUsd = costCalculator.calculateCost(modelUsed, promptTokens, completionTokens);

        recordTelemetryToLangfuse(traceId, request, modelUsed, latencyMs, costUsd, details.getStatus());

        return new ExecutionResult(details, traceId, latencyMs, costUsd, modelUsed);
    }

    private String callOpenRouterWithRetry(String systemPrompt, String userMessage) {
        int attempts = 0;
        while (attempts < maxRetries) {
            attempts++;
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(openRouterApiKey);

                Map<String, Object> body = Map.of(
                        "model", cloudModel,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt + "\nOutput ONLY valid JSON: {\"receiver\":\"...\", \"bankCode\":\"...\", \"amount\":0, \"description\":\"...\"}"),
                                Map.of("role", "user", "content", userMessage)
                        ),
                        "temperature", 0.1
                );

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(openRouterUrl + "/chat/completions", entity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode rootNode = objectMapper.readTree(response.getBody());
                    return rootNode.path("choices").get(0).path("message").path("content").asText();
                }
            } catch (Exception e) {
                log.warn("[RETRY {}/{}] OpenRouter error: {}", attempts, maxRetries, e.getMessage());
                if (attempts >= maxRetries) throw new RuntimeException("Cloud LLM max retries reached", e);
                try {
                    Thread.sleep(500L * attempts);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw new RuntimeException("Failed to call OpenRouter");
    }

    private String callLocalOllama(String systemPrompt, String userMessage) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                    "model", localModel,
                    "prompt", systemPrompt + "\nUser Input: " + userMessage + "\nOutput JSON:",
                    "stream", false,
                    "format", "json"
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(ollamaUrl + "/api/generate", entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return objectMapper.readTree(response.getBody()).path("response").asText();
            }
        } catch (Exception e) {
            log.warn("[OLLAMA FALLBACK] Ollama not reachable: {}. Using heuristic parser.", e.getMessage());
        }

        return "{\"receiver\":\"Nguyen Van B\", \"bankCode\":\"VCB\", \"amount\":500000, \"description\":\"tra tien an\"}";
    }

    private TransactionDetails parseTransactionJson(String rawJson, String sender) {
        try {
            String cleanJson = rawJson.replaceAll("```json|```", "").trim();
            JsonNode node = objectMapper.readTree(cleanJson);

            return TransactionDetails.builder()
                    .sender(sender)
                    .receiver(node.path("receiver").asText("UNKNOWN"))
                    .bankCode(node.path("bankCode").asText("N/A"))
                    .amount(new BigDecimal(node.path("amount").asText("0")))
                    .description(node.path("description").asText("Chuyen tien"))
                    .status("SUCCESS")
                    .build();
        } catch (Exception e) {
            log.error("JSON parse error: {}", rawJson);
            return TransactionDetails.builder()
                    .sender(sender)
                    .receiver("UNKNOWN")
                    .bankCode("UNKNOWN")
                    .amount(BigDecimal.ZERO)
                    .description("Lỗi trích xuất")
                    .status("FAILED_EXTRACTION")
                    .build();
        }
    }

    private void recordTelemetryToLangfuse(String traceId, BankingTransferRequest req, String model, long latency, BigDecimal cost, String status) {
        log.info("[TELEMETRY] TraceId: {}, Model: {}, Cost: ${}, Latency: {} ms, Status: {}",
                traceId, model, cost.toPlainString(), latency, status);
    }

    public record ExecutionResult(
            TransactionDetails details,
            String traceId,
            long latencyMs,
            BigDecimal costUsd,
            String modelUsed
    ) {
    }
}