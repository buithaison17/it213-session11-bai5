package com.example.bai5.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.langfuse.client.LangfuseClient;
import com.langfuse.client.resources.prompts.requests.GetPromptRequest;
import com.langfuse.client.resources.prompts.types.Prompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PromptRegistryService {

    private final LangfuseClient langfuseClient;
    private final Cache<String, String> promptCache;

    private static final String DEFAULT_FALLBACK_PROMPT =
            "Bạn là trợ lý AI Banking chuyên nghiệp. Khách hàng: {{user_name}}. Quy định: {{bank_policy}}. Hãy bóc tách dữ liệu giao dịch.";

    public PromptRegistryService(
            LangfuseClient langfuseClient,
            @Value("${cache.prompt.ttl-seconds:60}") long ttlSeconds,
            @Value("${cache.prompt.max-size:500}") long maxSize) {

        this.langfuseClient = langfuseClient;
        this.promptCache = Caffeine.newBuilder()
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .maximumSize(maxSize)
                .build();
    }

    public String getPrompt(String promptName, Map<String, Object> variables) {
        long startTime = System.currentTimeMillis();
        String rawTemplate = promptCache.getIfPresent(promptName);

        if (rawTemplate != null) {
            log.info("[CACHE HIT] Loaded prompt '{}' in {} ms", promptName, (System.currentTimeMillis() - startTime));
        } else {
            log.info("[CACHE MISS] Fetching prompt '{}' from Langfuse...", promptName);
            try {
                GetPromptRequest request = GetPromptRequest.builder().label("production").build();
                Prompt prompt = langfuseClient.prompts().get(promptName, request);
                rawTemplate = (prompt != null && prompt.isText() && prompt.getText().isPresent())
                        ? prompt.getText().get().getPrompt()
                        : (prompt != null ? prompt.toString() : DEFAULT_FALLBACK_PROMPT);

                promptCache.put(promptName, rawTemplate);
            } catch (Exception ex) {
                log.warn("[FALLBACK] Langfuse unreachable: {}. Using default fallback prompt.", ex.getMessage());
                rawTemplate = DEFAULT_FALLBACK_PROMPT;
            }
        }
        return compileVariables(rawTemplate, variables);
    }

    private String compileVariables(String template, Map<String, Object> variables) {
        if (template == null || variables == null || variables.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }
}