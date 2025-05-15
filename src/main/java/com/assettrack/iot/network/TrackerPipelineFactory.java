package com.assettrack.iot.network;

import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import com.assettrack.iot.protocol.BaseProtocolDecoder;
import com.assettrack.iot.protocol.BaseProtocolEncoder;
import com.assettrack.iot.session.ConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrackerPipelineFactory extends ChannelInitializer<Channel> {

    private final BaseProtocolDecoder decoder;
    private final BaseProtocolEncoder encoder;
    private final ConnectionManager connectionManager;

    @Autowired
    public TrackerPipelineFactory(
            BaseProtocolDecoder decoder,
            BaseProtocolEncoder encoder,
            ConnectionManager connectionManager) {
        this.decoder = decoder;
        this.encoder = encoder;
        this.connectionManager = connectionManager;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // Add timeout handler (30 seconds)
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // Add protocol handlers
        pipeline.addLast("decoder", decoder);
        pipeline.addLast("encoder", encoder);

        // Add business logic handler
        pipeline.addLast("handler", new NetworkMessageHandler(connectionManager));
    }
}