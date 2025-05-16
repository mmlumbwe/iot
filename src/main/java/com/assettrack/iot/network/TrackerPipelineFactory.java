package com.assettrack.iot.network;

import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.session.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import com.assettrack.iot.protocol.BaseProtocolDecoder;
import com.assettrack.iot.protocol.BaseProtocolEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrackerPipelineFactory extends ChannelInitializer<Channel> {

    private final BaseProtocolDecoder decoder;
    private final BaseProtocolEncoder encoder;
    private final SessionManager sessionManager;

    @Autowired
    public TrackerPipelineFactory(
            BaseProtocolDecoder decoder,
            BaseProtocolEncoder encoder,
            SessionManager sessionManager) {
        this.decoder = decoder;
        this.encoder = encoder;
        this.sessionManager = sessionManager;
    }

    @Autowired
    private NetworkMessageHandler networkMessageHandler;

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // Add timeout handler
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // Add protocol handlers
        pipeline.addLast("decoder", decoder);
        pipeline.addLast("encoder", encoder);

        // Add business logic handler
        pipeline.addLast("handler", networkMessageHandler);

        // Add logging handler for debugging
        pipeline.addLast("logger", new LoggingHandler(LogLevel.INFO));
    }
}