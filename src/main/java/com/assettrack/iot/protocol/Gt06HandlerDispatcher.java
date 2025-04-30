package com.assettrack.iot.protocol;

import org.apache.coyote.ProtocolException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Gt06HandlerDispatcher {
    @Autowired
    private Gt06LoginHandler loginHandler;

    @Autowired
    private Gt06LocationHandler locationHandler;

    @Autowired
    private ProtocolDetector protocolDetector;

    public void handle(byte[] data) throws ProtocolException {
        String packetType = protocolDetector.getPacketType(data);

        switch (packetType) {
            case "LOGIN":
                loginHandler.handle(data);
                break;
            case "LOCATION":
                locationHandler.handle(data);
                break;
            default:
                throw new ProtocolException("Unsupported GT06 packet type: " + packetType);
        }
    }
}