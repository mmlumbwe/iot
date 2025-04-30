package com.assettrack.iot.protocol;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for retrieving protocol handlers with version support.
 * Handlers are automatically discovered and indexed by their protocol and version.
 */
@Component
public class ProtocolHandlerFactory {
    private final Map<String, ProtocolHandler> handlers = new HashMap<>();
    private final ApplicationContext applicationContext;

    @Autowired
    public ProtocolHandlerFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        // Discover and index all ProtocolHandler implementations
        applicationContext.getBeansOfType(ProtocolHandler.class)
                .forEach((beanName, handler) -> {
                    Protocol annotation = handler.getClass().getAnnotation(Protocol.class);
                    if (annotation != null) {
                        // Index by both protocol and protocol+version
                        String protocolKey = annotation.value().toUpperCase();
                        String versionedKey = protocolKey + "_" + annotation.version().toUpperCase();

                        handlers.put(versionedKey, handler);

                        // Set default handler if none exists for this protocol
                        if (!handlers.containsKey(protocolKey)) {
                            handlers.put(protocolKey, handler);
                        }
                    }
                });
    }

    /**
     * Gets the appropriate handler for the protocol and version
     * @param protocol The protocol name (e.g., "GT06")
     * @param version The protocol version (e.g., "CODEC8")
     * @return The protocol handler instance
     * @throws IllegalArgumentException if no handler is found
     */
    public ProtocolHandler getHandler(String protocol, String version) {
        String normalizedProtocol = protocol.toUpperCase();
        String normalizedVersion = version.toUpperCase();

        // First try protocol_version
        String versionedKey = normalizedProtocol + "_" + normalizedVersion;
        ProtocolHandler handler = handlers.get(versionedKey);

        // Fall back to protocol only
        if (handler == null) {
            handler = handlers.get(normalizedProtocol);
        }

        if (handler == null) {
            // Provide helpful error with available handlers
            String available = handlers.keySet().stream()
                    .filter(k -> k.startsWith(normalizedProtocol))
                    .collect(Collectors.joining(", "));

            throw new IllegalArgumentException(
                    String.format("No handler found for protocol %s version %s. Available: %s",
                            protocol, version, available));
        }

        return handler;
    }

    /**
     * Gets all available protocol versions
     * @return Map of protocol to list of versions
     */
    public Map<String, List<String>> getAvailableProtocols() {
        Map<String, List<String>> result = new HashMap<>();

        handlers.forEach((key, handler) -> {
            Protocol annotation = handler.getClass().getAnnotation(Protocol.class);
            if (annotation != null) {
                String protocol = annotation.value();
                String version = annotation.version();
                result.computeIfAbsent(protocol, k -> new ArrayList<>()).add(version);
            }
        });

        return result;
    }
}