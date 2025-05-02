package com.assettrack.iot.service;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.protocol.ProtocolHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class GpsServer {
    private static final Logger logger = LoggerFactory.getLogger(GpsServer.class);
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds
    private static final int MAX_PACKET_SIZE = 2048;
    private static final int MAX_CONNECTION_AGE = 300000; // 5 minutes

    private final PositionService positionService;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket udpSocket;

    @Value("${gps.server.tcp.port:5023}")
    private int tcpPort;

    @Value("${gps.server.udp.port:5023}")
    private int udpPort;

    @Value("${gps.server.threads:10}")
    private int maxThreads;

    @Autowired
    private ProtocolDetector protocolDetector;

    @Autowired
    private List<ProtocolHandler> protocolHandlers;

    public GpsServer(ProtocolService protocolService, PositionService positionService,
                     @Value("${gps.server.threads:10}") int maxThreads) {
        this.positionService = positionService;
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("Thread pool size must be positive");
        }

        this.maxThreads = maxThreads;
        this.threadPool = Executors.newFixedThreadPool(maxThreads);
        logger.info("Initialized GPS Server with thread pool size: {}", maxThreads);
    }

    @PostConstruct
    public void start() {
        running.set(true);
        logger.info("Initializing GPS Server...");
        logger.info("Configuration - TCP Port: {}, UDP Port: {}", tcpPort, udpPort);

        startTcpServer();
        startUdpServer();

        logger.info("GPS Server successfully started (TCP:{}, UDP:{})", tcpPort, udpPort);
    }

    @PreDestroy
    public void stop() {
        logger.info("Shutting down GPS Server...");
        running.set(false);
        gracefulShutdown();
        logger.info("GPS Server shutdown complete");
    }

    private void gracefulShutdown() {
        logger.debug("Initiating graceful shutdown of thread pool");
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Forcing shutdown of remaining threads");
                threadPool.shutdownNow();
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Thread pool failed to terminate");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Thread pool shutdown interrupted", e);
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Async
    protected void startTcpServer() {
        logger.info("Starting TCP server on port {}", tcpPort);
        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT);
            logger.info("TCP server successfully bound to port {}", tcpPort);

            while (running.get()) {
                try {
                    logger.debug("Waiting for TCP connection...");
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(SOCKET_TIMEOUT);
                    logger.info("New TCP connection from {}:{}",
                            clientSocket.getInetAddress().getHostAddress(),
                            clientSocket.getPort());

                    threadPool.execute(() -> handleTcpClient(clientSocket));
                } catch (SocketTimeoutException e) {
                    logger.trace("TCP accept timeout (normal operation)");
                } catch (IOException e) {
                    logger.error("TCP Server error", e);
                    if (!running.get()) {
                        logger.info("TCP server stopping due to shutdown request");
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        logger.warn("TCP server recovery sleep interrupted", ie);
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start TCP server on port {}", tcpPort, e);
        } finally {
            logger.info("TCP server on port {} has stopped", tcpPort);
        }
    }

    @Async
    protected void startUdpServer() {
        logger.info("Starting UDP server on port {}", udpPort);
        try (DatagramSocket socket = new DatagramSocket(udpPort)) {
            this.udpSocket = socket;
            socket.setSoTimeout(SOCKET_TIMEOUT);
            logger.info("UDP server successfully bound to port {}", udpPort);

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (running.get()) {
                try {
                    logger.trace("Waiting for UDP datagram...");
                    socket.receive(packet);
                    logger.info("Received UDP packet from {}:{} ({} bytes)",
                            packet.getAddress().getHostAddress(),
                            packet.getPort(),
                            packet.getLength());

                    threadPool.execute(() -> handleUdpPacket(packet));
                    packet.setLength(buffer.length);
                } catch (SocketTimeoutException e) {
                    logger.trace("UDP receive timeout (normal operation)");
                } catch (IOException e) {
                    logger.error("UDP Server error", e);
                    if (!running.get()) {
                        logger.info("UDP server stopping due to shutdown request");
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        logger.warn("UDP server recovery sleep interrupted", ie);
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start UDP server on port {}", udpPort, e);
        } finally {
            logger.info("UDP server on port {} has stopped", udpPort);
        }
    }

    private void handleTcpClient(Socket clientSocket) {
        String clientAddress = clientSocket.getInetAddress().getHostAddress();
        int clientPort = clientSocket.getPort();
        logger.info("Handling TCP client connection from {}:{}", clientAddress, clientPort);

        try (InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            int bytesRead = input.read(buffer);

            if (bytesRead > 0) {
                byte[] receivedData = Arrays.copyOf(buffer, bytesRead);
                logger.info("Received raw payload from {}:{} ({} bytes)",
                        clientAddress, clientPort, bytesRead);
                logHexDump(receivedData);

                DeviceMessage message = processProtocolMessage(receivedData);

                if (message != null) {
                    // Process response if available
                    processResponse(output, message, clientAddress, clientPort);
                    // Process position if available
                    processPosition(message, clientAddress, clientPort);
                }
            }
        } catch (IOException e) {
            logger.error("I/O error with client {}:{} - {}", clientAddress, clientPort, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error handling client {}:{} - {}",
                    clientAddress, clientPort, e.getMessage(), e);
        } finally {
            try {
                clientSocket.close();
                logger.info("Closed connection with {}:{}", clientAddress, clientPort);
            } catch (IOException e) {
                logger.warn("Error closing socket for {}:{} - {}",
                        clientAddress, clientPort, e.getMessage());
            }
        }
    }

    private void processResponse(OutputStream output, DeviceMessage message,
                                 String clientAddress, int clientPort) {
        try {
            if (message == null || message.getParsedData() == null) {
                logger.warn("Invalid message structure from {}", clientAddress);
                return;
            }

            Object responseObj = message.getParsedData().get("response");
            if (responseObj == null) {
                logger.error("CRITICAL: No response generated for {} (MessageType: {})",
                        clientAddress, message.getMessageType());
                return;
            }

            if (!(responseObj instanceof byte[])) {
                logger.error("Invalid response type from {}: {}",
                        clientAddress, responseObj.getClass().getSimpleName());
                return;
            }

            byte[] responseBytes = (byte[]) responseObj;
            output.write(responseBytes);
            output.flush();

            if (message.getMessageType().equals("ERROR")) {
                logger.warn("Sent error response to {}:{} ({} bytes) - Error: {}",
                        clientAddress, clientPort, responseBytes.length,
                        message.getError() != null ? message.getError() : "Unknown error");
            } else {
                logger.info("Sent {} response to {}:{} ({} bytes)",
                        message.getMessageType(),
                        clientAddress, clientPort,
                        responseBytes.length);
            }
        } catch (IOException e) {
            logger.error("Failed to send response to {}:{} - {}",
                    clientAddress, clientPort, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error processing response for {}:{} - {}",
                    clientAddress, clientPort, e.getMessage(), e);
        }
    }

    private void processPosition(DeviceMessage message, String clientAddress, int clientPort) {
        if (message.getParsedData() == null || !message.getParsedData().containsKey("position")) {
            logger.debug("No position data in message from {}:{}", clientAddress, clientPort);
            return;
        }

        Object positionObj = message.getParsedData().get("position");
        if (!(positionObj instanceof Position)) {
            logger.warn("Invalid position type from {}:{} - expected Position object",
                    clientAddress, clientPort);
            return;
        }

        Position position = (Position) positionObj;

        if (position.getDevice() == null || position.getDevice().getImei() == null) {
            logger.warn("Invalid position data from {}:{} - missing device or IMEI",
                    clientAddress, clientPort);
            return;
        }

        String imei = position.getDevice().getImei();
        logger.info("Processing position for device {}", imei);

        try {
            Position savedPosition = positionService.processAndSavePosition(position);

            if (savedPosition == null) {
                logger.error("Position save operation returned null for device {}", imei);
                return;
            }

            if (savedPosition.getId() == null) {
                logger.error("Saved position has null ID for device {}", imei);
                return;
            }

            logger.info("Successfully persisted position ID {} for device {} at {}",
                    savedPosition.getId(), imei, savedPosition.getTimestamp());

            if (logger.isDebugEnabled()) {
                logger.debug("Position details - Lat: {}, Lon: {}, Speed: {}, Valid: {}",
                        position.getLatitude(), position.getLongitude(),
                        position.getSpeed(), position.isValid());
            }
        } catch (Exception e) {
            logger.error("Failed to save position for device {}: {}", imei, e.getMessage(), e);
        }
    }

    private void handleUdpPacket(DatagramPacket packet) {
        String clientAddress = packet.getAddress().getHostAddress();
        int clientPort = packet.getPort();
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

        logUdpPacket(clientAddress, clientPort, data);

        DeviceMessage message = processProtocolMessage(data);

        if (message != null && message.getParsedData() != null) {
            Map<String, Object> parsedData = message.getParsedData();

            // Process position if available
            if (parsedData.containsKey("position")) {
                Object positionObj = parsedData.get("position");
                if (positionObj instanceof Position) {
                    Position position = (Position) positionObj;
                    if (position.getDevice() != null && position.getDevice().getImei() != null) {
                        try {
                            Position savedPosition = positionService.processAndSavePosition(position);
                            if (savedPosition != null && savedPosition.getId() != null) {
                                logger.info("Successfully saved UDP position ID {} for device {}",
                                        savedPosition.getId(),
                                        savedPosition.getDevice().getImei());
                            } else {
                                logger.error("UDP position save operation returned null or invalid position");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to save UDP position for device {}: {}",
                                    position.getDevice().getImei(), e.getMessage(), e);
                        }
                    } else {
                        logger.warn("Invalid UDP position data - missing device or IMEI");
                    }
                } else {
                    logger.warn("Invalid UDP position type in parsed data");
                }
            }

            // Send response if available
            Object responseObj = parsedData.get("response");
            if (responseObj instanceof byte[]) {
                sendUdpResponse(packet, message);
            } else if (responseObj != null) {
                logger.warn("Invalid UDP response type in parsed data");
            }
            // No response needed if responseObj is null
        }
    }

    private DeviceMessage processProtocolMessage(byte[] data) {
        try {
            String protocol = protocolDetector.detectProtocol(data);
            logger.info("Detected protocol: {}", protocol);

            for (ProtocolHandler handler : protocolHandlers) {
                if (handler.canHandle(protocol, null)) {
                    try {
                        DeviceMessage message = handler.handle(data);
                        logger.info("Processed {} message using {}",
                                protocol, handler.getClass().getSimpleName());
                        return message;
                    } catch (ProtocolException e) {
                        logger.warn("Handler {} failed to process message: {}",
                                handler.getClass().getSimpleName(), e.getMessage());
                        continue;
                    }
                }
            }

            logger.warn("No handler found for protocol: {}", protocol);
        } catch (Exception e) {
            logger.error("Error processing protocol message: {}", e.getMessage(), e);
        }
        return null;
    }

    private void logHexDump(byte[] data) {
        if (!logger.isDebugEnabled()) return;

        StringBuilder hexDump = new StringBuilder();
        StringBuilder asciiDump = new StringBuilder();

        for (int i = 0; i < data.length; i++) {
            hexDump.append(String.format("%02X ", data[i]));
            char c = (data[i] >= 32 && data[i] < 127) ? (char) data[i] : '.';
            asciiDump.append(c);

            if ((i + 1) % 16 == 0 || i == data.length - 1) {
                logger.debug("{}{} |{}|",
                        String.format("%04X: ", i - 15),
                        hexDump.toString(),
                        asciiDump.toString());
                hexDump.setLength(0);
                asciiDump.setLength(0);
            }
        }
    }

    private void logUdpPacket(String address, int port, byte[] data) {
        if (logger.isDebugEnabled()) {
            logger.debug("UDP packet from {}:{} ({} bytes):\n{}",
                    address, port, data.length, formatHexDump(data));
        } else {
            logger.info("UDP packet from {}:{} ({} bytes) - {}...",
                    address, port, data.length, bytesToHex(Arrays.copyOf(data, Math.min(data.length, 8))));
        }
    }

    private void sendUdpResponse(DatagramPacket receivedPacket, DeviceMessage message) {
        try {
            byte[] response = (byte[]) message.getParsedData().get("response");
            DatagramPacket responsePacket = new DatagramPacket(
                    response,
                    response.length,
                    receivedPacket.getAddress(),
                    receivedPacket.getPort()
            );

            udpSocket.send(responsePacket);
            logger.debug("Sent UDP response to {}:{} ({} bytes)",
                    receivedPacket.getAddress().getHostAddress(),
                    receivedPacket.getPort(),
                    response.length);
        } catch (IOException e) {
            logger.error("Failed to send UDP response", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private String formatHexDump(byte[] data) {
        StringBuilder output = new StringBuilder();
        int offset = 0;

        while (offset < data.length) {
            output.append(String.format("%04X: ", offset));

            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    output.append(String.format("%02X ", data[offset + i]));
                } else {
                    output.append("   ");
                }
                if (i == 7) output.append(" ");
            }

            output.append(" ");
            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    char c = (char) data[offset + i];
                    output.append(c >= 32 && c < 127 ? c : '.');
                }
            }

            offset += 16;
            output.append("\n");
        }

        return output.toString();
    }
}