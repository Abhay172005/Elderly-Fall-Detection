package com.smartelderly.config;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Fail fast when MongoDB is unreachable: requests return a clear 503 within seconds instead of hanging for the
 * driver's 30-second default, so the portal can show a useful error.
 */
@Configuration
public class MongoConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer fastFailure() {
        return builder -> builder
                .applyToClusterSettings(c -> c.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS));
    }
}
