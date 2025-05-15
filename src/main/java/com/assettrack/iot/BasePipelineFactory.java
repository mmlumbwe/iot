package com.assettrack.iot;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import com.assettrack.iot.config.Config;
import com.assettrack.iot.config.Keys;
import com.assettrack.iot.handler.network.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public abstract class BasePipelineFactory extends ChannelInitializer<Channel> {

    @Autowired
    private ApplicationContext applicationContext;

    private final TrackerConnector connector;
    private final Config config;
    private final String protocol;
    private final int timeout;

    public BasePipelineFactory(TrackerConnector connector, Config config, String protocol) {
        this.connector = connector;
        this.config = config;
        this.protocol = protocol;
        int timeout = config.getInteger(Keys.PROTOCOL_TIMEOUT.withPrefix(protocol));
        this.timeout = timeout != 0 ? timeout : config.getInteger(Keys.SERVER_TIMEOUT);
    }

    protected abstract void addTransportHandlers(PipelineBuilder pipeline);

    protected abstract void addProtocolHandlers(PipelineBuilder pipeline);

    @SuppressWarnings("unchecked")
    public static <T extends ChannelHandler> T getHandler(ChannelPipeline pipeline, Class<T> clazz) {
        for (Map.Entry<String, ChannelHandler> handlerEntry : pipeline) {
            ChannelHandler handler = handlerEntry.getValue();
            if (handler instanceof WrapperInboundHandler wrapperHandler) {
                handler = wrapperHandler.getWrappedHandler();
            } else if (handler instanceof WrapperOutboundHandler wrapperHandler) {
                handler = wrapperHandler.getWrappedHandler();
            }
            if (clazz.isAssignableFrom(handler.getClass())) {
                return (T) handler;
            }
        }
        return null;
    }

    private <T> T injectMembers(T object) {
        applicationContext.getAutowireCapableBeanFactory().autowireBean(object);
        return object;
    }

    @Override
    protected void initChannel(Channel channel) {
        final ChannelPipeline pipeline = channel.pipeline();

        addTransportHandlers(pipeline::addLast);

        if (timeout > 0 && !connector.isDatagram()) {
            pipeline.addLast(new IdleStateHandler(timeout, 0, 0));
        }
        pipeline.addLast(new OpenChannelHandler(connector));
        if (config.hasKey(Keys.SERVER_FORWARD)) {
            int port = config.getInteger(Keys.PROTOCOL_PORT.withPrefix(protocol));
            pipeline.addLast(injectMembers(new NetworkForwarderHandler(port)));
        }
        pipeline.addLast(new NetworkMessageHandler());
        pipeline.addLast(injectMembers(new StandardLoggingHandler(protocol)));

        if (!connector.isDatagram() && !config.getBoolean(Keys.SERVER_INSTANT_ACKNOWLEDGEMENT)) {
            pipeline.addLast(new AcknowledgementHandler());
        }

        addProtocolHandlers(handler -> {
            if (handler instanceof BaseProtocolDecoder || handler instanceof BaseProtocolEncoder) {
                injectMembers(handler);
            } else {
                if (handler instanceof ChannelInboundHandler channelHandler) {
                    handler = new WrapperInboundHandler(channelHandler);
                } else if (handler instanceof ChannelOutboundHandler channelHandler) {
                    handler = new WrapperOutboundHandler(channelHandler);
                }
            }
            pipeline.addLast(handler);
        });

        pipeline.addLast(applicationContext.getBean(RemoteAddressHandler.class));
        pipeline.addLast(applicationContext.getBean(ProcessingHandler.class));
        pipeline.addLast(applicationContext.getBean(MainEventHandler.class));
    }
}