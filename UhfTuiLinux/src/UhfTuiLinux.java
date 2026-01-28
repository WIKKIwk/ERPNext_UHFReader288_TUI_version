import com.rfid.CReader;
import com.rfid.ReadTag;
import com.rfid.TagCallback;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class UhfTuiLinux {
  private static final Object PRINT_LOCK = new Object();

  private static CReader reader;
  private static boolean inventoryRunning = false;

  public static void main(String[] args) throws Exception {
    printBanner();

    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    startupWizard(br);

    while (true) {
      prompt();
      String line = br.readLine();
      if (line == null) break;
      List<String> tokens = tokenize(line);
      if (tokens.isEmpty()) continue;

      String cmd = tokens.get(0).toLowerCase();
      switch (cmd) {
        case "help":
        case "?":
          printHelp();
          break;
        case "quit":
        case "exit":
        case "q":
          shutdown();
          return;
        case "connect":
          cmdConnect(tokens);
          break;
        case "disconnect":
          cmdDisconnect();
          break;
        case "info":
          cmdInfo();
          break;
        case "power":
          cmdPower(tokens);
          break;
        case "inv":
          cmdInv(tokens);
          break;
        case "scan":
          cmdScan(tokens);
          break;
        case "clear":
        case "cls":
          System.out.print("\033[H\033[2J");
          System.out.flush();
          break;
        default:
          println("Unknown command. Type 'help'.");
      }
    }

    shutdown();
  }

  private static void cmdConnect(List<String> tokens) {
    if (tokens.size() < 2) {
      println("Usage: connect <ip> [port] [readerType] [log]");
      return;
    }
    if (reader != null) {
      println("Already connected. Use 'disconnect' first.");
      return;
    }

    String ip = tokens.get(1);
    int port = tokens.size() >= 3 ? parseInt(tokens.get(2), 27011) : 27011;
    int readerType = tokens.size() >= 4 ? parseInt(tokens.get(3), 4) : 4;
    int log = tokens.size() >= 5 ? parseInt(tokens.get(4), 0) : 0;

    try {
      reader = createReader(ip, port, readerType, log);

      int rc = reader.Connect();
      if (rc != 0) {
        reader = null;
        println("Connect failed: " + rc);
        return;
      }

      println("Connected: " + ip + "@" + port + " (readerType=" + readerType + ")");
    } catch (Throwable t) {
      reader = null;
      println("Connect error: " + t.getMessage());
    }
  }

  private static void cmdDisconnect() {
    if (reader == null) {
      println("Not connected.");
      return;
    }
    try {
      if (inventoryRunning) {
        try {
          reader.StopRead();
        } catch (Throwable ignored) {}
        inventoryRunning = false;
      }
      reader.DisConnect();
      reader = null;
      println("Disconnected.");
    } catch (Throwable t) {
      reader = null;
      inventoryRunning = false;
      println("Disconnect error: " + t.getMessage());
    }
  }

  private static void cmdInfo() {
    if (reader == null) {
      println("Not connected.");
      return;
    }
    try {
      byte[] version = new byte[2];
      byte[] power = new byte[1];
      byte[] band = new byte[1];
      byte[] maxFre = new byte[1];
      byte[] minFre = new byte[1];
      byte[] beep = new byte[1];
      int[] ant = new int[1];
      int rc = reader.GetUHFInformation(version, power, band, maxFre, minFre, beep, ant);
      if (rc != 0) {
        println("GetUHFInformation failed: " + rc);
        return;
      }
      println("Version=" + (version[0] & 0xFF) + "." + (version[1] & 0xFF)
          + " Power=" + (power[0] & 0xFF)
          + " Band=" + (band[0] & 0xFF)
          + " MinFre=" + (minFre[0] & 0xFF)
          + " MaxFre=" + (maxFre[0] & 0xFF)
          + " Beep=" + (beep[0] & 0xFF)
          + " Ant=" + ant[0]);
    } catch (Throwable t) {
      println("Info error: " + t.getMessage());
    }
  }

  private static void cmdPower(List<String> tokens) {
    if (reader == null) {
      println("Not connected.");
      return;
    }
    if (tokens.size() != 2) {
      println("Usage: power <0-33>");
      return;
    }
    int p = parseInt(tokens.get(1), -1);
    if (p < 0) {
      println("Invalid power.");
      return;
    }
    try {
      int rc = reader.SetRfPower(p);
      if (rc != 0) {
        println("SetRfPower failed: " + rc);
      } else {
        println("Power set: " + p);
      }
    } catch (Throwable t) {
      println("Power error: " + t.getMessage());
    }
  }

  private static void cmdInv(List<String> tokens) {
    if (reader == null) {
      println("Not connected.");
      return;
    }
    if (tokens.size() < 2) {
      println("Usage: inv start|stop");
      return;
    }
    String sub = tokens.get(1).toLowerCase();
    try {
      if ("start".equals(sub)) {
        if (inventoryRunning) {
          println("Inventory already running.");
          return;
        }
        int rc = reader.StartRead();
        if (rc != 0) {
          println("StartRead failed: " + rc);
          return;
        }
        inventoryRunning = true;
        println("Inventory started.");
        return;
      }
      if ("stop".equals(sub)) {
        if (!inventoryRunning) {
          println("Inventory not running.");
          return;
        }
        reader.StopRead();
        inventoryRunning = false;
        println("Inventory stopped.");
        return;
      }
      println("Usage: inv start|stop");
    } catch (Throwable t) {
      inventoryRunning = false;
      println("Inventory error: " + t.getMessage());
    }
  }

  private static void cmdScan(List<String> tokens) {
    if (reader != null) {
      println("Already connected. Use 'disconnect' first.");
      return;
    }
    List<Integer> ports = tokens.size() >= 2 ? parsePorts(tokens.get(1), true) : defaultPorts();
    int readerType = tokens.size() >= 3 ? parseInt(tokens.get(2), 4) : 4;
    int log = tokens.size() >= 4 ? parseInt(tokens.get(3), 0) : 0;
    String prefix = tokens.size() >= 5 ? tokens.get(4) : null;

    CReader found = autoConnectLan(ports, readerType, log, prefix);
    if (found == null) {
      println("No reader found.");
      return;
    }
    reader = found;
  }

  private static void shutdown() {
    if (reader == null) return;
    try {
      if (inventoryRunning) {
        try { reader.StopRead(); } catch (Throwable ignored) {}
        inventoryRunning = false;
      }
      try { reader.DisConnect(); } catch (Throwable ignored) {}
    } finally {
      reader = null;
    }
  }

  private static void printBanner() {
    println("UhfTuiLinux - Linux TUI for ST-8504/E710 (CReader.jar)");
    println("Type 'help' for commands.");
  }

  private static void printHelp() {
    println("Commands:");
    println("  connect <ip> [port] [readerType] [log]  (defaults: 27011, 4, 0)");
    println("  scan [ports|auto|auto+] [readerType] [log] [prefix]");
    println("       ports example: 27011,2022 or 2000-2100");
    println("  disconnect");
    println("  info");
    println("  power <0-33>");
    println("  inv start|stop");
    println("  clear");
    println("  quit");
  }

  private static void prompt() {
    System.out.print("uhf> ");
    System.out.flush();
  }

  private static void println(String s) {
    synchronized (PRINT_LOCK) {
      System.out.println(s);
    }
  }

  private static int parseInt(String s, int def) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static List<String> tokenize(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        inQuotes = !inQuotes;
        continue;
      }
      if (!inQuotes && Character.isWhitespace(ch)) {
        if (cur.length() > 0) {
          out.add(cur.toString());
          cur.setLength(0);
        }
        continue;
      }
      cur.append(ch);
    }
    if (cur.length() > 0) out.add(cur.toString());
    return out;
  }

  private static void startupWizard(BufferedReader br) {
    try {
      AutoMode mode = chooseAutoMode(br);
      if (mode == AutoMode.SKIP) return;
      if (mode == AutoMode.LAN) {
        int readerType = chooseReaderType(br);
        List<Integer> ports = choosePorts(br);
        String prefix = chooseSubnetPrefix(br);

        CReader found = autoConnectLanWithPrefixes(ports, readerType, 0, detectPrefixesOr(prefix));
        if (found != null) {
          reader = found;
        } else {
          println("Auto connect failed.");
        }
        return;
      }

      if (mode == AutoMode.USB) {
        int readerType = chooseReaderType(br);
        List<Integer> ports = choosePorts(br);

        List<String> usbPrefixes = detectUsbPrefixes();
        if (usbPrefixes.isEmpty()) {
          List<String> serials = detectSerialDevices();
          if (!serials.isEmpty()) {
            println("USB serial device(s) found: " + String.join(", ", serials));
            println("Linux SDK USB/serial qo‘llamaydi. LAN (RNDIS/ECM) bo‘lsa ishlaydi.");
          }
          println("USB network interface topilmadi. LAN prefixlarni tekshirishga o'tyapman...");
          usbPrefixes = detectPrefixes();
        }

        CReader found = autoConnectLanWithPrefixes(ports, readerType, 0, usbPrefixes);
        if (found != null) {
          reader = found;
        } else {
          println("Auto connect (USB/LAN) failed.");
        }
        return;
      }

    } catch (Throwable t) {
      println("Auto-connect skipped: " + t.getMessage());
    }
  }

  private static AutoMode chooseAutoMode(BufferedReader br) {
    int idx = chooseSwipeIndex(br, "Auto connect", new String[]{"LAN", "USB", "Skip"}, 0);
    return switch (idx) {
      case 1 -> AutoMode.USB;
      case 2 -> AutoMode.SKIP;
      default -> AutoMode.LAN;
    };
  }

  private static int chooseReaderType(BufferedReader br) {
    int idx = chooseSwipeIndex(br, "ReaderType", new String[]{"4", "16"}, 0);
    return idx == 1 ? 16 : 4;
  }

  private static List<Integer> choosePorts(BufferedReader br) {
    int idx = chooseSwipeIndex(br, "Ports", new String[]{"auto", "auto+", "custom"}, 0);
    if (idx == 0) return defaultPorts();
    if (idx == 1) return widePorts();
    String line = readLine(br, "Enter ports (comma/range), e.g. 27011,2022 or 2000-2100: ");
    return parsePorts(line, true);
  }

  private static String chooseSubnetPrefix(BufferedReader br) {
    int idx = chooseSwipeIndex(br, "Subnet", new String[]{"auto", "custom"}, 0);
    if (idx == 0) return null;
    String line = readLine(br, "Subnet prefix (e.g. 192.168.1): ");
    if (line == null) return null;
    line = line.trim();
    return line.isEmpty() ? null : line;
  }

  private static List<String> detectPrefixesOr(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return detectPrefixes();
    }
    List<String> list = new ArrayList<>();
    list.add(prefix);
    return list;
  }

  private static String readLine(BufferedReader br, String prompt) {
    try {
      System.out.print(prompt);
      System.out.flush();
      return br.readLine();
    } catch (Throwable t) {
      return null;
    }
  }

  private static int chooseSwipeIndex(BufferedReader br, String label, String[] options, int defaultIndex) {
    if (System.console() == null) {
      return chooseIndexLine(br, label, options, defaultIndex);
    }
    if (!setTerminalRaw(true)) {
      return chooseIndexLine(br, label, options, defaultIndex);
    }
    int idx = Math.max(0, Math.min(defaultIndex, options.length - 1));
    try {
      renderSwipeLine(label, options, idx);
      while (true) {
        int ch = System.in.read();
        if (ch == -1) return idx;
        if (ch == '\r' || ch == '\n') {
          System.out.println();
          return idx;
        }
        if (ch == 27) { // ESC
          int ch1 = System.in.read();
          if (ch1 == -1) return idx;
          if (ch1 == '[') {
            int ch2 = System.in.read();
            if (ch2 == 'A') {
              idx = (idx - 1 + options.length) % options.length;
              renderSwipeLine(label, options, idx);
              continue;
            }
            if (ch2 == 'B') {
              idx = (idx + 1) % options.length;
              renderSwipeLine(label, options, idx);
              continue;
            }
          } else {
            return idx;
          }
        }
        if (ch == 'j' || ch == 'J') {
          idx = (idx + 1) % options.length;
          renderSwipeLine(label, options, idx);
          continue;
        }
        if (ch == 'k' || ch == 'K') {
          idx = (idx - 1 + options.length) % options.length;
          renderSwipeLine(label, options, idx);
          continue;
        }
        if (ch >= '1' && ch <= '9') {
          int n = (ch - '1');
          if (n >= 0 && n < options.length) {
            idx = n;
            renderSwipeLine(label, options, idx);
          }
        }
      }
    } catch (Throwable t) {
      return chooseIndexLine(br, label, options, defaultIndex);
    } finally {
      setTerminalRaw(false);
    }
  }

  private static void renderSwipeLine(String label, String[] options, int idx) {
    String text = label + " [" + options[idx] + "] (↑/↓ + Enter)";
    System.out.print("\r\033[2K" + text);
    System.out.flush();
  }

  private static int chooseIndexLine(BufferedReader br, String label, String[] options, int defaultIndex) {
    try {
      StringBuilder sb = new StringBuilder();
      sb.append(label).append(" (");
      for (int i = 0; i < options.length; i++) {
        if (i > 0) sb.append(", ");
        sb.append(i + 1).append("=").append(options[i]);
      }
      sb.append(") [").append(options[defaultIndex]).append("]: ");
      System.out.print(sb.toString());
      System.out.flush();
      String line = br.readLine();
      if (line == null) return defaultIndex;
      line = line.trim();
      if (line.isEmpty()) return defaultIndex;
      try {
        int n = Integer.parseInt(line);
        if (n >= 1 && n <= options.length) return n - 1;
      } catch (Exception ignored) {
      }
      for (int i = 0; i < options.length; i++) {
        if (options[i].equalsIgnoreCase(line)) return i;
      }
      return defaultIndex;
    } catch (Throwable ignored) {
      return defaultIndex;
    }
  }

  private static boolean setTerminalRaw(boolean enable) {
    try {
      String cmd = enable
          ? "stty -echo -icanon min 1 time 0 < /dev/tty"
          : "stty sane < /dev/tty";
      Process p = new ProcessBuilder("sh", "-c", cmd).inheritIO().start();
      return p.waitFor() == 0;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static CReader createReader(String ip, int port, int readerType, int log) {
    CReader r = new CReader(ip, port, readerType, log);
    r.SetCallBack(new TagCallback() {
      @Override
      public void tagCallback(ReadTag tag) {
        synchronized (PRINT_LOCK) {
          String t = LocalTime.now().toString();
          System.out.println(t + " EPC=" + tag.epcId + " RSSI=" + tag.rssi + " ANT=" + tag.antId);
          prompt();
        }
      }

      @Override
      public void StopReadCallback() {
        synchronized (PRINT_LOCK) {
          inventoryRunning = false;
          System.out.println("Inventory stopped (callback).");
          prompt();
        }
      }
    });
    return r;
  }

  private static CReader autoConnectLan(int port, int readerType, int log, String prefix) {
    return autoConnectLan(defaultPorts(), readerType, log, prefix);
  }

  private static CReader autoConnectLan(List<Integer> ports, int readerType, int log, String prefix) {
    List<String> prefixes = new ArrayList<>();
    if (prefix != null && !prefix.isEmpty()) {
      prefixes.add(prefix);
    } else {
      prefixes.addAll(detectPrefixes());
    }

    return autoConnectLanWithPrefixes(ports, readerType, log, prefixes);
  }

  private static CReader autoConnectLanWithPrefixes(List<Integer> ports, int readerType, int log, List<String> prefixes) {
    if (prefixes == null || prefixes.isEmpty()) {
      println("No LAN prefixes found. Provide prefix like 192.168.1");
      return null;
    }

    if (ports == null || ports.isEmpty()) {
      ports = defaultPorts();
    }

    Duration timeout = ports.size() > 300 ? Duration.ofMillis(120) : Duration.ofMillis(200);
    int threads = ports.size() > 300 ? 128 : 64;

    for (String p : prefixes) {
      for (int port : ports) {
        println("Scanning subnet " + p + ".0/24 on port " + port + " ...");
        CReader found = scanPrefix(p, port, readerType, log, timeout, threads);
        if (found != null) {
          println("Connected: " + p + ".*@" + port + " (readerType=" + readerType + ")");
          return found;
        }
      }
    }
    return null;
  }

  private static CReader scanPrefix(String prefix, int port, int readerType, int log, Duration timeout, int threads) {
    AtomicBoolean done = new AtomicBoolean(false);
    AtomicReference<CReader> foundRef = new AtomicReference<>(null);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    for (int i = 1; i <= 254; i++) {
      if (done.get()) break;
      final String ip = prefix + "." + i;
      pool.submit(() -> {
        if (done.get()) return;
        if (!isPortOpen(ip, port, timeout)) return;
        try {
          CReader r = createReader(ip, port, readerType, log);
          int rc = r.Connect();
          if (rc == 0 && done.compareAndSet(false, true)) {
            foundRef.set(r);
          } else {
            try { r.DisConnect(); } catch (Throwable ignored) {}
          }
        } catch (Throwable ignored) {
          // ignore
        }
      });
    }

    pool.shutdown();
    try {
      pool.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }

    CReader found = foundRef.get();
    if (found != null) return found;
    return null;
  }

  private static boolean isPortOpen(String ip, int port, Duration timeout) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(ip, port), (int) timeout.toMillis());
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static List<String> detectPrefixes() {
    List<String> prefixes = new ArrayList<>();
    try {
      var nics = NetworkInterface.getNetworkInterfaces();
      while (nics.hasMoreElements()) {
        NetworkInterface nic = nics.nextElement();
        if (!nic.isUp() || nic.isLoopback()) continue;
        var addrs = nic.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (!(addr instanceof Inet4Address)) continue;
          String ip = addr.getHostAddress();
          String[] parts = ip.split("\\.");
          if (parts.length == 4) {
            String pfx = parts[0] + "." + parts[1] + "." + parts[2];
            if (!prefixes.contains(pfx)) prefixes.add(pfx);
          }
        }
      }
    } catch (Throwable ignored) {
    }
    return prefixes;
  }

  private static List<String> detectUsbPrefixes() {
    List<String> prefixes = new ArrayList<>();
    try {
      var nics = NetworkInterface.getNetworkInterfaces();
      while (nics.hasMoreElements()) {
        NetworkInterface nic = nics.nextElement();
        if (!nic.isUp() || nic.isLoopback()) continue;
        String name = nic.getName().toLowerCase();
        String display = nic.getDisplayName() == null ? "" : nic.getDisplayName().toLowerCase();
        boolean looksUsb =
            name.startsWith("usb") ||
            name.contains("rndis") ||
            name.contains("cdc") ||
            name.contains("ecm") ||
            display.contains("usb") ||
            display.contains("rndis") ||
            display.contains("cdc") ||
            display.contains("ecm");
        if (!looksUsb) continue;

        var addrs = nic.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (!(addr instanceof Inet4Address)) continue;
          String ip = addr.getHostAddress();
          String[] parts = ip.split("\\.");
          if (parts.length == 4) {
            String pfx = parts[0] + "." + parts[1] + "." + parts[2];
            if (!prefixes.contains(pfx)) prefixes.add(pfx);
          }
        }
      }
    } catch (Throwable ignored) {
    }
    return prefixes;
  }

  private static List<String> detectSerialDevices() {
    List<String> list = new ArrayList<>();
    try {
      java.io.File dev = new java.io.File("/dev");
      java.io.File[] files = dev.listFiles();
      if (files == null) return list;
      for (java.io.File f : files) {
        String n = f.getName();
        if (n.startsWith("ttyUSB") || n.startsWith("ttyACM")) {
          list.add("/dev/" + n);
        }
      }
    } catch (Throwable ignored) {
    }
    return list;
  }

  private static List<Integer> defaultPorts() {
    List<Integer> ports = new ArrayList<>();
    // Common / observed ports
    ports.add(27011);
    ports.add(2022);
    ports.add(2000);
    ports.add(4001);
    ports.add(4002);
    ports.add(5000);
    ports.add(5001);
    ports.add(6000);
    ports.add(7000);
    ports.add(8000);
    ports.add(9000);
    ports.add(10000);
    ports.add(12000);
    ports.add(15000);
    ports.add(16000);
    ports.add(20000);
    ports.add(21000);
    ports.add(22000);
    ports.add(23000);
    ports.add(24000);
    ports.add(25000);
    ports.add(26000);
    ports.add(28000);
    ports.add(29000);
    ports.add(30000);
    ports.add(40000);
    ports.add(50000);
    ports.add(60000);
    return dedupSort(ports, 1000);
  }

  private static List<Integer> parsePorts(String s, boolean verbose) {
    if (s == null) return defaultPorts();
    String t = s.trim().toLowerCase();
    if (t.isEmpty() || t.equals("auto")) return defaultPorts();
    if (t.equals("auto+") || t.equals("wide")) return widePorts();

    List<Integer> ports = new ArrayList<>();
    String[] parts = t.split("[,;\\s]+");
    for (String p : parts) {
      if (p == null || p.isEmpty()) continue;
      String part = p.trim();
      int dash = part.indexOf('-');
      if (dash > 0) {
        try {
          int start = Integer.parseInt(part.substring(0, dash).trim());
          int end = Integer.parseInt(part.substring(dash + 1).trim());
          if (start > end) {
            int tmp = start; start = end; end = tmp;
          }
          for (int v = start; v <= end; v++) {
            if (v > 0 && v <= 65535) ports.add(v);
          }
        } catch (Exception ignored) {
        }
      } else {
        try {
          int v = Integer.parseInt(part);
          if (v > 0 && v <= 65535) ports.add(v);
        } catch (Exception ignored) {
        }
      }
    }
    if (ports.isEmpty()) return defaultPorts();
    return dedupSort(ports, 1000, verbose);
  }

  private static List<Integer> widePorts() {
    List<Integer> ports = new ArrayList<>(defaultPorts());
    addRange(ports, 2000, 2100);
    addRange(ports, 27000, 27150);
    addRange(ports, 5000, 5100);
    addRange(ports, 10000, 10100);
    addRange(ports, 15000, 15100);
    addRange(ports, 20000, 20100);
    addRange(ports, 25000, 25100);
    addRange(ports, 30000, 30100);
    return dedupSort(ports, 1200);
  }

  private static void addRange(List<Integer> list, int start, int end) {
    if (start > end) {
      int tmp = start; start = end; end = tmp;
    }
    for (int v = start; v <= end; v++) {
      if (v > 0 && v <= 65535) list.add(v);
    }
  }

  private static List<Integer> dedupSort(List<Integer> ports, int max) {
    return dedupSort(ports, max, false);
  }

  private static List<Integer> dedupSort(List<Integer> ports, int max, boolean verbose) {
    ports.sort(Integer::compareTo);
    List<Integer> out = new ArrayList<>();
    Integer prev = null;
    for (int v : ports) {
      if (prev == null || v != prev) {
        out.add(v);
        prev = v;
      }
    }
    if (out.size() > max) {
      if (verbose) {
        println("Port list too large (" + out.size() + "), using first " + max + " ports.");
      }
      return new ArrayList<>(out.subList(0, max));
    }
    return out;
  }


  private enum AutoMode {
    LAN,
    USB,
    SKIP
  }
}
