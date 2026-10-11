package com.settle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "settle")
public record SettleProperties(String wikiDir, int demoMinuteSeconds, int llmTimeoutSeconds, Readonly readonly) {
    public record Readonly(String username, String password) {}
}
