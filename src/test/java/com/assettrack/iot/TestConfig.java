package com.assettrack.iot;

import com.assettrack.iot.handler.network.AcknowledgementHandler;
import com.assettrack.iot.network.TrackerPipelineFactory;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.BaseProtocolDecoder;
import com.assettrack.iot.protocol.BaseProtocolEncoder;
import com.assettrack.iot.security.AuthService;
import com.assettrack.iot.security.PayloadValidator;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import org.mockito.Mockito;
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

    @Bean
    public AcknowledgementHandler acknowledgementHandler() {
        return new AcknowledgementHandler();
    }

    @Bean
    public CacheManager cacheManager() {
        return new CacheManager();
    }


    /***********************/
    @Bean
    @Primary
    public TrackerPipelineFactory trackerPipelineFactory() {
        // Create mock instances for all required dependencies
        BaseProtocolDecoder mockDecoder = Mockito.mock(BaseProtocolDecoder.class);
        BaseProtocolEncoder mockEncoder = Mockito.mock(BaseProtocolEncoder.class);
        SessionManager mockSessionManager = Mockito.mock(SessionManager.class);

        // Create the factory with all mocked dependencies
        return new TrackerPipelineFactory(mockDecoder, mockEncoder, mockSessionManager);
    }

    @Bean
    @Primary
    public NetworkMessageHandler networkMessageHandler() {
        // Mock the NetworkMessageHandler that gets autowired
        return Mockito.mock(NetworkMessageHandler.class);
    }

    @Bean
    @Primary
    public BaseProtocolDecoder baseProtocolDecoder() {
        return Mockito.mock(BaseProtocolDecoder.class);
    }

    @Bean
    @Primary
    public BaseProtocolEncoder baseProtocolEncoder() {
        return Mockito.mock(BaseProtocolEncoder.class);
    }

    @Bean
    @Primary
    public SessionManager sessionManager() {
        return Mockito.mock(SessionManager.class);
    }

}