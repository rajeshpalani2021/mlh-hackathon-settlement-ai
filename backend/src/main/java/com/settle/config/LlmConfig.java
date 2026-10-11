package com.settle.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared ChatClient. Endpoint (Gemini's OpenAI-compatible API), key, model and sampling options
 * come from spring.ai.openai.* in application.yml.
 */
@Configuration
public class LlmConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
