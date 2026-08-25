package com.example.bai5.controller;

import com.example.bai5.dto.BankingApiResponse;
import com.example.bai5.dto.BankingTransferRequest;
import com.example.bai5.dto.TransactionDetails;
import com.example.bai5.service.BankingAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/banking")
@RequiredArgsConstructor
public class BankingAgentController {

    private final BankingAgentService bankingAgentService;

    @PostMapping("/process")
    public ResponseEntity<BankingApiResponse<TransactionDetails>> processTransfer(
            @Valid @RequestBody BankingTransferRequest request) {

        log.info("[REQUEST RECEIVED] Processing natural language transfer for user: {}", request.getUserId());

        BankingAgentService.ExecutionResult result = bankingAgentService.executeBankingAgent(request);

        BankingApiResponse<TransactionDetails> response = BankingApiResponse.<TransactionDetails>builder()
                .success("SUCCESS".equals(result.details().getStatus()))
                .traceId(result.traceId())
                .latencyMs(result.latencyMs())
                .costUsd(result.costUsd())
                .modelUsed(result.modelUsed())
                .message("SUCCESS".equals(result.details().getStatus())
                        ? "Bóc tách thông tin giao dịch thành công"
                        : "Bóc tách giao dịch thất bại")
                .data(result.details())
                .build();

        return ResponseEntity.ok(response);
    }
}