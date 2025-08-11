package com.mar.forex.infrastructure.broker;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class Oanda {
    @NotBlank
    private String url;        // e.g. https://api-fxpractice.oanda.com/v3
    @NotBlank private String apiKey;
    @NotBlank private String accountId;
}
