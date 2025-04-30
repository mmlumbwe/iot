package com.assettrack.iot;

import com.assettrack.iot.security.AuthService;
import com.assettrack.iot.security.PayloadValidator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestConfig {
    @Bean
    @Primary
    public AuthService testAuthService() {
        return new AuthService() {
            @Override
            public boolean authenticate(String imei, String protocol, byte[] authData) {
                return true;
            }
        };
    }

    @Bean
    public PayloadValidator payloadValidator() {
        return new PayloadValidator(); // or mock if appropriate
    }
}