package uhf.erp;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ErpPusher {
  private final ConcurrentLinkedQueue<ErpTagEvent> queue = new ConcurrentLinkedQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private ErpConfig cfg;
  private ScheduledFuture<?> flushTask;
  private ScheduledFuture<?> heartbeatTask;
  private volatile long backoffUntil = 0;
  private volatile int failCount = 0;
  private volatile long lastOkAt = 0;
  private volatile long lastErrAt = 0;
  private volatile String lastErrMsg = "";

  public ErpPusher(ErpConfig cfg) {
    this.cfg = cfg == null ? new ErpConfig() : cfg;
    schedule();
  }

  public synchronized void applyConfig(ErpConfig next) {
    this.cfg = next == null ? new ErpConfig() : next;
    cancelTasks();
    schedule();
  }

  public ErpConfig config() {
    return cfg;
  }

  public boolean isEnabled() {
    return enabled();
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

  public boolean testOnce() {
    if (cfg == null || safe(cfg.baseUrl).isEmpty()) {
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = "ERP URL not set";
      return false;
    }
    String base = safe(cfg.baseUrl);
    String auth = normalizeAuth(cfg.auth);
    try {
      int pingCode = request(joinUrl(base, "/api/method/rfidenter.rfidenter.api.ping"), "GET", auth, null);
      if (pingCode < 200 || pingCode >= 300) {
        lastErrAt = System.currentTimeMillis();
        lastErrMsg = "ERP ping HTTP " + pingCode;
        return false;
      }
      if (auth.isEmpty()) {
        lastErrAt = System.currentTimeMillis();
        lastErrMsg = "ERP token missing";
        return false;
      }
      int authCode = request(joinUrl(base, "/api/method/rfidenter.rfidenter.api.list_agents"), "POST", auth, "{}");
      if (authCode < 200 || authCode >= 300) {
        lastErrAt = System.currentTimeMillis();
        lastErrMsg = "ERP auth HTTP " + authCode;
        return false;
      }
      lastOkAt = System.currentTimeMillis();
      lastErrAt = 0;
      lastErrMsg = "";
      return true;
    } catch (Exception e) {
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = e.getMessage();
      return false;
    }
  }

  public boolean pushTestTags(int count) {
    if (cfg == null || safe(cfg.baseUrl).isEmpty()) {
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = "ERP URL not set";
      return false;
    }
    if (cfg.endpoint == null || cfg.endpoint.isBlank()) {
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = "ERP endpoint not set";
      return false;
    }
    int n = Math.max(1, Math.min(count, 200));
    List<ErpTagEvent> tags = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (int i = 0; i < n; i++) {
      String epc = "TEST" + Long.toHexString(now + i);
      tags.add(new ErpTagEvent(epc, "TEST", -40, 1, "0.0.0.0", now));
    }
    try {
      postTags(tags, false);
      lastOkAt = System.currentTimeMillis();
      lastErrAt = 0;
      lastErrMsg = "";
      return true;
    } catch (Exception e) {
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = e.getMessage();
      return false;
    }
  }

  public void enqueue(ErpTagEvent evt) {
    if (evt == null) return;
    if (!enabled()) return;
    queue.add(evt);
    trimQueue();
  }

  public void shutdown() {
    cancelTasks();
    scheduler.shutdownNow();
  }

  private boolean enabled() {
    return cfg != null
        && cfg.enabled
        && cfg.baseUrl != null
        && !cfg.baseUrl.isBlank()
        && cfg.endpoint != null
        && !cfg.endpoint.isBlank();
  }

  private void trimQueue() {
    int max = Math.max(0, cfg.maxQueue);
    if (max == 0) return;
    while (queue.size() > max) {
      queue.poll();
    }
  }

  private synchronized void schedule() {
    if (!enabled()) return;
    long batchMs = Math.max(10, cfg.batchMs);
    flushTask = scheduler.scheduleWithFixedDelay(this::safeFlush, batchMs, batchMs, TimeUnit.MILLISECONDS);
    int hb = cfg.heartbeatMs;
    if (hb > 0) {
      heartbeatTask = scheduler.scheduleWithFixedDelay(this::safeHeartbeat, hb, hb, TimeUnit.MILLISECONDS);
    }
  }

  private synchronized void cancelTasks() {
    if (flushTask != null) {
      flushTask.cancel(false);
      flushTask = null;
    }
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
      heartbeatTask = null;
    }
  }

  private void safeFlush() {
    try {
      flushOnce();
    } catch (Exception ignored) {
    }
  }

  private void safeHeartbeat() {
    try {
      sendHeartbeat();
    } catch (Exception ignored) {
    }
  }

  private void flushOnce() throws Exception {
    if (!enabled()) return;
    if (queue.isEmpty()) return;
    long now = System.currentTimeMillis();
    if (backoffUntil > now) return;

    List<ErpTagEvent> batch = new ArrayList<>();
    int max = Math.max(1, cfg.maxBatch);
    for (int i = 0; i < max; i++) {
      ErpTagEvent e = queue.poll();
      if (e == null) break;
      batch.add(e);
    }
    if (batch.isEmpty()) return;

    try {
      postTags(batch, false);
      failCount = 0;
      backoffUntil = 0;
      lastOkAt = System.currentTimeMillis();
      lastErrAt = 0;
      lastErrMsg = "";
    } catch (Exception e) {
      failCount++;
      lastErrAt = System.currentTimeMillis();
      lastErrMsg = e.getMessage();
      long backoff = Math.min(30000, 500L * (1L << Math.min(10, failCount)));
      backoffUntil = System.currentTimeMillis() + backoff;
      for (int i = batch.size() - 1; i >= 0; i--) {
        queue.add(batch.get(i));
      }
      trimQueue();
      throw e;
    }
  }

  private void sendHeartbeat() throws Exception {
    if (!enabled()) return;
    if (cfg.heartbeatMs <= 0) return;
    postTags(List.of(), true);
    lastOkAt = System.currentTimeMillis();
    lastErrAt = 0;
    lastErrMsg = "";
  }

  private void postTags(List<ErpTagEvent> tags, boolean heartbeat) throws Exception {
    String url = joinUrl(cfg.baseUrl, cfg.endpoint);
    String auth = normalizeAuth(cfg.auth);
    String payload = buildPayload(tags, heartbeat);
    HttpURLConnection conn = requestWithBody(url, "POST", auth, payload);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new RuntimeException("ERP HTTP " + code);
    }
    String resp = readBody(conn);
    String low = resp.toLowerCase();
    if (low.contains("\"ok\":false") || low.contains("\"ok\": false")) {
      throw new RuntimeException("ERP response not ok");
    }
  }

  private String buildPayload(List<ErpTagEvent> tags, boolean heartbeat) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"device\":\"").append(escape(cfg.device)).append("\",");
    sb.append("\"tags\":[");
    for (int i = 0; i < tags.size(); i++) {
      if (i > 0) sb.append(",");
      ErpTagEvent t = tags.get(i);
      sb.append("{");
      sb.append("\"epcId\":\"").append(escape(t.epcId())).append("\",");
      sb.append("\"memId\":\"").append(escape(t.memId())).append("\",");
      sb.append("\"rssi\":").append(t.rssi()).append(",");
      sb.append("\"antId\":").append(t.antId()).append(",");
      sb.append("\"ipAddr\":\"").append(escape(t.ipAddr())).append("\",");
      sb.append("\"ts\":").append(t.ts());
      sb.append("}");
    }
    sb.append("],");
    sb.append("\"ts\":").append(Instant.now().toEpochMilli());
    if (heartbeat) sb.append(",\"heartbeat\":true");
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

  private int request(String url, String method, String authHeader, String body) throws Exception {
    HttpURLConnection conn = body == null
        ? requestNoBody(url, method, authHeader)
        : requestWithBody(url, method, authHeader, body);
    return conn.getResponseCode();
  }

  private HttpURLConnection requestNoBody(String url, String method, String authHeader) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(8000);
    if (authHeader != null && !authHeader.isEmpty()) {
      conn.setRequestProperty("Authorization", authHeader);
    }
    return conn;
  }

  private HttpURLConnection requestWithBody(String url, String method, String authHeader, String body) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(8000);
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    if (authHeader != null && !authHeader.isEmpty()) {
      conn.setRequestProperty("Authorization", authHeader);
    }
    String secret = safe(cfg.secret);
    if (!secret.isEmpty()) {
      conn.setRequestProperty("x-rfidenter-token", secret);
    }
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    conn.setFixedLengthStreamingMode(bytes.length);
    try (OutputStream out = conn.getOutputStream()) {
      out.write(bytes);
    }
    return conn;
  }

  private String readBody(HttpURLConnection conn) {
    try {
      return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      try {
        return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      } catch (Exception ignored) {
        return "";
      }
    }
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }
}
