package com.assettrack.iot.service;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.model.session.DeviceSession;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.protocol.ProtocolHandler;
import com.assettrack.iot.service.session.SessionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class GpsServer {
    private static final Logger logger = LoggerFactory.getLogger(GpsServer.class);

    // Configuration constants
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds
    private static final int MAX_PACKET_SIZE = 2048;
    private static final int MAX_CONNECTION_AGE = 300000; // 5 minutes
    private static final int MAX_RECONNECTIONS_BEFORE_ALERT = 5;
    private static final int SUSPICIOUS_CONNECTION_THRESHOLD = 10;
    private static final long SUSPICIOUS_TIME_WINDOW = 60000; // 1 minute
    private static final int DEFAULT_TCP_PORT = 5023;
    private static final int DEFAULT_UDP_PORT = 5023;
    private static final int DEFAULT_MAX_THREADS = 10;
    private static final int DEFAULT_MAX_CONNECTIONS = 100;

    // Services and components
    private final PositionService positionService;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket udpSocket;
    private ServerSocket tcpServerSocket;

    // Configuration values
    @Value("${gps.server.tcp.port:" + DEFAULT_TCP_PORT + "}")
    private int tcpPort;

    @Value("${gps.server.udp.port:" + DEFAULT_UDP_PORT + "}")
    private int udpPort;

    @Value("${gps.server.threads:" + DEFAULT_MAX_THREADS + "}")
    private int maxThreads;

    @Value("${gps.server.max.connections:" + DEFAULT_MAX_CONNECTIONS + "}")
    private int maxConnections;

    @Autowired private ProtocolDetector protocolDetector;
    @Autowired private List<ProtocolHandler> protocolHandlers;
    @Autowired private SessionManager sessionManager;

    // Connection tracking
    private final Map<SocketAddress, DeviceSession> addressToSessionMap = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> activeConnections = new ConcurrentHashMap<>();
    private final Set<String> blacklistedIps = ConcurrentHashMap.newKeySet();

    // Connection tracking class
    private class DeviceConnection {
        final String imei;
        final String ip;
        long lastSeen;
        int connectionCount;
        long firstSeen;
        boolean flagged;

        DeviceConnection(String imei, String ip, long lastSeen, int connectionCount) {
            this.imei = imei;
            this.ip = ip;
            this.lastSeen = lastSeen;
            this.connectionCount = connectionCount;
            this.firstSeen = System.currentTimeMillis();
            this.flagged = false;
        }

        boolean isSuspicious() {
            long connectionInterval = lastSeen - firstSeen;
            return connectionCount > SUSPICIOUS_CONNECTION_THRESHOLD &&
                    connectionInterval < SUSPICIOUS_TIME_WINDOW;
        }
    }

    public GpsServer(PositionService positionService,
                     @Value("${gps.server.threads:" + DEFAULT_MAX_THREADS + "}") int maxThreads) {
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
        logger.info("Configuration - TCP Port: {}, UDP Port: {}, Max Threads: {}, Max Connections: {}",
                tcpPort, udpPort, maxThreads, maxConnections);

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
        logger.info("Initiating graceful shutdown...");

        // Step 1: Shutdown thread pool
        shutdownThreadPool();

        // Step 2: Close network sockets
        closeNetworkResources();

        // Step 3: Clean up sessions and connections
        cleanupResources();

        logger.info("Graceful shutdown completed");
    }

    private void shutdownThreadPool() {
        logger.debug("Shutting down thread pool");
        threadPool.shutdown(); // Disable new tasks from being submitted

        try {
            // Wait a while for existing tasks to terminate
            if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Forcing shutdown of remaining threads");
                threadPool.shutdownNow(); // Cancel currently executing tasks

                // Wait again for tasks to respond to being cancelled
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Thread pool did not terminate properly");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Thread pool shutdown interrupted", e);
            // (Re-)Cancel if current thread also interrupted
            threadPool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    private void closeNetworkResources() {
        logger.debug("Closing network resources");

        // Close UDP socket
        if (udpSocket != null && !udpSocket.isClosed()) {
            try {
                udpSocket.close();
                logger.debug("UDP socket closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing UDP socket", e);
            }
        }

        // Close TCP server socket
        if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
            try {
                tcpServerSocket.close();
                logger.debug("TCP server socket closed successfully");
            } catch (IOException e) {
                logger.warn("Error closing TCP server socket", e);
            }
        }
    }

    private void cleanupResources() {
        logger.debug("Cleaning up resources");

        // Clear session maps
        addressToSessionMap.clear();
        activeConnections.clear();

        // Optionally: Notify session manager about shutdown
        try {
            sessionManager.onShutdown();
            logger.debug("Session manager notified about shutdown");
        } catch (Exception e) {
            logger.warn("Error notifying session manager", e);
        }
    }

    @Async
    protected void startTcpServer() {
        logger.info("Starting TCP server on port {}", tcpPort);
        try {
            tcpServerSocket = new ServerSocket(tcpPort);
            tcpServerSocket.setSoTimeout(SOCKET_TIMEOUT);

            while (running.get()) {
                try {
                    Socket clientSocket = tcpServerSocket.accept();
                    if (shouldAcceptConnection(clientSocket)) {
                        threadPool.execute(() -> handleTcpClient(clientSocket));
                    }
                } catch (SocketTimeoutException e) {
                    logger.trace("TCP accept timeout (normal operation)");
                } catch (IOException e) {
                    handleServerError(e);
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
        try {
            udpSocket = new DatagramSocket(udpPort);
            udpSocket.setSoTimeout(SOCKET_TIMEOUT);

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (running.get()) {
                try {
                    udpSocket.receive(packet);
                    if (!blacklistedIps.contains(packet.getAddress().getHostAddress())) {
                        threadPool.execute(() -> handleUdpPacket(packet));
                    }
                    packet.setLength(buffer.length);
                } catch (SocketTimeoutException e) {
                    logger.trace("UDP receive timeout (normal operation)");
                } catch (IOException e) {
                    handleServerError(e);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start UDP server on port {}", udpPort, e);
        } finally {
            logger.info("UDP server on port {} has stopped", udpPort);
            if (udpSocket != null) {
                udpSocket.close();
            }
        }
    }

    private boolean shouldAcceptConnection(Socket clientSocket) throws IOException {
        String clientIp = clientSocket.getInetAddress().getHostAddress();

        if (activeConnections.size() >= maxConnections) {
            logger.warn("Max connections reached ({}), rejecting new connection from {}",
                    maxConnections, clientIp);
            clientSocket.close();
            return false;
        }

        if (blacklistedIps.contains(clientIp)) {
            logger.warn("Rejecting connection from blacklisted IP: {}", clientIp);
            clientSocket.close();
            return false;
        }

        clientSocket.setSoTimeout(SOCKET_TIMEOUT);
        logger.info("New TCP connection from {}:{}",
                clientIp, clientSocket.getPort());
        return true;
    }

    private void handleServerError(IOException e) {
        if (!running.get()) {
            logger.info("Server stopping due to shutdown request");
            return;
        }
        logger.error("Server error", e);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleTcpClient(Socket clientSocket) {
        final long connectionStart = System.currentTimeMillis();
        String clientAddress = clientSocket.getInetAddress().getHostAddress();
        int clientPort = clientSocket.getPort();

        try (InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            int bytesRead = input.read(buffer);

            if (bytesRead > 0) {
                DeviceMessage message = processProtocolMessage(Arrays.copyOf(buffer, bytesRead));
                if (message != null) {
                    message.setRemoteAddress(clientSocket.getRemoteSocketAddress());
                    handleDeviceMessage(message, clientSocket, output, clientAddress, clientPort);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling client {}:{} - {}", clientAddress, clientPort, e.getMessage());
        } finally {
            cleanupConnection(clientSocket, "normal");
            logger.info("Connection from {}:{} closed after {} ms",
                    clientAddress, clientPort, System.currentTimeMillis() - connectionStart);
        }
    }

    private void handleDeviceMessage(DeviceMessage message, Socket socket,
                                     OutputStream output, String clientAddress, int clientPort)
            throws Exception {
        DeviceSession session = sessionManager.getOrCreateSession(
                message.getImei(), message.getProtocol(), message.getRemoteAddress());

        addressToSessionMap.put(message.getRemoteAddress(), session);
        message.addParsedData("sessionId", session.getSessionId());

        processResponse(output, message, clientAddress, clientPort);

        if (shouldKeepConnectionOpen(message.getProtocol())) {
            handlePersistentConnection(socket, session, message.getProtocol());
        }
    }

    private void handlePersistentConnection(Socket socket, DeviceSession session, String protocol) throws IOException {
        final int bufferSize = getBufferSizeForProtocol(protocol);
        try (InputStream input = socket.getInputStream();
             OutputStream output = socket.getOutputStream()) {

            byte[] buffer = new byte[bufferSize];
            socket.setSoTimeout(getProtocolTimeout(protocol));

            while (!socket.isClosed()) {
                try {
                    int bytesRead = input.read(buffer);
                    if (bytesRead == -1) break;

                    byte[] data = Arrays.copyOf(buffer, bytesRead);
                    DeviceMessage message = processProtocolMessage(data);

                    if (message != null) {
                        updateSessionIfNeeded(session, message);
                        processResponse(output, message,
                                socket.getInetAddress().getHostAddress(), socket.getPort());
                        processPositionIfAvailable(message);
                    }
                } catch (SocketTimeoutException e) {
                    logger.debug("Connection timeout for session {}", session.getSessionId());
                    break;
                } catch (IOException e) {
                    logger.error("I/O error for session {}", session.getSessionId(), e);
                    throw e;
                } catch (Exception e) {
                    logger.error("Unexpected error for session {}", session.getSessionId(), e);
                    break;
                }
            }
        } finally {
            if (!socket.isClosed()) {
                socket.close();
            }
        }
    }

    private void updateSessionIfNeeded(DeviceSession session, DeviceMessage message) {
        if (message.getImei() != null && !message.getImei().equals(session.getImei())) {
            session.setImei(message.getImei());
            sessionManager.updateSession(session);
            logger.debug("Updated IMEI for session {} to {}",
                    session.getSessionId(), message.getImei());
        }
    }

    private void processPositionIfAvailable(DeviceMessage message) {
        if (message.getParsedData() != null &&
                message.getParsedData().containsKey("position")) {
            Position position = (Position) message.getParsedData().get("position");
            if (position.getDevice() == null) {
                Device device = new Device();
                device.setImei(message.getImei());
                device.setProtocolType(message.getProtocol());
                position.setDevice(device);
            }
            positionService.processAndSavePosition(position);
        }
    }

    private DeviceMessage processProtocolMessage(byte[] data) {
        if (data == null || data.length == 0) {
            logger.error("Null or empty data received for protocol processing");
            return null;
        }

        try {
            ProtocolDetector.ProtocolDetectionResult detection = protocolDetector.detect(data);
            String protocol = detection.getProtocol();
            String packetType = detection.getPacketType();
            String version = detection.getVersion();

            logger.debug("Detected protocol: {} (Type: {}, Version: {})",
                    protocol, packetType, version);

            if ("UNKNOWN".equals(protocol)) {
                logger.warn("Unrecognized protocol format. Error: {}", detection.getError());
                return createErrorResponse("UNSUPPORTED_PROTOCOL",
                        "No protocol detector matched this packet format");
            }

            if ("TELTONIKA".equals(protocol) && "IMEI".equals(packetType)) {
                return handleTeltonikaImei(data);
            }

            return handleWithProtocolHandlers(data, protocol, packetType, version);
        } catch (Exception e) {
            logger.error("Error processing protocol message", e);
            return createErrorResponse("PROCESSING_ERROR", e.getMessage());
        }
    }

    private DeviceMessage handleTeltonikaImei(byte[] data) {
        if (data.length < 4) {
            logger.error("Invalid Teltonika IMEI packet: too short ({} bytes)", data.length);
            return createErrorResponse("INVALID_IMEI", "Packet too short");
        }

        int declaredLength = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (data.length != declaredLength + 2) {
            logger.error("Invalid Teltonika IMEI length: declared {} but got {} bytes",
                    declaredLength, data.length - 2);
            return createErrorResponse("INVALID_IMEI", "Length mismatch");
        }

        try {
            String imei = new String(data, 2, 15, StandardCharsets.US_ASCII);
            if (!imei.matches("^\\d{15}$")) {
                logger.error("Invalid IMEI format: {}", imei);
                return createErrorResponse("INVALID_IMEI", "Must be 15 digits");
            }

            DeviceMessage message = new DeviceMessage();
            message.setProtocol("TELTONIKA");
            message.setImei(imei);
            message.setMessageType("IMEI");
            message.addParsedData("response", new byte[]{0x01});
            return message;
        } catch (Exception e) {
            logger.error("IMEI processing error", e);
            return createErrorResponse("IMEI_ERROR", e.getMessage());
        }
    }

    private DeviceMessage handleWithProtocolHandlers(byte[] data, String protocol,
                                                     String packetType, String version) {
        List<String> attemptedHandlers = new ArrayList<>();

        for (ProtocolHandler handler : protocolHandlers) {
            if (handler.canHandle(protocol, version)) {
                attemptedHandlers.add(handler.getClass().getSimpleName());
                DeviceMessage message = handler.handle(data);
                if (message != null) {
                    return message;
                }
            }
        }

        if (!attemptedHandlers.isEmpty()) {
            logger.error("All handlers failed for {} {} (v{})", protocol, packetType, version);
        } else {
            logger.error("No handlers available for {} {} (v{})", protocol, packetType, version);
        }

        return createErrorResponse("NO_HANDLER", "No available handler could process this message");
    }

    private DeviceMessage createErrorResponse(String errorCode, String errorMessage) {
        DeviceMessage errorMsg = new DeviceMessage();
        errorMsg.setMessageType("ERROR");
        errorMsg.addParsedData("errorCode", errorCode);
        errorMsg.addParsedData("errorMessage", errorMessage);
        return errorMsg;
    }

    private void processResponse(OutputStream output, DeviceMessage message,
                                 String clientAddress, int clientPort) {
        try {
            if (message == null || message.getParsedData() == null) {
                logger.warn("Invalid message structure from {}", clientAddress);
                return;
            }

            Object responseObj = message.getParsedData().get("response");
            if (!(responseObj instanceof byte[])) {
                logger.error("Invalid response type from {}", clientAddress);
                return;
            }

            byte[] responseBytes = (byte[]) responseObj;
            output.write(responseBytes);
            output.flush();

            logResponse(message, responseBytes.length, clientAddress, clientPort);
        } catch (IOException e) {
            logger.error("Failed to send response to {}:{}", clientAddress, clientPort, e);
        } catch (Exception e) {
            logger.error("Error processing response for {}:{}", clientAddress, clientPort, e);
        }
    }

    private void logResponse(DeviceMessage message, int length, String address, int port) {
        String messageType = message.getMessageType();
        if (messageType == null) {
            logger.warn("Message type not set from {}:{}", address, port);
            return;
        }

        if ("ERROR".equals(messageType)) {
            logger.warn("Sent error response to {}:{} ({} bytes) - Error: {}",
                    address, port, length, message.getError() != null ? message.getError() : "Unknown");
        } else {
            logger.info("Sent {} response to {}:{} ({} bytes)",
                    messageType, address, port, length);
        }
    }

    private void handleUdpPacket(DatagramPacket packet) {
        String clientAddress = packet.getAddress().getHostAddress();
        int clientPort = packet.getPort();
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

        logUdpPacket(clientAddress, clientPort, data);

        DeviceMessage message = processProtocolMessage(data);
        if (message != null && message.getParsedData() != null) {
            handleUdpMessage(message, packet);
        }
    }

    private void handleUdpMessage(DeviceMessage message, DatagramPacket packet) {
        String clientAddress = packet.getAddress().getHostAddress();
        int clientPort = packet.getPort();

        DeviceSession session = sessionManager.getOrCreateSession(
                message.getImei(), message.getProtocol(), packet.getSocketAddress());
        addressToSessionMap.put(packet.getSocketAddress(), session);

        if (message.getParsedData().containsKey("position")) {
            processUdpPosition(message.getParsedData().get("position"), clientAddress, clientPort, message);
        }

        Object responseObj = message.getParsedData().get("response");
        if (responseObj instanceof byte[]) {
            sendUdpResponse(packet, (byte[]) responseObj);
        }
    }

    private void processUdpPosition(Object positionObj, String clientAddress,
                                    int clientPort, DeviceMessage message) {
        if (!(positionObj instanceof Position)) {
            logger.warn("Invalid UDP position type from {}:{}", clientAddress, clientPort);
            return;
        }

        Position position = (Position) positionObj;
        if (position.getDevice() == null || position.getDevice().getImei() == null) {
            logger.warn("Invalid UDP position data from {}:{}", clientAddress, clientPort);
            return;
        }

        try {
            Position savedPosition = positionService.processAndSavePosition(position);
            if (savedPosition != null && savedPosition.getId() != null) {
                logger.info("Saved UDP position for device {}", position.getDevice().getImei());
                trackConnection(message, clientAddress);
            }
        } catch (Exception e) {
            logger.error("Failed to save UDP position", e);
        }
    }

    private void sendUdpResponse(DatagramPacket receivedPacket, byte[] response) {
        try {
            DatagramPacket responsePacket = new DatagramPacket(
                    response, response.length,
                    receivedPacket.getAddress(), receivedPacket.getPort());
            udpSocket.send(responsePacket);
            logger.debug("Sent UDP response to {}:{} ({} bytes)",
                    receivedPacket.getAddress().getHostAddress(),
                    receivedPacket.getPort(),
                    response.length);
        } catch (IOException e) {
            logger.error("Failed to send UDP response", e);
        }
    }

    private void logUdpPacket(String address, int port, byte[] data) {
        if (logger.isDebugEnabled()) {
            logger.debug("UDP packet from {}:{} ({} bytes)", address, port, data.length);
        } else {
            logger.info("UDP packet from {}:{} ({} bytes)", address, port, data.length);
        }
    }

    private void cleanupConnection(Socket clientSocket, String reason) {
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                int clientPort = clientSocket.getPort();
                addressToSessionMap.remove(clientSocket.getRemoteSocketAddress());
                clientSocket.close();
                logger.info("Closed connection with {}:{} (Reason: {})",
                        clientAddress, clientPort, reason);
            } catch (IOException e) {
                logger.warn("Error closing socket", e);
            }
        }
    }

    private void trackConnection(DeviceMessage message, String clientAddress) {
        if (message == null || message.getImei() == null) return;

        String imei = message.getImei();
        DeviceConnection conn = activeConnections.compute(imei, (k, v) ->
                v == null ? new DeviceConnection(imei, clientAddress, System.currentTimeMillis(), 1)
                        : new DeviceConnection(imei, clientAddress, System.currentTimeMillis(), v.connectionCount + 1));

        if (conn.connectionCount > MAX_RECONNECTIONS_BEFORE_ALERT) {
            logger.warn("Frequent reconnections from IMEI: {} ({} times)", imei, conn.connectionCount);
        }

        if (conn.isSuspicious()) {
            handleSuspiciousConnection(conn, clientAddress, imei);
        }
    }

    private void handleSuspiciousConnection(DeviceConnection conn, String clientAddress, String imei) {
        logger.error("SUSPICIOUS CONNECTION PATTERN from IMEI: {} - {} connections in {} ms",
                imei, conn.connectionCount, (conn.lastSeen - conn.firstSeen));
        conn.flagged = true;

        if (conn.connectionCount > SUSPICIOUS_CONNECTION_THRESHOLD * 2) {
            blacklistedIps.add(clientAddress);
            logger.warn("IP {} blacklisted due to suspicious activity", clientAddress);
        }
    }

    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupStaleConnections() {
        long now = System.currentTimeMillis();
        activeConnections.entrySet().removeIf(entry ->
                now - entry.getValue().lastSeen > 3600000); // 1 hour timeout
    }

    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupBlacklist() {
        logger.info("Current blacklist size: {}", blacklistedIps.size());
    }

    private boolean shouldKeepConnectionOpen(String protocol) {
        return "TELTONIKA".equals(protocol) || "GT06".equals(protocol);
    }

    private int getBufferSizeForProtocol(String protocol) {
        return 8192; // 8KB default
    }

    private int getProtocolTimeout(String protocol) {
        return "GT06".equals(protocol) ? 30000 : 60000;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}