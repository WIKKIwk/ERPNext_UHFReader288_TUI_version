package uhf.tui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Supplier;

public final class AgentServer {
  public static final class Status {
    public final boolean readerConnected;
    public final long tagsTotal;
    public final int tagsRate;

    public Status(boolean readerConnected, long tagsTotal, int tagsRate) {
      this.readerConnected = readerConnected;
      this.tagsTotal = tagsTotal;
      this.tagsRate = tagsRate;
    }
  }

  private final int port;
  private final Supplier<Status> statusSupplier;
  private HttpServer server;

  public AgentServer(int port, Supplier<Status> statusSupplier) {
    this.port = port;
    this.statusSupplier = statusSupplier;
  }

  public boolean start() {
    try {
      server = HttpServer.create(new InetSocketAddress(port), 0);
      server.createContext("/", this::handle);
      server.setExecutor(null);
      server.start();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private void handle(HttpExchange ex) throws IOException {
    String method = ex.getRequestMethod();
    Headers headers = ex.getResponseHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Access-Control-Allow-Methods", "GET, OPTIONS");
    headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    if ("OPTIONS".equalsIgnoreCase(method)) {
      ex.sendResponseHeaders(204, -1);
      return;
    }
    String path = ex.getRequestURI() == null ? "/" : ex.getRequestURI().getPath();
    String body;
    if ("/health".equals(path) || "/api/ping".equals(path) || "/api/agent/ping".equals(path)) {
      body = "{\"ok\":true,\"ts\":" + Instant.now().toEpochMilli() + "}";
    } else if ("/api/status".equals(path) || "/status".equals(path) || "/api/agent/status".equals(path)) {
      Status st = statusSupplier == null ? new Status(false, 0, 0) : statusSupplier.get();
      body = "{\"ok\":true,\"reader_connected\":" + st.readerConnected
          + ",\"tags_total\":" + st.tagsTotal
          + ",\"tags_rate\":" + st.tagsRate
          + ",\"ts\":" + Instant.now().toEpochMilli() + "}";
    } else {
      body = "{\"ok\":true,\"ts\":" + Instant.now().toEpochMilli() + "}";
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    headers.add("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }
}
