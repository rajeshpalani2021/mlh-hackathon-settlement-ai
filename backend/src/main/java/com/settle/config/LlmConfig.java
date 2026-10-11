package com.settle.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * ChatClient for NVIDIA's OpenAI-compatible endpoint. URL, model and sampling options come from
 * spring.ai.openai.* in application.yml; NVIDIA-specific chat_template_kwargs are added here as a real
 * JSON boolean (property binding would turn it into a string).
 */
@Configuration
public class LlmConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, SettleProperties props) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setExtraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", props.llmEnableThinking())));
        return builder.defaultOptions(options).build();
    }
}
