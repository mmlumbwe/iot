package com.assettrack.iot.network;

import com.assettrack.iot.protocol.*;
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
    private final Gt06Handler gt06Handler;
    //private final Tk103Handler tk103Handler;
    //private final TeltonikaHandler teltonikaHandler;

    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager,
            Gt06Handler gt06Handler
            //Tk103Handler tk103Handler,
            //TeltonikaHandler teltonikaHandler
    ) {
        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.gt06Handler = gt06Handler;
        //this.tk103Handler = tk103Handler;
        //this.teltonikaHandler = teltonikaHandler;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // Add timeout handler (30s read timeout)
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // Log raw incoming data (for debugging)
        pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.DEBUG));

        // Protocol detection and base decoding
        pipeline.addLast("baseDecoder", new BaseProtocolDecoder(sessionManager, protocolDetector));

        // Add protocol-specific decoders
        pipeline.addLast("gt06Handler", gt06Handler);
        //pipeline.addLast("tk103Handler", tk103Handler);
        //pipeline.addLast("teltonikaHandler", teltonikaHandler);

        // Log processed messages (for monitoring)
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.INFO));
    }
}