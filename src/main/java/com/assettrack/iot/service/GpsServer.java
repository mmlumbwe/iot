package com.assettrack.iot.service;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.protocol.Gt06HandlerDispatcher;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.protocol.ProtocolHandlerDispatcher;
import com.assettrack.iot.repository.PositionRepository;
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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class GpsServer {
    private static final Logger logger = LoggerFactory.getLogger(GpsServer.class);
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds
    private static final int MAX_PACKET_SIZE = 2048;
    private static final int MAX_CONNECTION_AGE = 300000; // 5 minutes

    private final ProtocolService protocolService;
    private final PositionRepository positionRepository;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final PositionService positionService;
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
    private Gt06HandlerDispatcher gt06Dispatcher;

    @Autowired
    private ProtocolHandlerDispatcher protocolDispatcher;

    @Autowired
    public GpsServer(ProtocolService protocolService,
                     PositionRepository positionRepository, PositionService positionService,
                     @Value("${gps.server.threads:10}") int maxThreads) {
        this.positionService = positionService;
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("Thread pool size must be positive");
        }

        this.protocolService = protocolService;
        this.positionRepository = positionRepository;
        this.maxThreads = maxThreads;
        this.threadPool = Executors.newFixedThreadPool(maxThreads);
        logger.info("Initialized GPS Server with thread pool size: {}", maxThreads);
        logger.info("GPS Server configured with:");
        logger.info("- TCP Port: {}", tcpPort);
        logger.info("- UDP Port: {}", udpPort);
        logger.info("- Thread Pool Size: {}", maxThreads);
        logger.info("GPS Server instance created with {} threads", maxThreads);
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

        try (InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            int bytesRead = input.read(buffer);

            if (bytesRead > 0) {
                byte[] receivedData = Arrays.copyOf(buffer, bytesRead);

                logger.info("Received raw payload from {}:{} ({} bytes)",
                        clientAddress, clientPort, bytesRead);
                logHexDump(receivedData);

                // First try protocol dispatcher
                try {
                    String protocol = protocolDetector.detectProtocol(receivedData);
                    if (!"UNKNOWN".equals(protocol)) {
                        protocolDispatcher.handle(receivedData);
                        DeviceMessage message = protocolDispatcher.handle(receivedData);

                        // If we get here, the packet was handled successfully
                        // Send response if needed (extracted from protocol-specific handler)
                        if (protocolDispatcher.hasResponse()) {
                            byte[] response = protocolDispatcher.getResponse();
                            logger.info("Sending response to {}:{} ({} bytes)",
                                    clientAddress, clientPort, response.length);
                            output.write(response);
                            output.flush();
                        }
                        if (message != null && message.getParsedData().containsKey("position")) {
                            Position position = (Position) message.getParsedData().get("position");
                            positionService.processPosition(position);
                        }

                        return;
                    }
                } catch (ProtocolException e) {
                    logger.error("Protocol handling error from {}: {}", clientAddress, e.getMessage());
                }

                // Fallback to legacy protocol service only if dispatcher couldn't handle it
                try {
                    DeviceMessage message = protocolService.parseData(
                            protocolDetector.detectProtocol(receivedData),
                            receivedData
                    );

                    if (message != null && message.getParsedData().containsKey("response")) {
                        byte[] response = (byte[]) message.getParsedData().get("response");
                        logger.info("Sending legacy response to {}:{} ({} bytes)",
                                clientAddress, clientPort, response.length);
                        output.write(response);
                        output.flush();
                    }
                } catch (ProtocolException e) {
                    logger.error("Legacy protocol processing error from {}: {}",
                            clientAddress, e.getMessage());
                    byte[] errorResponse = protocolService.generateErrorResponse(
                            protocolDetector.detectProtocol(receivedData),
                            e
                    );
                    if (errorResponse != null) {
                        output.write(errorResponse);
                        output.flush();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("I/O error with client {}:{} - {}",
                    clientAddress, clientPort, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error handling client {}:{} - {}",
                    clientAddress, clientPort, e.getMessage());
        }
    }

    private void logHexDump(byte[] data) {
        StringBuilder hexDump = new StringBuilder();
        StringBuilder asciiDump = new StringBuilder();

        for (int i = 0; i < data.length; i++) {
            // Hex portion
            hexDump.append(String.format("%02X ", data[i]));

            // ASCII portion
            char c = (data[i] >= 32 && data[i] < 127) ? (char) data[i] : '.';
            asciiDump.append(c);

            // Format 16 bytes per line
            if ((i + 1) % 16 == 0 || i == data.length - 1) {
                logger.info("{}{} |{}|",
                        String.format("%04X: ", i - 15),
                        hexDump.toString(),
                        asciiDump.toString());
                hexDump.setLength(0);
                asciiDump.setLength(0);
            }
        }
    }

    private void handleUdpPacket(DatagramPacket packet) {
        String clientAddress = packet.getAddress().getHostAddress();
        int clientPort = packet.getPort();
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

        // 1. Log the incoming UDP payload
        logUdpPacket(clientAddress, clientPort, data);

        try {
            // 2. Detect protocol type
            String protocolType = protocolDetector.detectProtocol(data);
            if ("GT06".equals(protocolType)) {
                try {
                    protocolDispatcher.handle(data);
                } catch (ProtocolException e) {
                    logger.error("Error handling protocol message", e);
                }
            }
            if ("UNKNOWN".equals(protocolType)) {
                logger.warn("Unknown protocol from {}:{} - first bytes: {}",
                        clientAddress, clientPort, bytesToHex(Arrays.copyOf(data, Math.min(data.length, 8))));
                return;
            }

            // 3. Parse protocol data
            DeviceMessage message = protocolService.parseData(protocolType, data);
            if (message == null) {
                logger.warn("No message parsed from {}:{} for protocol {}",
                        clientAddress, clientPort, protocolType);
                return;
            }

            // 4. Process the message
            processUdpMessage(clientAddress, clientPort, message);

            // 5. Send response if available
            sendUdpResponse(packet, message);

        } catch (ProtocolException e) {
            logger.error("Protocol error processing UDP from {}:{} - {}",
                    clientAddress, clientPort, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error handling UDP packet from {}:{} - {}",
                    clientAddress, clientPort, e.getMessage(), e);
        }
    }

    private void logUdpPacket(String address, int port, byte[] data) {
        if (logger.isDebugEnabled()) {
            String hexDump = formatHexDump(data);
            logger.debug("UDP packet from {}:{} ({} bytes):\n{}",
                    address, port, data.length, hexDump);
        } else {
            logger.info("UDP packet from {}:{} ({} bytes) - {}...",
                    address, port, data.length, bytesToHex(Arrays.copyOf(data, Math.min(data.length, 8))));
        }
    }

    private void processUdpMessage(String address, int port, DeviceMessage message) {
        logger.info("Processing {} {} message from {}:{}",
                message.getProtocol(), message.getMessageType(), address, port);

        // Handle position data
        if (message.getParsedData().containsKey("position")) {
            Position position = (Position) message.getParsedData().get("position");
            positionService.processPosition(position);
        }

        // Handle status updates
        /*if (message.getParsedData().containsKey("status")) {
            deviceService.updateStatus(
                    message.getImei(),
                    (Map<String, Object>) message.getParsedData().get("status")
            );
        }*/
    }

    private void sendUdpResponse(DatagramPacket receivedPacket, DeviceMessage message) {
        try {
            if (message.getParsedData().containsKey("response")) {
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
            }
        } catch (IOException e) {
            logger.error("Failed to send UDP response", e);
        }
    }

    // Utility methods
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
            // Offset
            output.append(String.format("%04X: ", offset));

            // Hex bytes
            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    output.append(String.format("%02X ", data[offset + i]));
                } else {
                    output.append("   ");
                }
                if (i == 7) output.append(" ");
            }

            // ASCII representation
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