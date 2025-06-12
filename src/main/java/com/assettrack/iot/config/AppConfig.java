package com.assettrack.iot.config;

import com.assettrack.iot.handler.network.AcknowledgementHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.repository.PositionRepository;
import com.assettrack.iot.service.GpsServer;
import com.assettrack.iot.service.PositionService;
import com.assettrack.iot.session.SessionManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public CommandLineRunner demo(PositionRepository repository) {
        return args -> {
            // Init test data if needed
        };
    }

    @Bean
    @DependsOn({"transactionManager", "entityManagerFactory"})
    public GpsServer gpsServer(PositionService positionService,
                               @Value("${gps.server.threads:10}") int threadPoolSize) {
        return new GpsServer(positionService, threadPoolSize);
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

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
                    factory.setConnectTimeout(30000);
                    factory.setReadTimeout(30000);
                    return factory;
                }).build();
    }

    @Bean
    public AcknowledgementHandler acknowledgementHandler() {
        return new AcknowledgementHandler();
    }

    @Bean
    public ProtocolDetectionHandler protocolDetectionHandler(ProtocolDetector protocolDetector, TeltonikaHandler teltonikaHandler, Gt06Handler gt06Handler) {
        ProtocolDetectionHandler handler = new ProtocolDetectionHandler(protocolDetector);
        logger.info("Created ProtocolDetectionHandler bean with instance ID: {}", System.identityHashCode(handler));
        return handler;
    }

    @Bean
    @ConditionalOnProperty(name = "protocol.gt06.enabled", havingValue = "true", matchIfMissing = true)
    public Gt06Handler gt06Handler(SessionManager sessionManager,
                                   ProtocolDetector protocolDetector,
                                   AcknowledgementHandler acknowledgementHandler) {
        logger.info("Creating Gt06Handler bean with dependencies");
        return new Gt06Handler(sessionManager, protocolDetector, acknowledgementHandler);
    }

    @Bean
    @ConditionalOnProperty(name = "protocol.teltonika.enabled", havingValue = "true", matchIfMissing = true)
    public TeltonikaHandler teltonikaHandler() {
        logger.info("Creating TeltonikaHandler bean");
        TeltonikaHandler handler = new TeltonikaHandler();

        // Set default validation mode if needed
        //handler.setValidationMode(TeltonikaHandler.ValidationMode.STRICT);

        return handler;
    }

}
