package com.assettrack.iot.config;

import com.assettrack.iot.protocol.Gt06Handler;
import com.assettrack.iot.protocol.ProtocolHandler;
import com.assettrack.iot.repository.PositionRepository;
import com.assettrack.iot.service.GpsServer;
import com.assettrack.iot.service.PositionService;
import com.assettrack.iot.service.ProtocolService;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

    /*@Bean
    public ProtocolHandler gt06Handler() {
        return new Gt06Handler();
    }*/

    @Bean
    public CommandLineRunner demo(PositionRepository repository) {
        return args -> {
            // Initialize with test data if needed
        };
    }

    @Bean
    @DependsOn({"transactionManager", "entityManagerFactory"})  // Ensure these are initialized first
    public GpsServer gpsServer(PositionService positionService,
                               @Value("${gps.server.threads:10}") int threadPoolSize) {
        return new GpsServer(positionService, threadPoolSize);
    }

    // Add this if you're using JPA
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    // Async configuration
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("GpsServer-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(30000); // 30 seconds in milliseconds
                    factory.setReadTimeout(30000);    // 30 seconds in milliseconds
                    return factory;
                })
                .build();
    }
}