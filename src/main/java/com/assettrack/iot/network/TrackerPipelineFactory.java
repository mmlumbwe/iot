package com.assettrack.iot.network;

import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.Gt06Handler;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.session.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrackerPipelineFactory extends ChannelInitializer<Channel> {

    private final ProtocolDetector protocolDetector;
    private final SessionManager sessionManager;
    private final Gt06Handler gt06Handler; // Using your existing handler
    private final NetworkMessageHandler networkMessageHandler;

    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager,
            Gt06Handler gt06Handler,
            NetworkMessageHandler networkMessageHandler) {
        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.gt06Handler = gt06Handler;
        this.networkMessageHandler = networkMessageHandler;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // 1. Timeout handler
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 2. Log raw incoming data
        pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.DEBUG));

        // 3. Add your Gt06Handler (which includes protocol detection and decoding)
        pipeline.addLast("gt06Handler", gt06Handler);

        // 4. Add your business logic handler
        pipeline.addLast("messageHandler", networkMessageHandler);

        // 5. Final logging
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.INFO));
    }
}