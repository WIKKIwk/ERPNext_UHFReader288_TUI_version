package uhf.erp;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ErpAgentRegistrar {
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final Supplier<List<String>> uiUrls;
  private ErpConfig cfg;
  private ScheduledFuture<?> task;
  private volatile long lastOkAt = 0;
  private volatile long lastErrAt = 0;
  private volatile String lastErrMsg = "";

  public ErpAgentRegistrar(ErpConfig cfg, Supplier<List<String>> uiUrls) {
    this.cfg = cfg == null ? new ErpConfig() : cfg;
    this.uiUrls = uiUrls;
    schedule();
  }

  public synchronized void applyConfig(ErpConfig next) {
    this.cfg = next == null ? new ErpConfig() : next;
    cancel();
    schedule();
  }

  public void shutdown() {
    cancel();
    scheduler.shutdownNow();
  }

  public long lastOkAt() {
    return lastOkAt;
  }

  public long lastErrAt() {
    return lastErrAt;
  }

  public String lastErrMsg() {
    return lastErrMsg == null ? "" : lastErrMsg;
  }

  private boolean enabled() {
    if (cfg == null) return false;
    if (safe(cfg.baseUrl).isEmpty()) return false;
    if (safe(cfg.agentEndpoint).isEmpty()) return false;
    return !normalizeAuth(cfg.auth).isEmpty() || !safe(cfg.secret).isEmpty();
  }

  private synchronized void schedule() {
    if (!enabled()) return;
    int interval = Math.max(2000, cfg.agentIntervalMs);
    task = scheduler.scheduleWithFixedDelay(this::safeRegister, 200, interval, TimeUnit.MILLISECONDS);
  }

  private synchronized void cancel() {
    if (task != null) {
      task.cancel(false);
      task = null;
    }
  }

  private void safeRegister() {
    try {
      registerOnce();
      lastOkAt = System.currentTimeMillis();
      lastErrAt = 0;
      lastErrMsg = "";
    } catch (Exception e) {
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = e.getMessage();
    }
  }

  private void registerOnce() throws Exception {
    if (!enabled()) return;
    String url = joinUrl(cfg.baseUrl, cfg.agentEndpoint);
    String auth = normalizeAuth(cfg.auth);
    String secret = safe(cfg.secret);

    List<String> urls = uiUrls == null ? List.of() : uiUrls.get();
    String uiHost = "";
    int uiPort = 0;
    if (!urls.isEmpty()) {
      try {
        URI u = URI.create(urls.get(0));
        uiHost = safe(u.getHost());
        uiPort = u.getPort();
      } catch (Exception ignored) {
      }
    }
    String agentId = safe(cfg.agentId);
    if (agentId.isEmpty()) agentId = safe(cfg.device);
    if (agentId.isEmpty()) agentId = "rfid-agent";

    String payload = buildPayload(agentId, urls, uiHost, uiPort);
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("POST");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(8000);
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    if (!auth.isEmpty()) conn.setRequestProperty("Authorization", auth);
    if (!secret.isEmpty()) conn.setRequestProperty("x-rfidenter-token", secret);
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    conn.setFixedLengthStreamingMode(body.length);
    try (OutputStream out = conn.getOutputStream()) {
      out.write(body);
    }
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new RuntimeException("Agent register HTTP " + code);
    }
  }

  private String buildPayload(String agentId, List<String> urls, String uiHost, int uiPort) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"agent_id\":\"").append(escape(agentId)).append("\",");
    sb.append("\"device\":\"").append(escape(safe(cfg.device))).append("\",");
    sb.append("\"ui_urls\":[");
    for (int i = 0; i < urls.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(escape(urls.get(i))).append("\"");
    }
    sb.append("],");
    sb.append("\"ui_host\":\"").append(escape(uiHost)).append("\",");
    sb.append("\"ui_port\":").append(uiPort <= 0 ? 0 : uiPort).append(",");
    sb.append("\"platform\":\"").append(escape(System.getProperty("os.name", ""))).append("\",");
    sb.append("\"version\":\"uhf-tui\"").append(",");
    sb.append("\"pid\":").append(ProcessHandle.current().pid()).append(",");
    sb.append("\"ts\":").append(System.currentTimeMillis());
    sb.append("}");
    return sb.toString();
  }

  private static String joinUrl(String base, String path) {
    String b = safe(base);
    String p = safe(path);
    if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
    if (!p.startsWith("/")) p = "/" + p;
    return b + p;
  }

  private static String normalizeAuth(String auth) {
    String a = safe(auth);
    if (a.isEmpty()) return "";
    return a.toLowerCase().startsWith("token ") ? a : "token " + a;
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }
}
