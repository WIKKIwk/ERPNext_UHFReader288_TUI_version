package uhf.tui;

import java.time.Duration;
import java.util.List;
import uhf.core.GpioStatus;
import uhf.core.InventoryParams;
import uhf.core.ReaderInfo;
import uhf.core.Result;
import uhf.sdk.ReaderClient;

public final class Main {
  public static void main(String[] args) {
    ConsoleUi ui = new ConsoleUi();
    ReaderClient reader = new ReaderClient();
    CommandRegistry registry = new CommandRegistry();

    ui.println("UhfTuiLinux - Linux TUI for UHFReader288/ST-8504/E710");
    ui.println("Menu mode. Use ↑/↓ + Enter.");

    setupCommands(registry);
    menuLoop(ui, reader, registry);
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

  private static void menuLoop(ConsoleUi ui, ReaderClient reader, CommandRegistry registry) {
    CommandContext ctx = new CommandContext(reader, ui);
    while (true) {
      updateStatus(ui, reader);
      String status = reader.isConnected() ? "connected" : "disconnected";
      int sel = ui.selectOption(
          "Main [" + status + "]",
          new String[]{"Connection", "Scan/Auto", "Inventory", "Tag Ops", "Config/IO", "Info", "Command shell", "Quit"},
          0
      );
      switch (sel) {
        case 0 -> menuConnection(ui, ctx, registry);
        case 1 -> menuScan(ui, ctx);
        case 2 -> menuInventory(ui, ctx, registry);
        case 3 -> menuTagOps(ui, ctx, registry);
        case 4 -> menuConfig(ui, ctx, registry);
        case 5 -> menuInfo(ui, ctx, registry);
        case 6 -> {
          ui.exitMenuMode();
          ShellExit exit = commandShell(ui, ctx, registry);
          if (exit == ShellExit.QUIT) {
            reader.disconnect();
            return;
          }
        }
        default -> {
          reader.disconnect();
          return;
        }
      }
    }
  }

  private static void menuConnection(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader());
      int sel = ui.selectOption("Connection", new String[]{"Connect", "Disconnect", "Back"}, 0);
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
      int readerType = askInt(ui, "ReaderType (4/16)", 4);
      int log = askInt(ui, "Log (0/1)", 0);
      registry.execute(List.of("connect", ip, String.valueOf(port), String.valueOf(readerType), String.valueOf(log)), ctx);
    }
  }

  private static void menuScan(ConsoleUi ui, CommandContext ctx) {
    while (true) {
      updateStatus(ui, ctx.reader());
      int sel = ui.selectOption("Scan", new String[]{"LAN auto-scan", "USB auto-scan", "Back"}, 0);
      if (sel == 2) return;
      int readerType = askInt(ui, "ReaderType (4/16)", 4);
      int log = askInt(ui, "Log (0/1)", 0);
      List<Integer> ports = choosePorts(ui);
      String prefix = chooseSubnetPrefix(ui);
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
        prefixes = (prefix == null || prefix.isBlank()) ? NetworkScanner.detectPrefixes() : List.of(prefix.trim());
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
            Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, ctx.ui()::printTag, () -> {});
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
      updateStatus(ui, ctx.reader());
      int sel = ui.selectOption("Inventory", new String[]{"Start", "Stop", "Once (timed)", "Params (view)", "Params (set)", "Back"}, 0);
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
      updateStatus(ui, ctx.reader());
      int sel = ui.selectOption("Tag Ops", options, 0);
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
    String[] options = {"Power", "Region", "Beep", "GPIO Get", "GPIO Set", "Relay", "Antenna", "Back"};
    while (true) {
      updateStatus(ui, ctx.reader());
      int sel = ui.selectOption("Config/IO", options, 0);
      if (sel == 7) return;
      switch (sel) {
        case 0 -> registry.execute(List.of("power", String.valueOf(askInt(ui, "Power (0-33)", 30))), ctx);
        case 1 -> registry.execute(List.of("region",
            String.valueOf(selectBand(ui)),
            String.valueOf(askInt(ui, "MaxFreq", 0)),
            String.valueOf(askInt(ui, "MinFreq", 0))), ctx);
        case 2 -> registry.execute(List.of("beep", String.valueOf(askInt(ui, "Beep (0/1)", 1))), ctx);
        case 3 -> registry.execute(List.of("gpio", "get"), ctx);
        case 4 -> registry.execute(List.of("gpio", "set", String.valueOf(askInt(ui, "GPIO mask", 0))), ctx);
        case 5 -> registry.execute(List.of("relay", String.valueOf(askInt(ui, "Relay value", 0))), ctx);
        case 6 -> registry.execute(List.of("antenna",
            String.valueOf(askInt(ui, "Arg1", 0)),
            String.valueOf(askInt(ui, "Arg2", 0))), ctx);
      }
    }
  }

  private static void menuInfo(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader());
      int sel = ui.selectOption("Info", new String[]{"Reader info", "Serial", "Back"}, 0);
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

  private static String askString(ConsoleUi ui, String label) {
    return ui.readLineInMenu(label + ": ");
  }

  private static List<Integer> choosePorts(ConsoleUi ui) {
    int mode = ui.selectOption("Ports", new String[]{"auto", "auto+", "custom"}, 0);
    if (mode == 0) return NetworkScanner.defaultPorts();
    if (mode == 1) return NetworkScanner.widePorts();
    return NetworkScanner.parsePorts(ui.readLineInMenu("Enter ports (comma/range): "), true);
  }

  private static String chooseSubnetPrefix(ConsoleUi ui) {
    int mode = ui.selectOption("Subnet", new String[]{"auto", "custom"}, 0);
    if (mode == 0) return null;
    return ui.readLineInMenu("Subnet prefix (e.g. 192.168.1): ");
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

  private static int selectBand(ConsoleUi ui) {
    String[] bands = {
        "Band 0", "Band 1", "Band 2", "Band 3", "Band 4", "Band 5", "Band 6", "Custom"
    };
    int sel = ui.selectOption("Region Band", bands, 0);
    if (sel >= 0 && sel <= 6) return sel;
    return askInt(ui, "Band", 0);
  }

  private static int parseInt(String s, int def) {
    try {
      return Integer.parseInt(s.trim());
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

  private static void updateStatus(ConsoleUi ui, ReaderClient reader) {
    ui.setStatusBase("Status: " + (reader.isConnected() ? "connected" : "disconnected"));
  }

  private enum ShellExit {
    BACK,
    QUIT
  }
}
