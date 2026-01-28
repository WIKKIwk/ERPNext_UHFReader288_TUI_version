package uhf.tui;

import java.time.Duration;
import java.util.List;
import uhf.core.GpioStatus;
import uhf.core.ReaderInfo;
import uhf.core.Result;
import uhf.sdk.ReaderClient;

public final class Main {
  public static void main(String[] args) {
    ConsoleUi ui = new ConsoleUi();
    ReaderClient reader = new ReaderClient();
    CommandRegistry registry = new CommandRegistry();
    CommandContext ctx = new CommandContext(reader, ui);

    ui.println("UhfTuiLinux - Linux TUI for UHFReader288/ST-8504/E710");
    ui.println("Type 'help' for commands.");

    setupCommands(registry);
    startupWizard(ui, reader);

    while (true) {
      ui.prompt();
      String line = ui.readLine();
      if (line == null) break;
      List<String> tokens = Tokenizer.tokenize(line);
      if (tokens.isEmpty()) continue;
      String cmd = tokens.get(0).toLowerCase();
      if (cmd.equals("quit") || cmd.equals("exit") || cmd.equals("q")) {
        reader.disconnect();
        break;
      }
      if (cmd.equals("help") || cmd.equals("?")) {
        printHelp(registry, ui);
        continue;
      }
      if (!registry.execute(tokens, ctx)) {
        ui.println("Unknown command. Type 'help'.");
      }
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

      Result r = ctx.reader().connect(ip, port, readerType, log, ctx.ui()::printTag, () -> {});
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
            Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, ctx.ui()::printTag, () -> {});
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
  }

  private static void printHelp(CommandRegistry registry, ConsoleUi ui) {
    ui.println("Commands:");
    for (var def : registry.listUnique()) {
      ui.println("  " + def.name() + " - " + def.help());
    }
    ui.println("  help - show this help");
    ui.println("  quit - exit");
  }

  private static void startupWizard(ConsoleUi ui, ReaderClient reader) {
    int mode = ui.selectOption("Auto connect", new String[]{"LAN", "USB", "Skip"}, 0);
    if (mode == 2) return;
    int readerType = ui.selectOption("ReaderType", new String[]{"4", "16"}, 0) == 1 ? 16 : 4;
    int portMode = ui.selectOption("Ports", new String[]{"auto", "auto+", "custom"}, 0);
    List<Integer> ports = (portMode == 1) ? NetworkScanner.widePorts()
        : (portMode == 2 ? NetworkScanner.parsePorts(ui.readLine("Enter ports: "), true) : NetworkScanner.defaultPorts());
    int subnetMode = ui.selectOption("Subnet", new String[]{"auto", "custom"}, 0);
    String prefix = subnetMode == 1 ? ui.readLine("Subnet prefix (e.g. 192.168.1): ") : null;

    List<String> prefixes;
    if (mode == 1) { // USB
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
      prefixes = (prefix == null || prefix.isBlank()) ? NetworkScanner.detectPrefixes() : List.of(prefix.trim());
    }

    if (prefixes.isEmpty()) {
      ui.println("No LAN prefixes found.");
      return;
    }

    for (String pfx : prefixes) {
      for (int p : ports) {
        ui.println("Scanning subnet " + pfx + ".0/24 on port " + p + " ...");
        NetworkScanner.HostPort hp = NetworkScanner.findReader(
            List.of(pfx), List.of(p), readerType, 0, Duration.ofMillis(200)
        );
        if (hp != null) {
          Result r = reader.connect(hp.host(), hp.port(), readerType, 0, ui::printTag, () -> {});
          if (r.ok()) {
            ui.println("Connected: " + hp.host() + "@" + hp.port());
            return;
          }
        }
      }
    }
    ui.println("Auto connect failed.");
  }

  private static int parseInt(String s, int def) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }
}
