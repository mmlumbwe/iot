package com.assettrack.iot.config;

import com.assettrack.iot.repository.DeviceRepository;
import com.assettrack.iot.security.AuthService;
import com.assettrack.iot.security.AuthServiceImpl;
import com.assettrack.iot.security.PayloadValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${security.allowed.protocols:GT06,TELTONIKA,TK103}")
    private List<String> allowedProtocols;

    @Value("${security.imei.strict:true}")
    private boolean strictImeiValidation;

    @Value("${security.payload.validate-checksum:true}")
    private boolean validateChecksum;

    @Value("${security.payload.validate-length:true}")
    private boolean validateLength;

    @Value("${security.payload.max-size:2048}")
    private int maxPayloadSize;

    /*@Bean
    public AuthService authService(DeviceRepository deviceRepository) {
        return new AuthServiceImpl(
                deviceRepository,
                allowedProtocols,
                strictImeiValidation
        );
    }*/

    @Bean
    public PayloadValidator payloadValidator() {
        PayloadValidator validator = new PayloadValidator();
        validator.setValidateChecksum(validateChecksum);
        validator.setValidateLength(validateLength);
        validator.setMaxPayloadSize(maxPayloadSize);
        return validator;
    }
}