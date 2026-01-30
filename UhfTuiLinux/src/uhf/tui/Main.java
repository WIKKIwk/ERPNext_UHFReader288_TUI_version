package uhf.tui;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import uhf.core.AntennaPowerInfo;
import uhf.core.GpioStatus;
import uhf.core.InventoryParams;
import uhf.core.ReaderInfo;
import uhf.core.Result;
import uhf.core.ReturnLossInfo;
import uhf.core.TagRead;
import uhf.core.WritePowerInfo;
import uhf.erp.ErpConfig;
import uhf.erp.ErpPusher;
import uhf.erp.ErpTagEvent;
import uhf.sdk.ReaderClient;

public final class Main {
  private static final TagStats TAG_STATS = new TagStats();
  private static final TagOutput TAG_OUTPUT = new TagOutput();

  public static void main(String[] args) {
    ConsoleUi ui = new ConsoleUi();
    ReaderClient reader = new ReaderClient();
    ErpPusher erp = new ErpPusher(loadErpConfig());
    CommandRegistry registry = new CommandRegistry();
    int agentPort = parseInt(System.getenv("RFID_AGENT_PORT"), 18000);
    List<String> agentUrls = listAgentUrls(agentPort);
    AgentServer agent = new AgentServer(agentPort,
        () -> new AgentServer.Status(reader.isConnected(), TAG_STATS.total(), TAG_STATS.rate()));
    boolean agentOk = agent.start();

    ui.println("UhfTuiLinux - Linux TUI for UHFReader288/ST-8504/E710");
    ui.println("Menu mode. Use ↑/↓ + Enter.");
    if (agentOk) {
      if (agentUrls.isEmpty()) {
        ui.println("Local agent: http://127.0.0.1:" + agentPort + " (ERP online detector)");
      } else {
        ui.println("Agent URL (use in ERP settings):");
        for (String url : agentUrls) {
          ui.println("  " + url);
        }
      }
    } else {
      ui.println("Local agent failed to start on port " + agentPort);
    }

    setupCommands(registry);
    try {
      menuLoop(ui, reader, erp, registry);
    } finally {
      agent.stop();
      erp.shutdown();
      reader.disconnect();
    }
  }

  private static void setupCommands(CommandRegistry registry) {
    registry.register("connect", "connect <ip> [port] [readerType] [log]", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: connect <ip> [port] [readerType] [log]");
        return;
      }
      if (ctx.reader().isConnected()) {
        ctx.ui().println("Already connected. Use 'disconnect' first.");
        return;
      }
      String ip = args.get(1);
      int port = args.size() >= 3 ? parseInt(args.get(2), 27011) : 27011;
      int readerType = args.size() >= 4 ? parseInt(args.get(3), 4) : 4;
      int log = args.size() >= 5 ? parseInt(args.get(4), 0) : 0;

      Result r = ctx.reader().connect(ip, port, readerType, log, tag -> handleTag(ctx, tag), () -> {});
      if (!r.ok()) {
        ctx.ui().println("Connect failed: " + r.code());
        return;
      }
      ctx.ui().println("Connected: " + ip + "@" + port + " (readerType=" + readerType + ")");
    });

    registry.register("disconnect", "disconnect", (args, ctx) -> {
      Result r = ctx.reader().disconnect();
      if (!r.ok()) {
        ctx.ui().println("Disconnect failed: " + r.code());
      } else {
        ctx.ui().println("Disconnected.");
      }
    }, "disc");

    registry.register("scan", "scan [ports|auto|auto+] [readerType] [log] [prefix]", (args, ctx) -> {
      if (ctx.reader().isConnected()) {
        ctx.ui().println("Already connected. Use 'disconnect' first.");
        return;
      }
      List<Integer> ports = args.size() >= 2
          ? NetworkScanner.parsePorts(args.get(1), true)
          : NetworkScanner.defaultPorts();
      int readerType = args.size() >= 3 ? parseInt(args.get(2), 4) : 4;
      int log = args.size() >= 4 ? parseInt(args.get(3), 0) : 0;
      String prefix = args.size() >= 5 ? args.get(4) : null;

      List<String> prefixes = (prefix == null || prefix.isBlank())
          ? NetworkScanner.detectPrefixes()
          : List.of(prefix.trim());
      if (prefixes.isEmpty()) {
        ctx.ui().println("No LAN prefixes found. Provide prefix like 192.168.1");
        return;
      }

      for (String pfx : prefixes) {
        for (int p : ports) {
          ctx.ui().println("Scanning subnet " + pfx + ".0/24 on port " + p + " ...");
          NetworkScanner.HostPort hp = NetworkScanner.findReader(
              List.of(pfx), List.of(p), readerType, log, Duration.ofMillis(200)
          );
          if (hp != null) {
            Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, tag -> handleTag(ctx, tag), () -> {});
            if (r.ok()) {
              ctx.ui().println("Connected: " + hp.host() + "@" + hp.port());
              return;
            }
          }
        }
      }
      ctx.ui().println("No reader found.");
    });

    registry.register("info", "info", (args, ctx) -> {
      ReaderInfo info = ctx.reader().getInfo();
      if (!info.result().ok()) {
        ctx.ui().println("GetUHFInformation failed: " + info.result().code());
        return;
      }
      ctx.ui().println(
          "Version=" + info.versionMajor() + "." + info.versionMinor() +
              " Power=" + info.power() +
              " Band=" + info.band() +
              " MinFre=" + info.minFreq() +
              " MaxFre=" + info.maxFreq() +
              " Beep=" + info.beep() +
              " Ant=" + info.antenna()
      );
    });

    registry.register("serial", "serial", (args, ctx) -> {
      String sn = ctx.reader().getSerialNumber();
      if (sn == null || sn.isBlank()) {
        ctx.ui().println("Serial number not available.");
      } else {
        ctx.ui().println("Serial: " + sn);
      }
    });

    registry.register("power", "power <0-33>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: power <0-33>");
        return;
      }
      int p = parseInt(args.get(1), -1);
      if (p < 0) {
        ctx.ui().println("Invalid power.");
        return;
      }
      Result r = ctx.reader().setPower(p);
      ctx.ui().println(r.ok() ? "Power set: " + p : "SetRfPower failed: " + r.code());
    });

    registry.register("antpower", "antpower get | set <0-33> [count]", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: antpower get | set <0-33> [count]");
        return;
      }
      if (!ctx.reader().isConnected()) {
        ctx.ui().println("Not connected.");
        return;
      }
      String sub = args.get(1).toLowerCase();
      if (sub.equals("get")) {
        int count = args.size() >= 3 ? parseInt(args.get(2), ctx.reader().getAntennaCount()) : ctx.reader().getAntennaCount();
        AntennaPowerInfo info = ctx.reader().getRfPowerByAnt(count);
        if (!info.result().ok()) {
          ctx.ui().println("GetRfPowerByAnt failed: " + info.result().code());
          return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < info.powers().length; i++) {
          if (i > 0) sb.append(" ");
          sb.append("Ant").append(i + 1).append("=").append(info.powers()[i]);
        }
        ctx.ui().println(sb.toString());
        return;
      }
      if (sub.equals("set")) {
        if (args.size() < 3) {
          ctx.ui().println("Usage: antpower set <0-33> [count]");
          return;
        }
        int p = parseInt(args.get(2), -1);
        if (p < 0 || p > 33) {
          ctx.ui().println("Invalid power.");
          return;
        }
        int count = args.size() >= 4 ? parseInt(args.get(3), ctx.reader().getAntennaCount()) : ctx.reader().getAntennaCount();
        if (count <= 0) count = ctx.reader().getAntennaCount();
        int[] powers = new int[count];
        for (int i = 0; i < count; i++) powers[i] = p;
        Result r = ctx.reader().setRfPowerByAnt(powers);
        ctx.ui().println(r.ok() ? "Per-antenna power set: " + p : "SetRfPowerByAnt failed: " + r.code());
        return;
      }
      ctx.ui().println("Usage: antpower get | set <0-33> [count]");
    });

    registry.register("checkant", "checkant <0|1>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: checkant <0|1>");
        return;
      }
      int v = parseInt(args.get(1), -1);
      if (v != 0 && v != 1) {
        ctx.ui().println("Invalid checkant value.");
        return;
      }
      Result r = ctx.reader().setCheckAnt(v == 1);
      ctx.ui().println(r.ok() ? "Antenna check set: " + v : "SetCheckAnt failed: " + r.code());
    });

    registry.register("returnloss", "returnloss <antenna> <freqMHz>", (args, ctx) -> {
      if (args.size() < 3) {
        ctx.ui().println("Usage: returnloss <antenna> <freqMHz>");
        return;
      }
      int ant = parseInt(args.get(1), -1);
      double freq = parseDouble(args.get(2), -1);
      if (ant < 0 || freq <= 0) {
        ctx.ui().println("Invalid antenna or frequency.");
        return;
      }
      int freqKhz = (int) Math.round(freq * 1000.0);
      ReturnLossInfo info = ctx.reader().measureReturnLoss(ant, freqKhz);
      if (!info.result().ok()) {
        ctx.ui().println("MeasureReturnLoss failed: " + info.result().code());
      } else {
        ctx.ui().println("ReturnLoss ant=" + ant + " freq=" + formatMHz(freq) + " loss=" + info.lossDb() + " dB");
      }
    });

    registry.register("wpower", "wpower get | set <0-33> [mode]", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: wpower get | set <0-33> [mode]");
        ctx.ui().println("mode: 0=normal, 1=high (default=0)");
        return;
      }
      if (!ctx.reader().isConnected()) {
        ctx.ui().println("Not connected.");
        return;
      }
      String sub = args.get(1).toLowerCase();
      if (sub.equals("get")) {
        WritePowerInfo info = ctx.reader().getWritePower();
        if (!info.result().ok()) {
          ctx.ui().println("GetWritePower failed: " + info.result().code());
        } else {
          ctx.ui().println("WritePower=" + info.power() + " dBm mode=" + (info.highMode() ? "high" : "normal"));
        }
        return;
      }
      if (sub.equals("set")) {
        if (args.size() < 3) {
          ctx.ui().println("Usage: wpower set <0-33> [mode]");
          return;
        }
        int p = parseInt(args.get(2), -1);
        if (p < 0 || p > 33) {
          ctx.ui().println("Invalid power.");
          return;
        }
        int mode = args.size() >= 4 ? parseInt(args.get(3), 0) : 0;
        boolean high = mode == 1;
        Result r = ctx.reader().setWritePower(p, high);
        ctx.ui().println(r.ok() ? "Write power set: " + p + " (" + (high ? "high" : "normal") + ")"
            : "SetWritePower failed: " + r.code());
        return;
      }
      ctx.ui().println("Usage: wpower get | set <0-33> [mode]");
    });

    registry.register("region", "region <band> <maxFreq> <minFreq>", (args, ctx) -> {
      if (args.size() < 4) {
        ctx.ui().println("Usage: region <band> <maxFreq> <minFreq>");
        return;
      }
      int band = parseInt(args.get(1), -1);
      int max = parseInt(args.get(2), -1);
      int min = parseInt(args.get(3), -1);
      if (band < 0 || max < 0 || min < 0) {
        ctx.ui().println("Invalid region parameters.");
        return;
      }
      Result r = ctx.reader().setRegion(band, max, min);
      ctx.ui().println(r.ok() ? "Region set." : "SetRegion failed: " + r.code());
    });

    registry.register("beep", "beep <0|1>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: beep <0|1>");
        return;
      }
      int v = parseInt(args.get(1), -1);
      if (v != 0 && v != 1) {
        ctx.ui().println("Invalid beep value.");
        return;
      }
      Result r = ctx.reader().setBeep(v);
      ctx.ui().println(r.ok() ? "Beep set: " + v : "SetBeepNotification failed: " + r.code());
    });

    registry.register("erp", "erp status | enable | disable | set <url> <token>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: erp status | enable | disable | set <url> <token>");
        return;
      }
      String sub = args.get(1).toLowerCase();
      ErpConfig cfg = copyErpConfig(ctx.erp().config());
      if (sub.equals("status")) {
        ctx.ui().println("ERP enabled=" + cfg.enabled + " url=" + cfg.baseUrl + " endpoint=" + cfg.endpoint);
        return;
      }
      if (sub.equals("enable")) {
        cfg.enabled = true;
        saveErpConfig(ctx.erp(), cfg);
        ctx.ui().println("ERP push enabled.");
        return;
      }
      if (sub.equals("disable")) {
        cfg.enabled = false;
        saveErpConfig(ctx.erp(), cfg);
        ctx.ui().println("ERP push disabled.");
        return;
      }
      if (sub.equals("set")) {
        String url;
        String token;
        if (args.size() == 3) {
          url = args.get(2);
          token = ctx.ui().readLine("ERP Token (api_key:api_secret) [" + safe(cfg.auth) + "]: ");
          if (token == null) return;
          if (token.isBlank()) token = cfg.auth;
        } else if (args.size() >= 4 && "url".equalsIgnoreCase(args.get(2))) {
          url = args.get(3);
          token = ctx.ui().readLine("ERP Token (api_key:api_secret) [" + safe(cfg.auth) + "]: ");
          if (token == null) return;
          if (token.isBlank()) token = cfg.auth;
        } else if (args.size() >= 4) {
          url = args.get(2);
          token = args.get(3);
        } else {
          ctx.ui().println("Usage: erp set <url> <token>");
          return;
        }
        cfg.baseUrl = url;
        if (token != null) cfg.auth = token;
        saveErpConfig(ctx.erp(), cfg);
        ctx.ui().println("ERP config updated.");
        return;
      }
      ctx.ui().println("Usage: erp status | enable | disable | set <url> <token>");
    });

    registry.register("gpio", "gpio get | gpio set <mask>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: gpio get | gpio set <mask>");
        return;
      }
      String sub = args.get(1).toLowerCase();
      if (sub.equals("get")) {
        GpioStatus st = ctx.reader().getGpio();
        if (!st.result().ok()) {
          ctx.ui().println("GetGPIOStatus failed: " + st.result().code());
        } else {
          ctx.ui().println("GPIO mask: 0x" + Integer.toHexString(st.mask()));
        }
        return;
      }
      if (sub.equals("set")) {
        if (args.size() < 3) {
          ctx.ui().println("Usage: gpio set <mask>");
          return;
        }
        int mask = parseInt(args.get(2), -1);
        if (mask < 0) {
          ctx.ui().println("Invalid mask.");
          return;
        }
        Result r = ctx.reader().setGpio(mask);
        ctx.ui().println(r.ok() ? "GPIO set: 0x" + Integer.toHexString(mask) : "SetGPIO failed: " + r.code());
        return;
      }
      ctx.ui().println("Usage: gpio get | gpio set <mask>");
    });

    registry.register("relay", "relay <value>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: relay <value>");
        return;
      }
      int v = parseInt(args.get(1), -1);
      if (v < 0) {
        ctx.ui().println("Invalid relay value.");
        return;
      }
      Result r = ctx.reader().setRelay(v);
      ctx.ui().println(r.ok() ? "Relay set: " + v : "SetRelay failed: " + r.code());
    });

    registry.register("antenna", "antenna <arg1> <arg2>", (args, ctx) -> {
      if (args.size() < 3) {
        ctx.ui().println("Usage: antenna <arg1> <arg2>");
        ctx.ui().println("Note: SDK exposes SetAntenna(int,int). Check vendor docs for meanings.");
        return;
      }
      int a1 = parseInt(args.get(1), -1);
      int a2 = parseInt(args.get(2), -1);
      if (a1 < 0 || a2 < 0) {
        ctx.ui().println("Invalid antenna args.");
        return;
      }
      Result r = ctx.reader().setAntenna(a1, a2);
      ctx.ui().println(r.ok() ? "Antenna set." : "SetAntenna failed: " + r.code());
    });

    registry.register("read-epc", "read-epc <epc> <mem> <wordPtr> <num> <password>", (args, ctx) -> {
      if (args.size() < 6) {
        ctx.ui().println("Usage: read-epc <epc> <mem> <wordPtr> <num> <password>");
        return;
      }
      String epc = args.get(1);
      int mem = parseInt(args.get(2), -1);
      int wordPtr = parseInt(args.get(3), -1);
      int num = parseInt(args.get(4), -1);
      String pwd = args.get(5);
      if (mem < 0 || wordPtr < 0 || num <= 0) {
        ctx.ui().println("Invalid parameters.");
        return;
      }
      String data = ctx.reader().readDataByEpc(epc, mem, wordPtr, num, pwd);
      if (data == null) {
        ctx.ui().println("Read failed.");
      } else {
        ctx.ui().println("Data: " + data);
      }
    });

    registry.register("read-tid", "read-tid <tid> <mem> <wordPtr> <num> <password>", (args, ctx) -> {
      if (args.size() < 6) {
        ctx.ui().println("Usage: read-tid <tid> <mem> <wordPtr> <num> <password>");
        return;
      }
      String tid = args.get(1);
      int mem = parseInt(args.get(2), -1);
      int wordPtr = parseInt(args.get(3), -1);
      int num = parseInt(args.get(4), -1);
      String pwd = args.get(5);
      if (mem < 0 || wordPtr < 0 || num <= 0) {
        ctx.ui().println("Invalid parameters.");
        return;
      }
      String data = ctx.reader().readDataByTid(tid, mem, wordPtr, num, pwd);
      if (data == null) {
        ctx.ui().println("Read failed.");
      } else {
        ctx.ui().println("Data: " + data);
      }
    });

    registry.register("write-epc", "write-epc <epc> <mem> <wordPtr> <password> <data>", (args, ctx) -> {
      if (args.size() < 6) {
        ctx.ui().println("Usage: write-epc <epc> <mem> <wordPtr> <password> <data>");
        return;
      }
      if (!ctx.ui().confirm("Write EPC memory?")) return;
      String epc = args.get(1);
      int mem = parseInt(args.get(2), -1);
      int wordPtr = parseInt(args.get(3), -1);
      String pwd = args.get(4);
      String data = args.get(5);
      if (mem < 0 || wordPtr < 0) {
        ctx.ui().println("Invalid parameters.");
        return;
      }
      Result r = ctx.reader().writeDataByEpc(epc, mem, wordPtr, pwd, data);
      ctx.ui().println(r.ok() ? "Write success." : "Write failed: " + r.code());
    });

    registry.register("write-tid", "write-tid <tid> <mem> <wordPtr> <password> <data>", (args, ctx) -> {
      if (args.size() < 6) {
        ctx.ui().println("Usage: write-tid <tid> <mem> <wordPtr> <password> <data>");
        return;
      }
      if (!ctx.ui().confirm("Write TID memory?")) return;
      String tid = args.get(1);
      int mem = parseInt(args.get(2), -1);
      int wordPtr = parseInt(args.get(3), -1);
      String pwd = args.get(4);
      String data = args.get(5);
      if (mem < 0 || wordPtr < 0) {
        ctx.ui().println("Invalid parameters.");
        return;
      }
      Result r = ctx.reader().writeDataByTid(tid, mem, wordPtr, pwd, data);
      ctx.ui().println(r.ok() ? "Write success." : "Write failed: " + r.code());
    });

    registry.register("write-epc-id", "write-epc-id <epc> <password>", (args, ctx) -> {
      if (args.size() < 3) {
        ctx.ui().println("Usage: write-epc-id <epc> <password>");
        return;
      }
      if (!ctx.ui().confirm("Overwrite EPC ID?")) return;
      String epc = args.get(1);
      String pwd = args.get(2);
      Result r = ctx.reader().writeEpc(epc, pwd);
      ctx.ui().println(r.ok() ? "EPC updated." : "WriteEPC failed: " + r.code());
    });

    registry.register("write-epc-by-tid", "write-epc-by-tid <tid> <epc> <password>", (args, ctx) -> {
      if (args.size() < 4) {
        ctx.ui().println("Usage: write-epc-by-tid <tid> <epc> <password>");
        return;
      }
      if (!ctx.ui().confirm("Overwrite EPC by TID?")) return;
      String tid = args.get(1);
      String epc = args.get(2);
      String pwd = args.get(3);
      Result r = ctx.reader().writeEpcByTid(tid, epc, pwd);
      ctx.ui().println(r.ok() ? "EPC updated." : "WriteEPCByTID failed: " + r.code());
    });

    registry.register("lock", "lock <epc> <select> <protect> <password>", (args, ctx) -> {
      if (args.size() < 5) {
        ctx.ui().println("Usage: lock <epc> <select> <protect> <password>");
        return;
      }
      if (!ctx.ui().confirm("Lock tag memory? This may be irreversible.")) return;
      String epc = args.get(1);
      int select = parseInt(args.get(2), -1);
      int protect = parseInt(args.get(3), -1);
      String pwd = args.get(4);
      if (select < 0 || protect < 0) {
        ctx.ui().println("Invalid parameters.");
        return;
      }
      Result r = ctx.reader().lock(epc, select, protect, pwd);
      ctx.ui().println(r.ok() ? "Lock success." : "Lock failed: " + r.code());
    });

    registry.register("kill", "kill <epc> <password>", (args, ctx) -> {
      if (args.size() < 3) {
        ctx.ui().println("Usage: kill <epc> <password>");
        return;
      }
      if (!ctx.ui().confirm("KILL tag? This is permanent and irreversible.")) return;
      String epc = args.get(1);
      String pwd = args.get(2);
      Result r = ctx.reader().kill(epc, pwd);
      ctx.ui().println(r.ok() ? "Kill success." : "Kill failed: " + r.code());
    });

    registry.register("inv", "inv start|stop", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: inv start|stop");
        return;
      }
      String sub = args.get(1).toLowerCase();
      if (sub.equals("start")) {
        Result r = ctx.reader().startInventory();
        ctx.ui().println(r.ok() ? "Inventory started." : "StartRead failed: " + r.code());
        return;
      }
      if (sub.equals("stop")) {
        Result r = ctx.reader().stopInventory();
        ctx.ui().println(r.ok() ? "Inventory stopped." : "StopRead failed: " + r.code());
        return;
      }
      ctx.ui().println("Usage: inv start|stop");
    });

    registry.register("inv-once", "inv-once [ms]", (args, ctx) -> {
      int ms = args.size() >= 2 ? parseInt(args.get(1), 1000) : 1000;
      if (ms < 50) ms = 50;
      Result r = ctx.reader().startInventory();
      if (!r.ok()) {
        ctx.ui().println("StartRead failed: " + r.code());
        return;
      }
      ctx.ui().println("Scanning for " + ms + " ms ...");
      try {
        Thread.sleep(ms);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      Result stop = ctx.reader().stopInventory();
      ctx.ui().println(stop.ok() ? "Scan stopped." : "StopRead failed: " + stop.code());
    }, "once");

    registry.register("inv-param", "inv-param get | set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]",
        (args, ctx) -> {
          if (args.size() < 2) {
            ctx.ui().println("Usage: inv-param get | set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]");
            return;
          }
          if (!ctx.reader().isConnected()) {
            ctx.ui().println("Not connected.");
            return;
          }
          String sub = args.get(1).toLowerCase();
          if (sub.equals("get")) {
            InventoryParams p = ctx.reader().getInventoryParams();
            printInventoryParams(ctx.ui(), p);
            return;
          }
          if (sub.equals("set")) {
            InventoryParams current = ctx.reader().getInventoryParams();
            if (args.size() >= 13) {
              int session = parseInt(args.get(2), current.session());
              int q = parseInt(args.get(3), current.qValue());
              int scanTime = parseInt(args.get(4), current.scanTime());
              int readType = parseInt(args.get(5), current.readType());
              int readMem = parseInt(args.get(6), current.readMem());
              int readPtr = parseInt(args.get(7), current.readPtr());
              int readLen = parseInt(args.get(8), current.readLength());
              int tidPtr = parseInt(args.get(9), current.tidPtr());
              int tidLen = parseInt(args.get(10), current.tidLen());
              int antenna = parseInt(args.get(11), current.antenna());
              String password = args.get(12);
              int address = args.size() >= 14 ? parseInt(args.get(13), current.address()) : current.address();
              InventoryParams p = new InventoryParams(Result.success(), address, tidPtr, tidLen, session, q, scanTime, antenna,
                  readType, readMem, readPtr, readLen, password);
              Result r = ctx.reader().setInventoryParams(p);
              ctx.ui().println(r.ok() ? "Inventory params updated." : "SetInventoryParameter failed: " + r.code());
              return;
            }
            InventoryParams p = promptInventoryParams(ctx.ui(), current);
            Result r = ctx.reader().setInventoryParams(p);
            ctx.ui().println(r.ok() ? "Inventory params updated." : "SetInventoryParameter failed: " + r.code());
            return;
          }
          ctx.ui().println("Usage: inv-param get | set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]");
        }, "invp");
  }

  private static void printHelp(CommandRegistry registry, ConsoleUi ui) {
    ui.println("Commands:");
    for (var def : registry.listUnique()) {
      ui.println("  " + def.name() + " - " + def.help());
    }
    ui.println("  help - show this help");
    ui.println("  menu - back to menu");
    ui.println("  quit - exit");
  }

  private static void menuLoop(ConsoleUi ui, ReaderClient reader, ErpPusher erp, CommandRegistry registry) {
    CommandContext ctx = new CommandContext(reader, ui, erp);
    boolean autoTried = false;
    MenuId forwardTarget = null;
    while (true) {
      if (!autoTried && !reader.isConnected()) {
        autoTried = true;
        attemptAutoConnect(ui, ctx);
      }
      updateStatus(ui, reader, erp);
      String status = reader.isConnected() ? "connected" : "disconnected";
      int sel = ui.selectOption(
          "Main [" + status + "]",
          new String[]{"Connection", "Scan/Auto", "Inventory", "Tag Ops", "Config/IO", "Info", "About", "Command shell", "Quit"},
          0
      );
      if (sel == ConsoleUi.NAV_BACK) {
        continue;
      }
      if (sel == ConsoleUi.NAV_FORWARD && forwardTarget != null) {
        sel = switch (forwardTarget) {
          case CONNECTION -> 0;
          case SCAN -> 1;
          case INVENTORY -> 2;
          case TAGOPS -> 3;
          case CONFIG -> 4;
          case INFO -> 5;
          case ABOUT -> 6;
          case SHELL -> 7;
        };
      } else if (sel == ConsoleUi.NAV_FORWARD) {
        sel = ui.getLastMenuIndex();
      }
      switch (sel) {
        case 0 -> {
          menuConnection(ui, ctx, registry);
          forwardTarget = MenuId.CONNECTION;
        }
        case 1 -> {
          menuScan(ui, ctx);
          forwardTarget = MenuId.SCAN;
        }
        case 2 -> {
          menuInventory(ui, ctx, registry);
          forwardTarget = MenuId.INVENTORY;
        }
        case 3 -> {
          menuTagOps(ui, ctx, registry);
          forwardTarget = MenuId.TAGOPS;
        }
        case 4 -> {
          menuConfig(ui, ctx, registry);
          forwardTarget = MenuId.CONFIG;
        }
        case 5 -> {
          menuInfo(ui, ctx, registry);
          forwardTarget = MenuId.INFO;
        }
        case 6 -> {
          menuAbout(ui);
          forwardTarget = MenuId.ABOUT;
        }
        case 7 -> {
          ui.exitMenuMode();
          ShellExit exit = commandShell(ui, ctx, registry);
          if (exit == ShellExit.QUIT) {
            reader.disconnect();
            return;
          }
          forwardTarget = MenuId.SHELL;
        }
        default -> {
          reader.disconnect();
          return;
        }
      }
    }
  }

  private static void attemptAutoConnect(ConsoleUi ui, CommandContext ctx) {
    int readerType = 4;
    int log = 0;
    List<Integer> ports = List.of(27011, 2022);
    List<String> prefixes = NetworkScanner.detectPrefixes();
    List<String> usbPrefixes = NetworkScanner.detectUsbPrefixes();
    List<String> all = new ArrayList<>();
    all.addAll(prefixes);
    for (String p : usbPrefixes) {
      if (!all.contains(p)) all.add(p);
    }
    if (all.isEmpty()) {
      ui.setStatusMessage("Auto-connect: no LAN/USB prefixes found.");
      return;
    }
    final NetworkScanner.HostPort[] found = {null};
    ui.runWithSpinner("Auto-connecting", () -> {
      for (String pfx : all) {
        for (int p : ports) {
          NetworkScanner.HostPort hp = NetworkScanner.findReader(
              List.of(pfx), List.of(p), readerType, log, Duration.ofMillis(200)
          );
          if (hp != null) {
            found[0] = hp;
            return;
          }
        }
      }
    });
    if (found[0] == null) {
      ui.setStatusMessage("Auto-connect: no reader found.");
      return;
    }
    NetworkScanner.HostPort hp = found[0];
    Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, tag -> handleTag(ctx, tag), () -> {});
    if (r.ok()) {
      ui.setStatusMessage("Auto-connect: " + hp.host() + "@" + hp.port());
    } else {
      ui.setStatusMessage("Auto-connect failed: " + r.code());
    }
  }

  private static void menuAbout(ConsoleUi ui) {
    List<String> lines = readReadmeLines();
    if (lines.isEmpty()) {
      ui.showLines("About", List.of(
          "README.md not found.",
          "Expected in current folder or parent."
      ));
      return;
    }
    ui.viewLinesPaged("About (README)", lines, 12);
  }

  private static void menuConnection(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Connection", new String[]{"Connect", "Disconnect", "Back"}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      if (sel == 1) {
        registry.execute(List.of("disconnect"), ctx);
        continue;
      }
      String ip = askString(ui, "IP address");
      if (ip == null || ip.isBlank()) {
        ui.println("IP is required.");
        continue;
      }
      int port = askInt(ui, "Port", 27011);
      int readerType = selectReaderType(ui, 4);
      if (readerType == ConsoleUi.NAV_BACK) return;
      int log = selectLog(ui, 0);
      if (log == ConsoleUi.NAV_BACK) return;
      registry.execute(List.of("connect", ip, String.valueOf(port), String.valueOf(readerType), String.valueOf(log)), ctx);
    }
  }

  private static void menuScan(ConsoleUi ui, CommandContext ctx) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Scan", new String[]{"LAN auto-scan", "USB auto-scan", "Back"}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      int readerType = selectReaderType(ui, 4);
      if (readerType == ConsoleUi.NAV_BACK) return;
      int log = selectLog(ui, 0);
      if (log == ConsoleUi.NAV_BACK) return;
      List<Integer> ports = choosePorts(ui);
      if (ports == null) return;
      List<String> prefixes;
      if (sel == 1) {
        prefixes = NetworkScanner.detectUsbPrefixes();
        if (prefixes.isEmpty()) {
          List<String> serials = NetworkScanner.detectSerialDevices();
          if (!serials.isEmpty()) {
            ui.println("USB serial device(s): " + String.join(", ", serials));
            ui.println("Linux SDK USB/serial qo‘llamaydi. LAN (RNDIS/ECM) bo‘lsa ishlaydi.");
          }
          prefixes = NetworkScanner.detectPrefixes();
        }
      } else {
        List<String> detected = NetworkScanner.detectPrefixes();
        if (detected.isEmpty()) {
          ui.setStatusMessage("No LAN prefixes found.");
          continue;
        }
        PrefixChoice choice = chooseSubnetPrefix(ui, detected);
        if (choice.cancelled()) return;
        prefixes = choice.prefix() == null ? detected : List.of(choice.prefix());
      }
      if (prefixes.isEmpty()) {
        ui.println("No LAN prefixes found.");
        continue;
      }
      boolean connected = false;
      for (String pfx : prefixes) {
        for (int p : ports) {
          ui.println("Scanning subnet " + pfx + ".0/24 on port " + p + " ...");
          NetworkScanner.HostPort hp = NetworkScanner.findReader(
              List.of(pfx), List.of(p), readerType, log, Duration.ofMillis(200)
          );
          if (hp != null) {
            Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, tag -> handleTag(ctx, tag), () -> {});
            if (r.ok()) {
              ui.println("Connected: " + hp.host() + "@" + hp.port());
              connected = true;
              break;
            }
          }
        }
        if (connected) break;
      }
      if (!connected) ui.println("No reader found.");
    }
  }

  private static void menuInventory(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Inventory", new String[]{"Start", "Stop", "Once (timed)", "Params (view)", "Params (set)", "Back"}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 5) return;
      if (sel == 0) registry.execute(List.of("inv", "start"), ctx);
      if (sel == 1) registry.execute(List.of("inv", "stop"), ctx);
      if (sel == 2) {
        int ms = askInt(ui, "Duration ms", 1000);
        registry.execute(List.of("inv-once", String.valueOf(ms)), ctx);
        pause(ui);
      }
      if (sel == 3) {
        if (!ctx.reader().isConnected()) {
          ui.setStatusMessage("Not connected.");
          continue;
        }
        InventoryParams p = ctx.reader().getInventoryParams();
        printInventoryParams(ui, p);
      }
      if (sel == 4) {
        if (!ctx.reader().isConnected()) {
          ui.setStatusMessage("Not connected.");
          continue;
        }
        InventoryParams current = ctx.reader().getInventoryParams();
        InventoryParams p = promptInventoryParams(ui, current);
        Result r = ctx.reader().setInventoryParams(p);
        ui.println(r.ok() ? "Inventory params updated." : "SetInventoryParameter failed: " + r.code());
        pause(ui);
      }
    }
  }

  private static void menuTagOps(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {
        "Read EPC", "Read TID", "Write EPC", "Write TID",
        "Write EPC ID", "Write EPC by TID", "Lock", "Kill", "Back"
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Tag Ops", options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 8) return;
      switch (sel) {
        case 0 -> registry.execute(List.of("read-epc",
            askString(ui, "EPC"),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, "WordPtr", 0)),
            String.valueOf(askInt(ui, "Num", 1)),
            askString(ui, "Password")), ctx);
        case 1 -> registry.execute(List.of("read-tid",
            askString(ui, "TID"),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, "WordPtr", 0)),
            String.valueOf(askInt(ui, "Num", 1)),
            askString(ui, "Password")), ctx);
        case 2 -> registry.execute(List.of("write-epc",
            askString(ui, "EPC"),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, "WordPtr", 0)),
            askString(ui, "Password"),
            askString(ui, "Data")), ctx);
        case 3 -> registry.execute(List.of("write-tid",
            askString(ui, "TID"),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, "WordPtr", 0)),
            askString(ui, "Password"),
            askString(ui, "Data")), ctx);
        case 4 -> registry.execute(List.of("write-epc-id",
            askString(ui, "EPC"),
            askString(ui, "Password")), ctx);
        case 5 -> registry.execute(List.of("write-epc-by-tid",
            askString(ui, "TID"),
            askString(ui, "EPC"),
            askString(ui, "Password")), ctx);
        case 6 -> registry.execute(List.of("lock",
            askString(ui, "EPC"),
            String.valueOf(askInt(ui, "Select", 0)),
            String.valueOf(askInt(ui, "Protect", 0)),
            askString(ui, "Password")), ctx);
        case 7 -> registry.execute(List.of("kill",
            askString(ui, "EPC"),
            askString(ui, "Password")), ctx);
      }
    }
  }

  private static void menuConfig(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {
        "Tag Output",
        "Agent URLs",
        "RF Power",
        "Write Power",
        "Per-Antenna Power",
        "Region",
        "Beep",
        "Antenna Check",
        "Return Loss",
        "GPIO Get",
        "GPIO Set",
        "Relay",
        "Antenna",
        "ERP Push",
        "Back"
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Config/IO", options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 14) return;
      switch (sel) {
        case 0 -> {
          int mode = ui.selectOption("Tag Output", new String[]{"Counts only", "Show tag lines"}, TAG_OUTPUT.show ? 1 : 0);
          if (mode == ConsoleUi.NAV_BACK) break;
          if (mode == ConsoleUi.NAV_FORWARD) mode = ui.getLastMenuIndex();
          TAG_OUTPUT.show = mode == 1;
          ui.setStatusMessage(TAG_OUTPUT.show ? "Tag output: ON" : "Tag output: OFF");
        }
        case 1 -> {
          List<String> urls = listAgentUrls(parseInt(System.getenv("RFID_AGENT_PORT"), 18000));
          if (urls.isEmpty()) {
            ui.showLines("Agent URLs", List.of("No LAN IP found.", "Use: http://127.0.0.1:18000"));
          } else {
            ui.showLines("Agent URLs", urls);
          }
        }
        case 2 -> {
          int p = selectPowerValue(ui, "RF Power", 30);
          if (p == ConsoleUi.NAV_BACK) return;
          registry.execute(List.of("power", String.valueOf(p)), ctx);
        }
        case 3 -> menuWritePower(ui, ctx, registry);
        case 4 -> menuAntennaPower(ui, ctx);
        case 5 -> {
          RegionSelection region = selectRegion(ui);
          if (region == null) break;
          registry.execute(List.of("region",
              String.valueOf(region.band()),
              String.valueOf(region.maxFreq()),
              String.valueOf(region.minFreq())), ctx);
        }
        case 6 -> {
          int b = ui.selectOption("Beep", new String[]{"On (1)", "Off (0)"}, 0);
          if (b == ConsoleUi.NAV_BACK) return;
          if (b == ConsoleUi.NAV_FORWARD) b = ui.getLastMenuIndex();
          int val = b == 0 ? 1 : 0;
          registry.execute(List.of("beep", String.valueOf(val)), ctx);
        }
        case 7 -> menuAntennaCheck(ui, ctx);
        case 8 -> menuReturnLoss(ui, ctx);
        case 9 -> registry.execute(List.of("gpio", "get"), ctx);
        case 10 -> registry.execute(List.of("gpio", "set", String.valueOf(askInt(ui, "GPIO mask", 0))), ctx);
        case 11 -> registry.execute(List.of("relay", String.valueOf(askInt(ui, "Relay value", 0))), ctx);
        case 12 -> registry.execute(List.of("antenna",
            String.valueOf(askInt(ui, "Arg1", 0)),
            String.valueOf(askInt(ui, "Arg2", 0))), ctx);
        case 13 -> menuErp(ui, ctx);
      }
    }
  }

  private static void menuErp(ConsoleUi ui, CommandContext ctx) {
    String[] options = {"Status", "Enable", "Disable", "Set URL", "Test (fake)", "Back"};
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("ERP Push", options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 5) return;
      ErpConfig cfg = copyErpConfig(ctx.erp().config());
      switch (sel) {
        case 0 -> ui.showLines("ERP Status", List.of(
            "enabled=" + cfg.enabled,
            "url=" + safe(cfg.baseUrl),
            "endpoint=" + safe(cfg.endpoint),
            "device=" + safe(cfg.device),
            "auth=" + (cfg.auth == null || cfg.auth.isBlank() ? "(empty)" : "***"),
            "secret=" + (cfg.secret == null || cfg.secret.isBlank() ? "(empty)" : "***")
        ));
        case 1 -> {
          cfg.enabled = true;
          saveErpConfig(ctx.erp(), cfg);
          ui.setStatusMessage("ERP push enabled.");
        }
        case 2 -> {
          cfg.enabled = false;
          saveErpConfig(ctx.erp(), cfg);
          ui.setStatusMessage("ERP push disabled.");
        }
        case 3 -> {
          String url;
          while (true) {
            url = askStringOrBack(ui, "ERP URL", safe(cfg.baseUrl));
            if (url == null) break;
            if (!url.isBlank()) break;
            ui.setStatusMessage("ERP URL required.");
          }
          if (url == null) break;
          String token = askStringOrBack(ui, "ERP Token (api_key:api_secret)", safe(cfg.auth));
          if (token == null) break;
          cfg.baseUrl = url;
          cfg.auth = token;
          saveErpConfig(ctx.erp(), cfg);
          final boolean[] ok = {false};
          ui.runWithSpinner("Checking ERP", () -> ok[0] = ctx.erp().testOnce());
          if (ok[0]) {
            ui.setStatusMessage("ERP check: ok");
          } else {
            String msg = ctx.erp().lastErrMsg();
            ui.setStatusMessage(msg == null || msg.isBlank() ? "ERP check: failed" : "ERP check: failed (" + msg + ")");
          }
        }
        case 4 -> {
          int count = askInt(ui, "Fake tags count", 5);
          final boolean[] ok = {false};
          ui.runWithSpinner("Sending test tags", () -> ok[0] = ctx.erp().pushTestTags(count));
          if (ok[0]) {
            ui.setStatusMessage("ERP test tags: sent");
          } else {
            String msg = ctx.erp().lastErrMsg();
            ui.setStatusMessage(msg == null || msg.isBlank() ? "ERP test tags: failed" : "ERP test tags: failed (" + msg + ")");
          }
        }
      }
    }
  }

  private static void menuAntennaPower(ConsoleUi ui, CommandContext ctx) {
    String[] options = {"Set all", "Set per-antenna", "Get", "Back"};
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Per-Antenna Power", options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 3) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage("Not connected.");
        continue;
      }
      int count = ctx.reader().getAntennaCount();
      if (sel == 2) {
        AntennaPowerInfo info = ctx.reader().getRfPowerByAnt(count);
        if (!info.result().ok()) {
          ui.setStatusMessage("GetRfPowerByAnt failed: " + info.result().code());
          continue;
        }
        String[] lines = new String[info.powers().length];
        for (int i = 0; i < info.powers().length; i++) {
          lines[i] = "Ant " + (i + 1) + ": " + info.powers()[i] + " dBm";
        }
        ui.showLines("Per-Antenna Power", List.of(lines));
        continue;
      }
      if (sel == 0) {
        int p = selectPowerValue(ui, "Set all power", 30);
        if (p == ConsoleUi.NAV_BACK) continue;
        int[] powers = new int[count];
        for (int i = 0; i < count; i++) powers[i] = p;
        Result r = ctx.reader().setRfPowerByAnt(powers);
        ui.setStatusMessage(r.ok() ? "Per-antenna power set." : "SetRfPowerByAnt failed: " + r.code());
        continue;
      }
      int[] powers = new int[count];
      AntennaPowerInfo current = ctx.reader().getRfPowerByAnt(count);
      if (current.result().ok() && current.powers().length == count) {
        System.arraycopy(current.powers(), 0, powers, 0, count);
      } else {
        for (int i = 0; i < count; i++) powers[i] = 30;
      }
      boolean cancelled = false;
      for (int i = 0; i < count; i++) {
        int p = selectPowerValue(ui, "Ant " + (i + 1) + " Power", powers[i]);
        if (p == ConsoleUi.NAV_BACK) {
          cancelled = true;
          break;
        }
        powers[i] = p;
      }
      if (cancelled) continue;
      Result r = ctx.reader().setRfPowerByAnt(powers);
      ui.setStatusMessage(r.ok() ? "Per-antenna power set." : "SetRfPowerByAnt failed: " + r.code());
    }
  }

  private static void menuAntennaCheck(ConsoleUi ui, CommandContext ctx) {
    String[] options = {"Enable (1)", "Disable (0)", "Back"};
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Antenna Check", options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage("Not connected.");
        continue;
      }
      boolean enabled = sel == 0;
      Result r = ctx.reader().setCheckAnt(enabled);
      ui.setStatusMessage(r.ok() ? "Antenna check " + (enabled ? "enabled" : "disabled") + "."
          : "SetCheckAnt failed: " + r.code());
    }
  }

  private static void menuReturnLoss(ConsoleUi ui, CommandContext ctx) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Return Loss", new String[]{"Measure", "Back"}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 1) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage("Not connected.");
        continue;
      }
      int ant = selectAntennaIndex(ui, ctx.reader().getAntennaCount(), 0);
      if (ant == ConsoleUi.NAV_BACK) continue;
      Double freqMHz = selectReturnLossFreq(ui);
      if (freqMHz == null) continue;
      int freqKhz = (int) Math.round(freqMHz * 1000.0);
      ReturnLossInfo info = ctx.reader().measureReturnLoss(ant, freqKhz);
      if (!info.result().ok()) {
        ui.setStatusMessage("MeasureReturnLoss failed: " + info.result().code());
      } else {
        ui.showLines("Return Loss", List.of(
            "Antenna: " + (ant + 1) + " (" + ant + ")",
            "Freq: " + formatMHz(freqMHz),
            "Loss: " + info.lossDb() + " dB"
        ));
      }
    }
  }

  private static void menuWritePower(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {"Set (normal)", "Set (high)", "Get", "Back"};
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Write Power", options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 3) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage("Not connected.");
        continue;
      }
      if (sel == 2) {
        registry.execute(List.of("wpower", "get"), ctx);
        continue;
      }
      int def = 30;
      WritePowerInfo info = ctx.reader().getWritePower();
      if (info.result().ok()) def = info.power();
      int p = selectPowerValue(ui, "Write Power", def);
      if (p == ConsoleUi.NAV_BACK) continue;
      registry.execute(List.of("wpower", "set", String.valueOf(p), sel == 1 ? "1" : "0"), ctx);
    }
  }

  private static void menuInfo(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption("Info", new String[]{"Reader info", "Serial", "Back"}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      if (sel == 0) registry.execute(List.of("info"), ctx);
      if (sel == 1) registry.execute(List.of("serial"), ctx);
    }
  }

  private static ShellExit commandShell(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    ui.println("Command shell. Type 'menu' to return.");
    while (true) {
      ui.prompt();
      String line = ui.readLine();
      if (line == null) return ShellExit.BACK;
      List<String> tokens = Tokenizer.tokenize(line);
      if (tokens.isEmpty()) continue;
      String cmd = tokens.get(0).toLowerCase();
      if (cmd.equals("menu") || cmd.equals("back")) return ShellExit.BACK;
      if (cmd.equals("quit") || cmd.equals("exit") || cmd.equals("q")) return ShellExit.QUIT;
      if (cmd.equals("help") || cmd.equals("?")) {
        printHelp(registry, ui);
        continue;
      }
      if (!registry.execute(tokens, ctx)) {
        ui.println("Unknown command. Type 'help'.");
      }
    }
  }

  private static int askInt(ConsoleUi ui, String label, int def) {
    String line = ui.readLineInMenu(label + " [" + def + "]: ");
    if (line == null || line.isBlank()) return def;
    return parseInt(line, def);
  }

  private static String askString(ConsoleUi ui, String label, String def) {
    String line = ui.readLineInMenu(label + " [" + def + "]: ");
    if (line == null || line.isBlank()) return def;
    return line;
  }

  private static String askStringOrBack(ConsoleUi ui, String label, String def) {
    String line = ui.readLineInMenuOrBack(label + " [" + def + "]: ");
    if (line == null) return null;
    if (line.isBlank()) return def;
    return line;
  }

  private static String askString(ConsoleUi ui, String label) {
    return ui.readLineInMenu(label + ": ");
  }

  private static List<Integer> choosePorts(ConsoleUi ui) {
    String[] options = {
        "auto (default)",
        "auto+ (wide)",
        "27011 only",
        "27011 + 2022",
        "2022 only"
    };
    int mode = ui.selectOption("Ports", options, 0);
    if (mode == ConsoleUi.NAV_BACK) return null;
    if (mode == ConsoleUi.NAV_FORWARD) mode = ui.getLastMenuIndex();
    return switch (mode) {
      case 1 -> NetworkScanner.widePorts();
      case 2 -> List.of(27011);
      case 3 -> List.of(27011, 2022);
      case 4 -> List.of(2022);
      default -> NetworkScanner.defaultPorts();
    };
  }

  private static PrefixChoice chooseSubnetPrefix(ConsoleUi ui, List<String> prefixes) {
    if (prefixes == null || prefixes.isEmpty()) {
      ui.setStatusMessage("No LAN prefixes found.");
      return new PrefixChoice(null, true);
    }
    String[] options = new String[prefixes.size() + 1];
    options[0] = "Auto (all detected)";
    for (int i = 0; i < prefixes.size(); i++) {
      options[i + 1] = prefixes.get(i);
    }
    int sel = ui.selectOption("Subnet", options, 0);
    if (sel == ConsoleUi.NAV_BACK) return new PrefixChoice(null, true);
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel <= 0) return new PrefixChoice(null, false);
    return new PrefixChoice(prefixes.get(sel - 1), false);
  }

  private static int selectMem(ConsoleUi ui) {
    int mode = ui.selectOption("Mem", new String[]{"Password(0)", "EPC(1)", "TID(2)", "User(3)"}, 1);
    return switch (mode) {
      case 0 -> 0;
      case 2 -> 2;
      case 3 -> 3;
      default -> 1;
    };
  }

  private static RegionSelection selectRegion(ConsoleUi ui) {
    RegionOption[] options = regionOptions();
    String[] labels = new String[options.length + 1];
    for (int i = 0; i < options.length; i++) labels[i] = options[i].label();
    labels[labels.length - 1] = "Custom";
    int sel = ui.selectOption("Region", labels, 0);
    if (sel == ConsoleUi.NAV_BACK) return null;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel >= 0 && sel < options.length) {
      RegionOption opt = options[sel];
      double[] freqs = buildFreqList(opt.startMhz(), opt.stepMhz(), opt.count());
      String[] items = new String[freqs.length];
      for (int i = 0; i < freqs.length; i++) {
        items[i] = i + ": " + formatMHz(freqs[i]);
      }
      int minIdx = ui.selectOptionPaged("MinFreq", items, 0, 12);
      if (minIdx == ConsoleUi.NAV_BACK) return null;
      if (minIdx == ConsoleUi.NAV_FORWARD) minIdx = ui.getLastMenuIndex();
      if (minIdx < 0) minIdx = 0;
      String[] maxItems = new String[items.length + 1];
      maxItems[0] = "Same as Min (" + minIdx + ")";
      System.arraycopy(items, 0, maxItems, 1, items.length);
      int maxSel = ui.selectOptionPaged("MaxFreq", maxItems, minIdx + 1, 12);
      if (maxSel == ConsoleUi.NAV_BACK) return null;
      if (maxSel == ConsoleUi.NAV_FORWARD) maxSel = ui.getLastMenuIndex();
      int maxIdx = maxSel <= 0 ? minIdx : maxSel - 1;
      return new RegionSelection(opt.band(), maxIdx, minIdx);
    }
    int band = askInt(ui, "Band", 0);
    int max = askInt(ui, "MaxFreq", 0);
    int min = askInt(ui, "MinFreq", 0);
    return new RegionSelection(band, max, min);
  }

  private static RegionOption[] regionOptions() {
    return new RegionOption[] {
        new RegionOption("Chinese band1", 8, 840.125, 0.25, 20),
        new RegionOption("US band", 2, 902.75, 0.5, 50),
        new RegionOption("Korean band", 3, 917.1, 0.2, 32),
        new RegionOption("EU band", 4, 865.1, 0.2, 15),
        new RegionOption("Chinese band2", 1, 920.125, 0.25, 20),
        new RegionOption("US band3", 12, 902.0, 0.5, 53),
        new RegionOption("ALL band", 0, 840.0, 2.0, 61)
    };
  }

  private static double[] buildFreqList(double startMHz, double stepMHz, int count) {
    double[] list = new double[count];
    for (int i = 0; i < count; i++) {
      list[i] = startMHz + (stepMHz * i);
    }
    return list;
  }

  private static String formatMHz(double mhz) {
    String s = String.format(java.util.Locale.US, "%.3f", mhz);
    while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
      s = s.substring(0, s.length() - 1);
    }
    return s + " MHz";
  }

  private static int parseInt(String s, int def) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static double parseDouble(String s, double def) {
    try {
      return Double.parseDouble(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static InventoryParams promptInventoryParams(ConsoleUi ui, InventoryParams current) {
    int addrDefault = current.address() == 255 ? 0 : 1;
    int addrSel = ui.selectOption("Address", new String[]{"Broadcast (255)", "Custom"}, addrDefault);
    int address = addrSel == 0 ? 255 : askInt(ui, "Address (0-255)", current.address());
    int session = askInt(ui, "Session", current.session());
    int q = askInt(ui, "QValue", current.qValue());
    int scanTime = askInt(ui, "ScanTime", current.scanTime());
    int antenna = askInt(ui, "Antenna", current.antenna());
    int readType = askInt(ui, "ReadType", current.readType());
    int readMem = askInt(ui, "ReadMem", current.readMem());
    int readPtr = askInt(ui, "ReadPtr", current.readPtr());
    int readLen = askInt(ui, "ReadLength", current.readLength());
    int tidPtr = askInt(ui, "TID Ptr", current.tidPtr());
    int tidLen = askInt(ui, "TID Len", current.tidLen());
    String pwd = askString(ui, "Password", current.password() == null ? "" : current.password());
    return new InventoryParams(Result.success(), address, tidPtr, tidLen, session, q, scanTime, antenna,
        readType, readMem, readPtr, readLen, pwd);
  }

  private static void printInventoryParams(ConsoleUi ui, InventoryParams p) {
    if (!p.result().ok()) {
      if (p.result().code() == 0x36) {
        ui.showLines("Not connected", List.of("Please connect first."));
      } else {
        ui.showLines("GetInventoryParameter failed", List.of("code=" + p.result().code()));
      }
      return;
    }
    ui.showLines("Inventory Params", List.of(
        "address=" + p.address() + " session=" + p.session() + " q=" + p.qValue() + " scanTime=" + p.scanTime()
            + " antenna=" + p.antenna(),
        "readType=" + p.readType() + " readMem=" + p.readMem() + " readPtr=" + p.readPtr()
            + " readLen=" + p.readLength(),
        "tidPtr=" + p.tidPtr() + " tidLen=" + p.tidLen() + " password=" + p.password()
    ));
  }

  private static void pause(ConsoleUi ui) {
    ui.readLineInMenu("Press Enter to continue...");
  }

  private record PrefixChoice(String prefix, boolean cancelled) {
  }

  private static int selectReaderType(ConsoleUi ui, int def) {
    int idx = def == 16 ? 1 : 0;
    int sel = ui.selectOption("ReaderType", new String[]{"4", "16"}, idx);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    return sel == 1 ? 16 : 4;
  }

  private static int selectLog(ConsoleUi ui, int def) {
    int idx = def == 1 ? 1 : 0;
    int sel = ui.selectOption("Log", new String[]{"0 (off)", "1 (on)"}, idx);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    return sel == 1 ? 1 : 0;
  }

  private static int selectAntennaIndex(ConsoleUi ui, int count, int def) {
    int n = count > 0 ? count : 4;
    String[] items = new String[n];
    for (int i = 0; i < n; i++) {
      items[i] = "Ant " + (i + 1) + " (" + i + ")";
    }
    int sel = ui.selectOptionPaged("Antenna", items, Math.max(0, Math.min(def, n - 1)), 10);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    return sel;
  }

  private static Double selectReturnLossFreq(ConsoleUi ui) {
    RegionOption[] options = regionOptions();
    String[] labels = new String[options.length + 1];
    for (int i = 0; i < options.length; i++) labels[i] = options[i].label();
    labels[labels.length - 1] = "Custom (MHz)";
    int sel = ui.selectOption("Return Loss Freq", labels, 0);
    if (sel == ConsoleUi.NAV_BACK) return null;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel >= 0 && sel < options.length) {
      RegionOption opt = options[sel];
      double[] freqs = buildFreqList(opt.startMhz(), opt.stepMhz(), opt.count());
      String[] items = new String[freqs.length];
      for (int i = 0; i < freqs.length; i++) {
        items[i] = i + ": " + formatMHz(freqs[i]);
      }
      int idx = ui.selectOptionPaged("Frequency", items, freqs.length / 2, 12);
      if (idx == ConsoleUi.NAV_BACK) return null;
      if (idx == ConsoleUi.NAV_FORWARD) idx = ui.getLastMenuIndex();
      if (idx < 0) idx = 0;
      return freqs[idx];
    }
    String line = ui.readLineInMenu("Freq MHz (e.g. 915.25): ");
    double freq = parseDouble(line, -1);
    if (freq <= 0) {
      ui.setStatusMessage("Invalid frequency.");
      return null;
    }
    return freq;
  }

  private static int selectPowerValue(ConsoleUi ui, String label, int def) {
    String[] items = new String[34];
    for (int i = 0; i <= 33; i++) {
      items[i] = i + " dBm";
    }
    int safeDef = Math.max(0, Math.min(def, 33));
    int sel = ui.selectOptionPaged(label, items, safeDef, 12);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel < 0) sel = safeDef;
    return sel;
  }

  private static void handleTag(CommandContext ctx, TagRead tag) {
    TAG_STATS.onTag(ctx.ui());
    if (TAG_OUTPUT.show) {
      ctx.ui().printTag(tag);
    }
    ctx.erp().enqueue(new ErpTagEvent(tag.epcId(), tag.memId(), tag.rssi(), tag.antId(), tag.ipAddr(), System.currentTimeMillis()));
  }

  private static void updateStatus(ConsoleUi ui, ReaderClient reader, ErpPusher erp) {
    String readerState = reader.isConnected() ? "UHF: connected" : "UHF: disconnected";
    String erpState = erpStatus(erp);
    ui.setHeaderRight(erpState.isEmpty() ? readerState : readerState + " | " + erpState);
    ui.setStatusBase(TAG_STATS.statusLine());
  }

  private static List<String> listAgentUrls(int port) {
    List<String> out = new ArrayList<>();
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
        for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
          if (addr instanceof Inet4Address) {
            String ip = addr.getHostAddress();
            if (ip != null && !ip.isBlank()) {
              out.add("http://" + ip + ":" + port);
            }
          }
        }
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private static String erpStatus(ErpPusher erp) {
    if (erp == null) return "";
    if (erp.config() == null || erp.config().baseUrl == null || erp.config().baseUrl.isBlank()) return "ERP: inactive";
    long now = System.currentTimeMillis();
    long ok = erp.lastOkAt();
    long err = erp.lastErrAt();
    if (ok > 0 && err <= ok && now - ok <= 60000) return "ERP: active";
    if (!erp.isEnabled()) return "ERP: configured";
    if (err > ok) return "ERP: error";
    if (ok == 0) return "ERP: waiting";
    long age = now - ok;
    if (age <= 60000) return "ERP: connected";
    return "ERP: stale";
  }

  private enum ShellExit {
    BACK,
    QUIT
  }

  private static final class TagOutput {
    private volatile boolean show = false;
  }

  private static final class TagStats {
    private long total = 0;
    private long sinceLast = 0;
    private long lastRateAt = System.currentTimeMillis();
    private long lastUiAt = 0;
    private int rate = 0;

    synchronized void onTag(ConsoleUi ui) {
      total++;
      sinceLast++;
      long now = System.currentTimeMillis();
      if (now - lastRateAt >= 1000) {
        rate = (int) sinceLast;
        sinceLast = 0;
        lastRateAt = now;
      }
      if (ui != null && now - lastUiAt >= 1000) {
        ui.setStatusBase(statusLine());
        lastUiAt = now;
      }
    }

    synchronized String statusLine() {
      return "Tags: " + total + " | Rate: " + rate + "/s";
    }

    synchronized long total() {
      return total;
    }

    synchronized int rate() {
      return rate;
    }
  }

  private enum MenuId {
    CONNECTION,
    SCAN,
    INVENTORY,
    TAGOPS,
    CONFIG,
    INFO,
    ABOUT,
    SHELL
  }

  private static List<String> readReadmeLines() {
    List<Path> candidates = List.of(
        Path.of("README.md"),
        Path.of("./README.md"),
        Path.of("..", "README.md")
    );
    for (Path p : candidates) {
      try {
        if (!Files.exists(p)) continue;
        return new ArrayList<>(Files.readAllLines(p));
      } catch (Exception ignored) {
      }
    }
    return List.of();
  }

  private static ErpConfig loadErpConfig() {
    Path file = erpConfigPath();
    return ErpConfig.load(file);
  }

  private static void saveErpConfig(ErpPusher erp, ErpConfig cfg) {
    if (erp == null || cfg == null) return;
    cfg.save(erpConfigPath());
    erp.applyConfig(cfg);
  }

  private static ErpConfig copyErpConfig(ErpConfig src) {
    ErpConfig c = new ErpConfig();
    if (src == null) return c;
    c.enabled = src.enabled;
    c.baseUrl = safe(src.baseUrl);
    c.auth = safe(src.auth);
    c.secret = safe(src.secret);
    c.device = safe(src.device);
    c.endpoint = safe(src.endpoint);
    c.batchMs = src.batchMs;
    c.maxBatch = src.maxBatch;
    c.maxQueue = src.maxQueue;
    c.heartbeatMs = src.heartbeatMs;
    return c;
  }

  private static Path erpConfigPath() {
    return Path.of("UhfTuiLinux", "erp.properties");
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }

  private record RegionOption(String label, int band, double startMhz, double stepMhz, int count) {
  }

  private record RegionSelection(int band, int maxFreq, int minFreq) {
  }
}
