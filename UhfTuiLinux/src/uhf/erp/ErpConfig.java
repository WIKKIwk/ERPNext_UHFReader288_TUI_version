package uhf.erp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ErpConfig {
  private static final String DEFAULT_ENDPOINT = "/api/method/rfidenter.rfidenter.api.ingest_tags";
  private static final String DEFAULT_AGENT_ENDPOINT = "/api/method/rfidenter.rfidenter.api.register_agent";

  public boolean enabled = false;
  public String baseUrl = "";
  public String auth = "";
  public String secret = "";
  public String device = defaultDevice();
  public String endpoint = DEFAULT_ENDPOINT;
  public String agentEndpoint = DEFAULT_AGENT_ENDPOINT;
  public String agentId = "";
  public int agentIntervalMs = 10000;
  public int batchMs = 250;
  public int maxBatch = 200;
  public int maxQueue = 5000;
  public int heartbeatMs = 3000;

  public static ErpConfig load(Path file) {
    ErpConfig cfg = new ErpConfig();
    if (file == null || !Files.exists(file)) return cfg;
    Properties p = new Properties();
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      p.load(in);
      cfg.enabled = Boolean.parseBoolean(p.getProperty("enabled", String.valueOf(cfg.enabled)));
      cfg.baseUrl = p.getProperty("baseUrl", cfg.baseUrl).trim();
      cfg.auth = p.getProperty("auth", cfg.auth).trim();
      cfg.secret = p.getProperty("secret", cfg.secret).trim();
      cfg.device = p.getProperty("device", cfg.device).trim();
      cfg.endpoint = p.getProperty("endpoint", cfg.endpoint).trim();
      cfg.agentEndpoint = p.getProperty("agentEndpoint", cfg.agentEndpoint).trim();
      cfg.agentId = p.getProperty("agentId", cfg.agentId).trim();
      cfg.batchMs = parseInt(p.getProperty("batchMs"), cfg.batchMs);
      cfg.maxBatch = parseInt(p.getProperty("maxBatch"), cfg.maxBatch);
      cfg.maxQueue = parseInt(p.getProperty("maxQueue"), cfg.maxQueue);
      cfg.heartbeatMs = parseInt(p.getProperty("heartbeatMs"), cfg.heartbeatMs);
      cfg.agentIntervalMs = parseInt(p.getProperty("agentIntervalMs"), cfg.agentIntervalMs);
    } catch (IOException ignored) {
    }
    return cfg;
  }

  public void save(Path file) {
    if (file == null) return;
    Properties p = new Properties();
    p.setProperty("enabled", String.valueOf(enabled));
    p.setProperty("baseUrl", safe(baseUrl));
    p.setProperty("auth", safe(auth));
    p.setProperty("secret", safe(secret));
    p.setProperty("device", safe(device));
    p.setProperty("endpoint", safe(endpoint));
    p.setProperty("agentEndpoint", safe(agentEndpoint));
    p.setProperty("agentId", safe(agentId));
    p.setProperty("batchMs", String.valueOf(batchMs));
    p.setProperty("maxBatch", String.valueOf(maxBatch));
    p.setProperty("maxQueue", String.valueOf(maxQueue));
    p.setProperty("heartbeatMs", String.valueOf(heartbeatMs));
    p.setProperty("agentIntervalMs", String.valueOf(agentIntervalMs));
    try {
      Files.createDirectories(file.getParent());
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
        p.store(out, "ERP Push Config");
      }
    } catch (IOException ignored) {
    }
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }

  private static int parseInt(String s, int def) {
    if (s == null) return def;
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static String defaultDevice() {
    try {
      String host = InetAddress.getLocalHost().getHostName();
      if (host != null && !host.isBlank()) return host.trim();
    } catch (Exception ignored) {
    }
    return "rfid-device";
  }
}
