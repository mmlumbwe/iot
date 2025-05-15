package com.assettrack.iot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.assettrack.iot.session.ConnectionManager;
import com.assettrack.iot.session.cache.CacheManager;

@Configuration
@EnableScheduling
public class TrackerConfig {

    @Bean
    public ConnectionManager connectionManager() {
        return new ConnectionManager();
    }

    @Bean
    public CacheManager cacheManager() {
        return new CacheManager();
    }
}