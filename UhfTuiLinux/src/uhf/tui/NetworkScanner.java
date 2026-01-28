package uhf.tui;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import uhf.sdk.ReaderClient;

public final class NetworkScanner {
  public record HostPort(String host, int port) {}

  private NetworkScanner() {}

  public static List<String> detectPrefixes() {
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

  public static List<String> detectUsbPrefixes() {
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

  public static List<String> detectSerialDevices() {
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

  public static List<Integer> defaultPorts() {
    List<Integer> ports = new ArrayList<>();
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

  public static List<Integer> widePorts() {
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

  public static List<Integer> parsePorts(String s, boolean verbose) {
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
          if (start > end) { int tmp = start; start = end; end = tmp; }
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

  public static HostPort findReader(
      List<String> prefixes,
      List<Integer> ports,
      int readerType,
      int log,
      Duration timeout
  ) {
    if (prefixes == null || prefixes.isEmpty()) return null;
    if (ports == null || ports.isEmpty()) ports = defaultPorts();
    int threads = ports.size() > 300 ? 128 : 64;
    for (String pfx : prefixes) {
      for (int port : ports) {
        HostPort hp = scanPrefix(pfx, port, readerType, log, timeout, threads);
        if (hp != null) return hp;
      }
    }
    return null;
  }

  private static HostPort scanPrefix(
      String prefix,
      int port,
      int readerType,
      int log,
      Duration timeout,
      int threads
  ) {
    AtomicBoolean done = new AtomicBoolean(false);
    AtomicReference<HostPort> foundRef = new AtomicReference<>(null);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    for (int i = 1; i <= 254; i++) {
      if (done.get()) break;
      final String ip = prefix + "." + i;
      pool.submit(() -> {
        if (done.get()) return;
        if (!isPortOpen(ip, port, timeout)) return;
        if (ReaderClient.probe(ip, port, readerType, log)) {
          if (done.compareAndSet(false, true)) {
            foundRef.set(new HostPort(ip, port));
          }
        }
      });
    }

    pool.shutdown();
    try {
      pool.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }
    return foundRef.get();
  }

  private static boolean isPortOpen(String ip, int port, Duration timeout) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(ip, port), (int) timeout.toMillis());
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void addRange(List<Integer> list, int start, int end) {
    if (start > end) { int tmp = start; start = end; end = tmp; }
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
        System.out.println("Port list too large (" + out.size() + "), using first " + max + " ports.");
      }
      return new ArrayList<>(out.subList(0, max));
    }
    return out;
  }
}

