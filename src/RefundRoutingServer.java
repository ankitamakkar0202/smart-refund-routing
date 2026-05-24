import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server exposing the refund routing engine via three endpoints.
 *
 * <p>Uses the JDK's built-in {@code com.sun.net.httpserver.HttpServer} — no
 * external web framework required. Available since Java 6; no {@code --add-modules}
 * flag needed on Java 11+.
 *
 * <p>SRP: this class is purely an HTTP adapter. All business logic lives in
 * {@link RefundRoutingEngine}.
 *
 * <p>Endpoints:
 * <pre>
 *   POST /api/v1/refund/route   — routing decision
 *   GET  /api/v1/channels       — live channel metadata
 *   GET  /api/v1/health         — health check
 * </pre>
 */
public final class RefundRoutingServer {

    private final HttpServer httpServer;
    private final RefundRoutingEngine engine;

    public RefundRoutingServer(RefundRoutingConfig config) throws IOException {
        this.engine = new RefundRoutingEngine(config);

        int port = config.getServerPort();
        this.httpServer = HttpServer.create(new InetSocketAddress(port), /*backlog*/ 32);

        httpServer.createContext("/api/v1/refund/route", new RouteHandler());
        httpServer.createContext("/api/v1/channels",     new ChannelsHandler());
        httpServer.createContext("/api/v1/health",       new HealthHandler());

        // 4 worker threads — enough for a microservice; daemon so JVM exits cleanly
        httpServer.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "refund-http-worker");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        httpServer.start();
        StructuredLogger.event("server_started")
                .field("port", httpServer.getAddress().getPort())
                .info();
    }

    public void stop(int delaySeconds) {
        httpServer.stop(delaySeconds);
        engine.close();
        StructuredLogger.event("server_stopped").info();
    }

    // ─── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        RefundRoutingConfig config = new RefundRoutingConfig();
        RefundRoutingServer server = new RefundRoutingServer(config);
        server.start();

        System.out.println("Smart Refund Routing System listening on port "
                + config.getServerPort());
        System.out.println("Endpoints:");
        System.out.println("  POST http://localhost:" + config.getServerPort() + "/api/v1/refund/route");
        System.out.println("  GET  http://localhost:" + config.getServerPort() + "/api/v1/channels");
        System.out.println("  GET  http://localhost:" + config.getServerPort() + "/api/v1/health");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.stop(2);
        }, "shutdown-hook"));
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/refund/route
     * Accepts a JSON {@link RefundRequest}, returns a JSON {@link RoutingDecision}.
     */
    private class RouteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                String body = readBody(exchange);
                RefundRequest req = RefundRequest.fromJson(body);
                RoutingDecision decision = engine.route(req);

                int status = resolveStatus(decision);
                send(exchange, status, decision.toJson());

            } catch (IllegalArgumentException e) {
                String detail = escape(e.getMessage());
                send(exchange, 400,
                        "{\"error\":\"Bad Request\",\"detail\":\"" + detail + "\"}");
            } catch (Exception e) {
                StructuredLogger.event("handler_exception")
                        .field("endpoint", "/api/v1/refund/route")
                        .field("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                        .error();
                send(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            }
        }
    }

    /**
     * GET /api/v1/channels
     * Returns a JSON array of all registered {@link ChannelMetadata}.
     */
    private class ChannelsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            List<ChannelMetadata> channels = engine.getRegistry().getAllChannels();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < channels.size(); i++) {
                if (i > 0) sb.append(",");
                ChannelMetadata m = channels.get(i);
                sb.append("{")
                  .append("\"channel\":\"").append(m.channel.name()).append("\",")
                  .append("\"successRate\":").append(m.successRate).append(",")
                  .append("\"costPerTxn\":").append(m.costPerTxn).append(",")
                  .append("\"avgSettlementHrs\":").append(m.avgSettlementHrs).append(",")
                  .append("\"available\":").append(m.available)
                  .append("}");
            }
            sb.append("]");
            send(exchange, 200, sb.toString());
        }
    }

    /**
     * GET /api/v1/health
     * Returns {@code {"status":"UP"}} — used by load balancers and monitoring.
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            send(exchange, 200, "{\"status\":\"UP\"}");
        }
    }

    // ─── HTTP status resolution ───────────────────────────────────────────────

    /**
     * Maps an applied-rule sentinel to the appropriate HTTP status code.
     * <ul>
     *   <li>RATE_LIMITED → 429</li>
     *   <li>VALIDATION_ERROR / INTERNAL_ERROR → 400 / 500</li>
     *   <li>everything else → 200</li>
     * </ul>
     */
    private static int resolveStatus(RoutingDecision decision) {
        if (decision.selectedChannel == RefundChannel.ERROR) {
            if ("RATE_LIMITED".equals(decision.appliedRule))      return 429;
            if ("VALIDATION_ERROR".equals(decision.appliedRule))  return 400;
            if ("INTERNAL_ERROR".equals(decision.appliedRule))    return 500;
        }
        return 200;
    }

    // ─── I/O utilities ────────────────────────────────────────────────────────

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
