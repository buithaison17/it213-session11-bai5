package com.example.bai5.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankingTransferRequest {
    @NotBlank(message = "Message cannot be blank")
    private String message;

    @NotBlank(message = "UserId is required")
    private String userId;

    private String department;
}