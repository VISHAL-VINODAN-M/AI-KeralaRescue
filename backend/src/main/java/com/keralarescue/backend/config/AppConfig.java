package com.keralarescue.backend.config;

import com.keralarescue.backend.ai.AiEngine;
import com.keralarescue.backend.ai.ClaudeClient;
import com.keralarescue.backend.notify.PushClient;
import com.keralarescue.backend.notify.SmsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Builds the optional integrations. Each one switches itself off when its settings are missing. */
@Configuration
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public AiEngine aiEngine(@Value("${rescue.ai.api-key:}") String apiKey,
                             @Value("${rescue.ai.model:claude-haiku-5-5}") String model,
                             @Value("${rescue.ai.base-url:}") String baseUrl,
                             @Value("${rescue.ai.timeout-seconds:20}") int timeoutSeconds) {
        AiEngine engine = new AiEngine(new ClaudeClient(apiKey, model, baseUrl, Duration.ofSeconds(Math.max(5, timeoutSeconds))));
        log.info(engine.isConfigured() ? "AI: Claude enabled (model {})" : "AI: no ANTHROPIC_API_KEY set, using offline rule-based triage only", model);
        return engine;
    }

    @Bean
    public SmsClient smsClient(@Value("${rescue.sms.twilio-sid:}") String sid, @Value("${rescue.sms.twilio-token:}") String token,
                               @Value("${rescue.sms.from:}") String from, @Value("${rescue.sms.base-url:}") String baseUrl) {
        SmsClient sms = new SmsClient(sid, token, from, baseUrl);
        log.info(sms.isConfigured() ? "SMS gateway: Twilio enabled" : "SMS gateway: not configured (SMS alerts off)");
        return sms;
    }

    @Bean
    public PushClient pushClient(@Value("${rescue.firebase.service-account-json:}") String json,
                                 @Value("${rescue.firebase.base-url:}") String baseUrl) {
        PushClient push = new PushClient(json, baseUrl);
        log.info(push.isConfigured() ? "Push: Firebase Cloud Messaging enabled" : "Push: not configured (push notifications off)");
        return push;
    }
}
