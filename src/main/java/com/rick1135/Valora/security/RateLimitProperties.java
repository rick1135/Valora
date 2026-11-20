package com.rick1135.Valora.security;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "security.rate-limit")
public class RateLimitProperties {
    private boolean enabled = true;
    private boolean failOpen = true;

    @Min(1)
    private int authRequestsPerMinute = 15;

    @Min(1)
    private int apiRequestsPerMinute = 120;

    @Min(1)
    private int windowSeconds = 60;
}
