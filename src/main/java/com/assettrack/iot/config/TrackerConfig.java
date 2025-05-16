package com.assettrack.iot.config;

import com.assettrack.iot.session.SessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.assettrack.iot.session.cache.CacheManager;

@Configuration
@EnableScheduling
public class TrackerConfig {

    @Bean
    public SessionManager sessionManager() {
        return new SessionManager();
    }

    @Bean
    public CacheManager cacheManager() {
        return new CacheManager();
    }
}