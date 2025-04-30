package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class ProtocolHandlerDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolHandlerDispatcher.class);

    private final List<ProtocolHandler> handlers;
    private final ProtocolDetector protocolDetector;

    private DeviceMessage lastMessage;

    @Autowired
    public ProtocolHandlerDispatcher(
            Tk103Handler tk103Handler,
            Gt06Handler gt06Handler,
            TeltonikaHandler teltonikaHandler,
            ProtocolDetector protocolDetector) {
        this.handlers = Arrays.asList(
                tk103Handler,
                gt06Handler,
                teltonikaHandler
        );
        this.protocolDetector = protocolDetector;
    }

    public DeviceMessage handle(byte[] data) throws ProtocolException {
        try {
            String protocol = protocolDetector.detectProtocol(data);
            lastMessage = null;

            for (ProtocolHandler handler : handlers) {
                if (handler.canHandle(protocol, null)) {
                    try {
                        lastMessage = handler.handle(data);
                        return lastMessage;
                    } catch (ProtocolException e) {
                        continue; // Try next handler
                    }
                }
            }
            throw new ProtocolException("No handler found for protocol: " + protocol);
        } catch (Exception e) {
            logger.error("Protocol handling error", e);
            throw new ProtocolException("Protocol handling error", e);
        }
    }

    public boolean hasResponse() {
        return lastMessage != null && lastMessage.getParsedData().containsKey("response");
    }

    public byte[] getResponse() {
        return hasResponse() ? (byte[]) lastMessage.getParsedData().get("response") : null;
    }

    public DeviceMessage getLastMessage() {
        return lastMessage;
    }
}