package uhf.tui;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import uhf.core.AntennaPowerInfo;
import uhf.core.GpioStatus;
import uhf.core.InventoryParams;
import uhf.core.ReaderInfo;
import uhf.core.Result;
import uhf.core.ReturnLossInfo;
import uhf.core.TagRead;
import uhf.core.WritePowerInfo;
import uhf.erp.ErpAgentRegistrar;
import uhf.erp.ErpConfig;
import uhf.erp.ErpPusher;
import uhf.erp.ErpTagEvent;
import uhf.sdk.ReaderClient;

public final class Main {
  private static final TagStats TAG_STATS = new TagStats();
  private static final TagOutput TAG_OUTPUT = new TagOutput();
  private static ErpAgentRegistrar ERP_AGENT;
  private static Lang LANG = Lang.EN;

  public static void main(String[] args) {
    ConsoleUi ui = new ConsoleUi();
    ReaderClient reader = new ReaderClient();
    ErpPusher erp = new ErpPusher(loadErpConfig());
    CommandRegistry registry = new CommandRegistry();
    LANG = loadLang();
    ConsoleUi.setTranslator(Main::L);
    int agentPort = parseInt(System.getenv("RFID_AGENT_PORT"), 0);
    boolean agentEnabled = agentPort > 0;
    List<String> agentUrls = agentEnabled ? listAgentUrls(agentPort) : List.of();
    AgentServer agent = agentEnabled
        ? new AgentServer(agentPort, () -> new AgentServer.Status(reader.isConnected(), TAG_STATS.total(), TAG_STATS.rate()))
        : null;
    boolean agentOk = agentEnabled && agent.start();
    ERP_AGENT = new ErpAgentRegistrar(erp.config(), () -> agentEnabled ? listAgentUrls(agentPort) : List.of());

    ui.println(L(
        "UhfTuiLinux - Linux TUI for UHFReader288/ST-8504/E710",
        "UhfTuiLinux - UHFReader288/ST-8504/E710 uchun Linux TUI",
        "UhfTuiLinux - Linux TUI для UHFReader288/ST-8504/E710"
    ));
    ui.println(L("Menu mode. Use ↑/↓ + Enter.", "Menyu rejimi. ↑/↓ + Enter ni ishlating.", "Режим меню. Используйте ↑/↓ + Enter."));
    if (agentEnabled) {
      if (agentOk) {
        if (agentUrls.isEmpty()) {
          ui.println(L("Agent HTTP enabled: ", "Agent HTTP yoqildi: ", "Agent HTTP включен: ") + "http://127.0.0.1:" + agentPort);
        } else {
          ui.println(L("Agent HTTP URLs:", "Agent HTTP URLlari:", "Agent HTTP URL-адреса:"));
          for (String url : agentUrls) {
            ui.println("  " + url);
          }
        }
      } else {
        ui.println(L("Agent HTTP failed to start on port ", "Agent HTTP ishga tushmadi, port: ", "Agent HTTP не запустился, порт: ") + agentPort);
      }
    }

    setupCommands(registry);
    try {
      menuLoop(ui, reader, erp, registry);
    } finally {
      if (agent != null) agent.stop();
      if (ERP_AGENT != null) ERP_AGENT.shutdown();
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
        ctx.ui().println(L("Already connected. Use 'disconnect' first.",
            "Allaqachon ulangan. Avval 'disconnect' qiling.",
            "Уже подключено. Сначала выполните 'disconnect'."));
        return;
      }
      String ip = args.get(1);
      int port = args.size() >= 3 ? parseInt(args.get(2), 27011) : 27011;
      int readerType = args.size() >= 4 ? parseInt(args.get(3), 4) : 4;
      int log = args.size() >= 5 ? parseInt(args.get(4), 0) : 0;

      Result r = ctx.reader().connect(ip, port, readerType, log, tag -> handleTag(ctx, tag), () -> {});
      if (!r.ok()) {
        ctx.ui().println(L("Connect failed: ", "Ulanish xato: ", "Ошибка подключения: ") + r.code());
        return;
      }
      rememberConnection(ip, port, readerType, log);
      ctx.ui().println(L("Connected: ", "Ulandi: ", "Подключено: ") + ip + "@" + port + " (readerType=" + readerType + ")");
    });

    registry.register("disconnect", "disconnect", (args, ctx) -> {
      Result r = ctx.reader().disconnect();
      if (!r.ok()) {
        ctx.ui().println(L("Disconnect failed: ", "Uzish xato: ", "Ошибка отключения: ") + r.code());
      } else {
        ctx.ui().println(L("Disconnected.", "Uzildi.", "Отключено."));
      }
    }, "disc");

    registry.register("scan", "scan [ports|auto|auto+] [readerType] [log] [prefix]", (args, ctx) -> {
      if (ctx.reader().isConnected()) {
        ctx.ui().println(L("Already connected. Use 'disconnect' first.",
            "Allaqachon ulangan. Avval 'disconnect' qiling.",
            "Уже подключено. Сначала выполните 'disconnect'."));
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
        ctx.ui().println(L("No LAN prefixes found. Provide prefix like 192.168.1",
            "LAN prefiks topilmadi. Masalan: 192.168.1",
            "LAN префиксы не найдены. Пример: 192.168.1"));
        return;
      }

      for (String pfx : prefixes) {
        for (int p : ports) {
          ctx.ui().println(L("Scanning subnet ", "Subnet skan: ", "Сканирование подсети ") + pfx + ".0/24 "
              + L("on port ", "port: ", "порт ") + p + " ...");
          NetworkScanner.HostPort hp = NetworkScanner.findReader(
              List.of(pfx), List.of(p), readerType, log, Duration.ofMillis(200)
          );
          if (hp != null) {
            Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, tag -> handleTag(ctx, tag), () -> {});
            if (r.ok()) {
              ctx.ui().println(L("Connected: ", "Ulandi: ", "Подключено: ") + hp.host() + "@" + hp.port());
              return;
            }
          }
        }
      }
      ctx.ui().println(L("No reader found.", "Reader topilmadi.", "Ридер не найден."));
    });

    registry.register("info", "info", (args, ctx) -> {
      ReaderInfo info = ctx.reader().getInfo();
      if (!info.result().ok()) {
        ctx.ui().println(L("GetUHFInformation failed: ", "GetUHFInformation xato: ", "GetUHFInformation ошибка: ") + info.result().code());
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
        ctx.ui().println(L("Serial number not available.", "Seriya raqami mavjud emas.", "Серийный номер недоступен."));
      } else {
        ctx.ui().println(L("Serial: ", "Seriya: ", "Серийный: ") + sn);
      }
    });

    registry.register("power", "power <0-33>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: power <0-33>");
        return;
      }
      int p = parseInt(args.get(1), -1);
      if (p < 0) {
        ctx.ui().println(L("Invalid power.", "Noto'g'ri quvvat.", "Неверная мощность."));
        return;
      }
      Result r = ctx.reader().setPower(p);
      ctx.ui().println(r.ok()
          ? L("Power set: ", "Quvvat o'rnatildi: ", "Мощность установлена: ") + p
          : L("SetRfPower failed: ", "SetRfPower xato: ", "SetRfPower ошибка: ") + r.code());
    });

    registry.register("antpower", "antpower get | set <0-33> [count]", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: antpower get | set <0-33> [count]");
        return;
      }
      if (!ctx.reader().isConnected()) {
        ctx.ui().println(L("Not connected.", "Ulanmagan.", "Не подключено."));
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
        ctx.ui().println(L("Invalid antenna or frequency.", "Antenna yoki chastota noto'g'ri.", "Неверная антенна или частота."));
        return;
      }
      int freqKhz = (int) Math.round(freq * 1000.0);
      ReturnLossInfo info = ctx.reader().measureReturnLoss(ant, freqKhz);
      if (!info.result().ok()) {
        ctx.ui().println(L("MeasureReturnLoss failed: ", "MeasureReturnLoss xato: ", "MeasureReturnLoss ошибка: ") + info.result().code());
      } else {
        ctx.ui().println(L("ReturnLoss ", "ReturnLoss ", "ReturnLoss ")
            + L("ant=", "ant=", "ант=") + ant + " "
            + L("freq=", "chastota=", "частота=") + formatMHz(freq) + " "
            + L("loss=", "yo'qotish=", "потери=") + info.lossDb() + " dB");
      }
    });

    registry.register("wpower", "wpower get | set <0-33> [mode]", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: wpower get | set <0-33> [mode]");
        ctx.ui().println(L("mode: 0=normal, 1=high (default=0)",
            "rejim: 0=oddiy, 1=yuqori (standart=0)",
            "режим: 0=обычный, 1=высокий (по умолч.=0)"));
        return;
      }
      if (!ctx.reader().isConnected()) {
        ctx.ui().println(L("Not connected.", "Ulanmagan.", "Не подключено."));
        return;
      }
      String sub = args.get(1).toLowerCase();
      if (sub.equals("get")) {
        WritePowerInfo info = ctx.reader().getWritePower();
        if (!info.result().ok()) {
          ctx.ui().println(L("GetWritePower failed: ", "GetWritePower xato: ", "GetWritePower ошибка: ") + info.result().code());
        } else {
          ctx.ui().println(L("WritePower=", "Yozish quvvati=", "Мощность записи=") + info.power()
              + " dBm " + L("mode=", "rejim=", "режим=") + (info.highMode() ? L("high", "yuqori", "высокий") : L("normal", "oddiy", "обычный")));
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
          ctx.ui().println(L("Invalid power.", "Noto'g'ri quvvat.", "Неверная мощность."));
          return;
        }
        int mode = args.size() >= 4 ? parseInt(args.get(3), 0) : 0;
        boolean high = mode == 1;
        Result r = ctx.reader().setWritePower(p, high);
        ctx.ui().println(r.ok()
            ? L("Write power set: ", "Yozish quvvati o'rnatildi: ", "Мощность записи установлена: ") + p
            + " (" + (high ? L("high", "yuqori", "высокий") : L("normal", "oddiy", "обычный")) + ")"
            : L("SetWritePower failed: ", "SetWritePower xato: ", "SetWritePower ошибка: ") + r.code());
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
        ctx.ui().println(L("Invalid region parameters.", "Mintaqa parametrlari noto'g'ri.", "Неверные параметры региона."));
        return;
      }
      Result r = ctx.reader().setRegion(band, max, min);
      ctx.ui().println(r.ok()
          ? L("Region set.", "Mintaqa o'rnatildi.", "Регион установлен.")
          : L("SetRegion failed: ", "SetRegion xato: ", "SetRegion ошибка: ") + r.code());
    });

    registry.register("beep", "beep <0|1>", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: beep <0|1>");
        return;
      }
      int v = parseInt(args.get(1), -1);
      if (v != 0 && v != 1) {
        ctx.ui().println(L("Invalid beep value.", "Noto'g'ri signal qiymati.", "Неверное значение сигнала."));
        return;
      }
      Result r = ctx.reader().setBeep(v);
      ctx.ui().println(r.ok()
          ? L("Beep set: ", "Signal o'rnatildi: ", "Сигнал установлен: ") + v
          : L("SetBeepNotification failed: ", "SetBeepNotification xato: ", "SetBeepNotification ошибка: ") + r.code());
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
          ctx.ui().println(L("GetGPIOStatus failed: ", "GetGPIOStatus xato: ", "GetGPIOStatus ошибка: ") + st.result().code());
        } else {
          ctx.ui().println(L("GPIO mask: ", "GPIO maska: ", "GPIO маска: ") + "0x" + Integer.toHexString(st.mask()));
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
          ctx.ui().println(L("Invalid mask.", "Noto'g'ri maska.", "Неверная маска."));
          return;
        }
        Result r = ctx.reader().setGpio(mask);
        ctx.ui().println(r.ok()
            ? L("GPIO set: ", "GPIO o'rnatildi: ", "GPIO установлено: ") + "0x" + Integer.toHexString(mask)
            : L("SetGPIO failed: ", "SetGPIO xato: ", "SetGPIO ошибка: ") + r.code());
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
        ctx.ui().println(L("Invalid relay value.", "Noto'g'ri rele qiymati.", "Неверное значение реле."));
        return;
      }
      Result r = ctx.reader().setRelay(v);
      ctx.ui().println(r.ok()
          ? L("Relay set: ", "Rele o'rnatildi: ", "Реле установлено: ") + v
          : L("SetRelay failed: ", "SetRelay xato: ", "SetRelay ошибка: ") + r.code());
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
        ctx.ui().println(L("Invalid antenna args.", "Noto'g'ri antenna argumentlari.", "Неверные аргументы антенны."));
        return;
      }
      Result r = ctx.reader().setAntenna(a1, a2);
      ctx.ui().println(r.ok()
          ? L("Antenna set.", "Antenna o'rnatildi.", "Антенна установлена.")
          : L("SetAntenna failed: ", "SetAntenna xato: ", "SetAntenna ошибка: ") + r.code());
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
        ctx.ui().println(L("Invalid parameters.", "Noto'g'ri parametrlar.", "Неверные параметры."));
        return;
      }
      String data = ctx.reader().readDataByEpc(epc, mem, wordPtr, num, pwd);
      if (data == null) {
        ctx.ui().println(L("Read failed.", "O'qish xato.", "Чтение не удалось."));
      } else {
        ctx.ui().println(L("Data: ", "Ma'lumot: ", "Данные: ") + data);
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
        ctx.ui().println(L("Invalid parameters.", "Noto'g'ri parametrlar.", "Неверные параметры."));
        return;
      }
      String data = ctx.reader().readDataByTid(tid, mem, wordPtr, num, pwd);
      if (data == null) {
        ctx.ui().println(L("Read failed.", "O'qish xato.", "Чтение не удалось."));
      } else {
        ctx.ui().println(L("Data: ", "Ma'lumot: ", "Данные: ") + data);
      }
    });

    registry.register("write-epc", "write-epc <epc> <mem> <wordPtr> <password> <data>", (args, ctx) -> {
      if (args.size() < 6) {
        ctx.ui().println("Usage: write-epc <epc> <mem> <wordPtr> <password> <data>");
        return;
      }
      if (!ctx.ui().confirm(L("Write EPC memory?", "EPC xotirasiga yozilsinmi?", "Записать память EPC?"))) return;
      String epc = args.get(1);
      int mem = parseInt(args.get(2), -1);
      int wordPtr = parseInt(args.get(3), -1);
      String pwd = args.get(4);
      String data = args.get(5);
      if (mem < 0 || wordPtr < 0) {
        ctx.ui().println(L("Invalid parameters.", "Noto'g'ri parametrlar.", "Неверные параметры."));
        return;
      }
      Result r = ctx.reader().writeDataByEpc(epc, mem, wordPtr, pwd, data);
      ctx.ui().println(r.ok()
          ? L("Write success.", "Yozish muvaffaqiyatli.", "Запись успешна.")
          : L("Write failed: ", "Yozish xato: ", "Ошибка записи: ") + r.code());
    });

    registry.register("write-tid", "write-tid <tid> <mem> <wordPtr> <password> <data>", (args, ctx) -> {
      if (args.size() < 6) {
        ctx.ui().println("Usage: write-tid <tid> <mem> <wordPtr> <password> <data>");
        return;
      }
      if (!ctx.ui().confirm(L("Write TID memory?", "TID xotirasiga yozilsinmi?", "Записать память TID?"))) return;
      String tid = args.get(1);
      int mem = parseInt(args.get(2), -1);
      int wordPtr = parseInt(args.get(3), -1);
      String pwd = args.get(4);
      String data = args.get(5);
      if (mem < 0 || wordPtr < 0) {
        ctx.ui().println(L("Invalid parameters.", "Noto'g'ri parametrlar.", "Неверные параметры."));
        return;
      }
      Result r = ctx.reader().writeDataByTid(tid, mem, wordPtr, pwd, data);
      ctx.ui().println(r.ok()
          ? L("Write success.", "Yozish muvaffaqiyatli.", "Запись успешна.")
          : L("Write failed: ", "Yozish xato: ", "Ошибка записи: ") + r.code());
    });

    registry.register("write-epc-id", "write-epc-id <epc> <password>", (args, ctx) -> {
      if (args.size() < 3) {
        ctx.ui().println("Usage: write-epc-id <epc> <password>");
        return;
      }
      if (!ctx.ui().confirm(L("Overwrite EPC ID?", "EPC ID ustiga yozilsinmi?", "Перезаписать EPC ID?"))) return;
      String epc = args.get(1);
      String pwd = args.get(2);
      Result r = ctx.reader().writeEpc(epc, pwd);
      ctx.ui().println(r.ok()
          ? L("EPC updated.", "EPC yangilandi.", "EPC обновлён.")
          : L("WriteEPC failed: ", "WriteEPC xato: ", "WriteEPC ошибка: ") + r.code());
    });

    registry.register("write-epc-by-tid", "write-epc-by-tid <tid> <epc> <password>", (args, ctx) -> {
      if (args.size() < 4) {
        ctx.ui().println("Usage: write-epc-by-tid <tid> <epc> <password>");
        return;
      }
      if (!ctx.ui().confirm(L("Overwrite EPC by TID?", "TID orqali EPC ustiga yozilsinmi?", "Перезаписать EPC по TID?"))) return;
      String tid = args.get(1);
      String epc = args.get(2);
      String pwd = args.get(3);
      Result r = ctx.reader().writeEpcByTid(tid, epc, pwd);
      ctx.ui().println(r.ok()
          ? L("EPC updated.", "EPC yangilandi.", "EPC обновлён.")
          : L("WriteEPCByTID failed: ", "WriteEPCByTID xato: ", "WriteEPCByTID ошибка: ") + r.code());
    });

    registry.register("lock", "lock <epc> <select> <protect> <password>", (args, ctx) -> {
      if (args.size() < 5) {
        ctx.ui().println("Usage: lock <epc> <select> <protect> <password>");
        return;
      }
      if (!ctx.ui().confirm(L("Lock tag memory? This may be irreversible.",
          "Tag xotirasini qulflaysizmi? Qaytarib bo'lmasligi mumkin.",
          "Заблокировать память тега? Это может быть необратимо."))) return;
      String epc = args.get(1);
      int select = parseInt(args.get(2), -1);
      int protect = parseInt(args.get(3), -1);
      String pwd = args.get(4);
      if (select < 0 || protect < 0) {
        ctx.ui().println(L("Invalid parameters.", "Noto'g'ri parametrlar.", "Неверные параметры."));
        return;
      }
      Result r = ctx.reader().lock(epc, select, protect, pwd);
      ctx.ui().println(r.ok()
          ? L("Lock success.", "Qulflash muvaffaqiyatli.", "Блокировка успешна.")
          : L("Lock failed: ", "Qulflash xato: ", "Ошибка блокировки: ") + r.code());
    });

    registry.register("kill", "kill <epc> <password>", (args, ctx) -> {
      if (args.size() < 3) {
        ctx.ui().println("Usage: kill <epc> <password>");
        return;
      }
      if (!ctx.ui().confirm(L("KILL tag? This is permanent and irreversible.",
          "Tagni o'chirasizmi? Bu qaytarib bo'lmaydi.",
          "Уничтожить тег? Это необратимо."))) return;
      String epc = args.get(1);
      String pwd = args.get(2);
      Result r = ctx.reader().kill(epc, pwd);
      ctx.ui().println(r.ok()
          ? L("Kill success.", "O'chirish muvaffaqiyatli.", "Уничтожение успешно.")
          : L("Kill failed: ", "O'chirish xato: ", "Ошибка уничтожения: ") + r.code());
    });

    registry.register("inv", "inv start|stop", (args, ctx) -> {
      if (args.size() < 2) {
        ctx.ui().println("Usage: inv start|stop");
        return;
      }
      String sub = args.get(1).toLowerCase();
      if (sub.equals("start")) {
        if (!ctx.reader().isConnected()) {
          ctx.ui().println(L("Not connected.", "Ulanmagan.", "Не подключено."));
          return;
        }

        // Many vendor firmwares expect inventory antenna values like 0x80.. (Ant1..),
        // while users often enter 1..N or 0..N-1. Normalize to reduce "255" errors.
        ensureInventoryAntennaNormalized(ctx);

        Result r = ctx.reader().startInventory();
        if (!r.ok() && r.code() == 255) {
          // Auto-try each antenna port to find one that can start inventory.
          Result rr = tryStartInventoryOnAnyAntenna(ctx);
          if (rr.ok()) {
            ctx.ui().println(L("Inventory started.", "Inventar boshlandi.", "Инвентарь запущен."));
            return;
          }
          r = rr;
        }

        ctx.ui().println(r.ok()
            ? L("Inventory started.", "Inventar boshlandi.", "Инвентарь запущен.")
            : L("StartRead failed: ", "StartRead xato: ", "StartRead ошибка: ") + r.code());
        return;
      }
      if (sub.equals("stop")) {
        Result r = ctx.reader().stopInventory();
        ctx.ui().println(r.ok()
            ? L("Inventory stopped.", "Inventar to'xtadi.", "Инвентарь остановлен.")
            : L("StopRead failed: ", "StopRead xato: ", "StopRead ошибка: ") + r.code());
        return;
      }
      ctx.ui().println("Usage: inv start|stop");
    });

    registry.register("inv-once", "inv-once [ms]", (args, ctx) -> {
      int ms = args.size() >= 2 ? parseInt(args.get(1), 1000) : 1000;
      if (ms < 50) ms = 50;
      if (!ctx.reader().isConnected()) {
        ctx.ui().println(L("Not connected.", "Ulanmagan.", "Не подключено."));
        return;
      }

      ensureInventoryAntennaNormalized(ctx);

      Result r = ctx.reader().startInventory();
      if (!r.ok()) {
        if (r.code() == 255) {
          Result rr = tryStartInventoryOnAnyAntenna(ctx);
          if (rr.ok()) {
            r = rr;
          } else {
            ctx.ui().println(L("StartRead failed: ", "StartRead xato: ", "StartRead ошибка: ") + rr.code());
            return;
          }
        } else {
          ctx.ui().println(L("StartRead failed: ", "StartRead xato: ", "StartRead ошибка: ") + r.code());
          return;
        }
      }
      ctx.ui().println(L("Scanning for ", "Skan qilinmoqda ", "Сканирование ") + ms + " ms ...");
      try {
        Thread.sleep(ms);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      Result stop = ctx.reader().stopInventory();
      ctx.ui().println(stop.ok()
          ? L("Scan stopped.", "Skan to'xtadi.", "Сканирование остановлено.")
          : L("StopRead failed: ", "StopRead xato: ", "StopRead ошибка: ") + stop.code());
    }, "once");

    registry.register("inv-param", "inv-param get | set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]",
        (args, ctx) -> {
          if (args.size() < 2) {
            ctx.ui().println("Usage: inv-param get | set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]");
            return;
          }
          if (!ctx.reader().isConnected()) {
            ctx.ui().println(L("Not connected.", "Ulanmagan.", "Не подключено."));
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
              int antenna = normalizeInventoryAntenna(parseInt(args.get(11), current.antenna()), ctx.reader().getAntennaCount());
              String password = args.get(12);
              int address = args.size() >= 14 ? parseInt(args.get(13), current.address()) : current.address();
              InventoryParams p = new InventoryParams(Result.success(), address, tidPtr, tidLen, session, q, scanTime, antenna,
                  readType, readMem, readPtr, readLen, password);
              Result r = ctx.reader().setInventoryParams(p);
              ctx.ui().println(r.ok()
                  ? L("Inventory params updated.", "Inventar parametrlari yangilandi.", "Параметры обновлены.")
                  : L("SetInventoryParameter failed: ", "SetInventoryParameter xato: ", "SetInventoryParameter ошибка: ") + r.code());
              return;
            }
            InventoryParams p = promptInventoryParams(ctx.ui(), current, ctx.reader().getAntennaCount());
            Result r = ctx.reader().setInventoryParams(p);
            ctx.ui().println(r.ok()
                ? L("Inventory params updated.", "Inventar parametrlari yangilandi.", "Параметры обновлены.")
                : L("SetInventoryParameter failed: ", "SetInventoryParameter xato: ", "SetInventoryParameter ошибка: ") + r.code());
            return;
          }
          ctx.ui().println("Usage: inv-param get | set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]");
        }, "invp");
  }

  private static void printHelp(CommandRegistry registry, ConsoleUi ui) {
    ui.println(L("Commands:", "Buyruqlar:", "Команды:"));
    for (var def : registry.listUnique()) {
      ui.println("  " + def.name() + " - " + def.help());
    }
    ui.println("  help - " + L("show this help", "yordamni ko'rsatish", "показать справку"));
    ui.println("  menu - " + L("back to menu", "menyuga qaytish", "вернуться в меню"));
    ui.println("  quit - " + L("exit", "chiqish", "выход"));
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
      String status = reader.isConnected()
          ? L("connected", "ulangan", "подключено")
          : L("disconnected", "uzilgan", "отключено");
      int sel = ui.selectOption(
          L("Main", "Asosiy", "Главная") + " [" + status + "]",
          new String[]{
              L("Connection", "Ulanish", "Подключение"),
              L("Scan", "Skanner", "Скан"),
              L("Inventory", "Inventar", "Инвентарь"),
              L("Tag Ops", "Tag amallari", "Операции тегов"),
              L("Settings", "Sozlamalar", "Настройки"),
              L("Quit", "Chiqish", "Выход")
          },
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
          case SETTINGS -> 4;
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
          menuScan(ui, ctx, registry);
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
          menuSettings(ui, ctx, registry);
          forwardTarget = MenuId.SETTINGS;
        }
        default -> {
          reader.disconnect();
          return;
        }
      }
    }
  }

  private static void menuSettings(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {
        L("Reader settings", "Reader sozlamalari", "Настройки ридера"),
        L("ERP Push", "ERP Push", "ERP Push"),
        L("Language", "Til", "Язык"),
        L("Info", "Ma'lumot", "Инфо"),
        L("About", "Haqida", "О программе"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Settings", "Sozlamalar", "Настройки"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 5) return;
      switch (sel) {
        case 0 -> menuConfig(ui, ctx, registry);
        case 1 -> menuErp(ui, ctx);
        case 2 -> menuLanguage(ui);
        case 3 -> menuInfo(ui, ctx, registry);
        case 4 -> menuAbout(ui);
      }
    }
  }

  private static void attemptAutoConnect(ConsoleUi ui, CommandContext ctx) {
    int readerType = 4;
    int log = 0;
    List<Integer> ports = List.of(27011, 2022);
    LastConnection last = loadLastConnection();
    if (last != null) {
      readerType = last.readerType;
      log = last.log;
      if (isPortOpen(last.host, last.port, 150)) {
        Result r = ctx.reader().connect(last.host, last.port, readerType, log, tag -> handleTag(ctx, tag), () -> {});
        if (r.ok()) {
          ui.setStatusMessage(L("Auto-connect: ", "Avto-ulan: ", "Автоподключение: ") + last.host + "@" + last.port);
          return;
        }
      }
    }
    List<String> prefixes = NetworkScanner.detectPrefixes();
    List<String> usbPrefixes = NetworkScanner.detectUsbPrefixes();
    List<String> all = new ArrayList<>();
    all.addAll(prefixes);
    for (String p : usbPrefixes) {
      if (!all.contains(p)) all.add(p);
    }
    if (all.isEmpty()) {
      ui.setStatusMessage(L("Auto-connect: no LAN/USB prefixes found.", "Avto-ulan: LAN/USB prefikslar topilmadi.", "Автоподключение: LAN/USB префиксы не найдены."));
      return;
    }
    final NetworkScanner.HostPort[] found = {null};
    final int rt = readerType;
    final int lg = log;
    ui.runWithSpinner(L("Auto-connecting", "Avto-ulanmoqda", "Автоподключение"), () -> {
      for (String pfx : all) {
        for (int p : ports) {
          NetworkScanner.HostPort hp = NetworkScanner.findReader(
              List.of(pfx), List.of(p), rt, lg, Duration.ofMillis(120)
          );
          if (hp != null) {
            found[0] = hp;
            return;
          }
        }
      }
    });
    if (found[0] == null) {
      ui.setStatusMessage(L("Auto-connect: no reader found.", "Avto-ulan: reader topilmadi.", "Автоподключение: ридер не найден."));
      return;
    }
    NetworkScanner.HostPort hp = found[0];
    Result r = ctx.reader().connect(hp.host(), hp.port(), readerType, log, tag -> handleTag(ctx, tag), () -> {});
    if (r.ok()) {
      rememberConnection(hp.host(), hp.port(), readerType, log);
      ui.setStatusMessage(L("Auto-connect: ", "Avto-ulan: ", "Автоподключение: ") + hp.host() + "@" + hp.port());
    } else {
      ui.setStatusMessage(L("Auto-connect failed: ", "Avto-ulan xato: ", "Автоподключение ошибка: ") + r.code());
    }
  }

  private static void menuAbout(ConsoleUi ui) {
    List<String> lines = readReadmeLines();
    if (lines.isEmpty()) {
      ui.showLines(L("About", "Haqida", "О программе"), List.of(
          L("README.md not found.", "README.md topilmadi.", "README.md не найден."),
          L("Expected in current folder or parent.", "Joriy papka yoki yuqori papkada kutilgan.", "Ожидался в текущей или родительской папке.")
      ));
      return;
    }
    ui.viewLinesPaged(L("About (README)", "Haqida (README)", "О программе (README)"), lines, 12);
  }

  private static void menuConnection(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(
          L("Connection", "Ulanish", "Подключение"),
          new String[]{L("Connect", "Ulanish", "Подключить"), L("Disconnect", "Uzish", "Отключить"), L("Back", "Orqaga", "Назад")},
          0
      );
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      if (sel == 1) {
        registry.execute(List.of("disconnect"), ctx);
        continue;
      }
      String ip = askString(ui, L("IP address", "IP manzil", "IP адрес"));
      if (ip == null || ip.isBlank()) {
        ui.println(L("IP is required.", "IP kiritilishi shart.", "IP обязателен."));
        continue;
      }
      int port = askInt(ui, L("Port", "Port", "Порт"), 27011);
      int readerType = selectReaderType(ui, 4);
      if (readerType == ConsoleUi.NAV_BACK) return;
      int log = selectLog(ui, 0);
      if (log == ConsoleUi.NAV_BACK) return;
      registry.execute(List.of("connect", ip, String.valueOf(port), String.valueOf(readerType), String.valueOf(log)), ctx);
    }
  }

  private static void menuScan(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(
          L("Scan", "Skanner", "Скан"),
          new String[]{
              L("Start (auto)", "Boshlash (auto)", "Старт (авто)"),
              L("Stop", "To'xtatish", "Стоп"),
              L("Auto-connect", "Avto-ulan", "Автоподключение"),
              L("Back", "Orqaga", "Назад")
          },
          0
      );
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 3) return;
      switch (sel) {
        case 0 -> {
          if (!ctx.reader().isConnected()) {
            attemptAutoConnect(ui, ctx);
          }
          if (!ctx.reader().isConnected()) {
            ui.setStatusMessage(L("No reader connected.", "Reader ulanmagan.", "Ридер не подключен."));
            continue;
          }
          registry.execute(List.of("inv", "start"), ctx);
        }
        case 1 -> registry.execute(List.of("inv", "stop"), ctx);
        case 2 -> attemptAutoConnect(ui, ctx);
      }
    }
  }

  private static void menuLanguage(ConsoleUi ui) {
    Lang[] options = {Lang.UZ, Lang.EN, Lang.RU};
    String[] labels = {options[0].label, options[1].label, options[2].label};
    int def = 0;
    for (int i = 0; i < options.length; i++) {
      if (options[i] == LANG) {
        def = i;
        break;
      }
    }
    int sel = ui.selectOption(L("Language", "Til", "Язык"), labels, def);
    if (sel == ConsoleUi.NAV_BACK) return;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel < 0 || sel >= options.length) return;
    LANG = options[sel];
    saveLang(LANG);
    ui.setStatusMessage(L("Language set", "Til o'rnatildi", "Язык установлен") + ": " + LANG.label);
  }

  private static void menuInventory(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(
          L("Inventory", "Inventar", "Инвентарь"),
          new String[]{
              L("Start", "Boshlash", "Старт"),
              L("Stop", "To'xtatish", "Стоп"),
              L("Once (timed)", "Bir marta (vaqt)", "Один раз (время)"),
              L("Params (view)", "Param (ko'rish)", "Параметры (просм.)"),
              L("Params (set)", "Param (sozlash)", "Параметры (установ.)"),
              L("Back", "Orqaga", "Назад")
          },
          0
      );
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 5) return;
      if (sel == 0) registry.execute(List.of("inv", "start"), ctx);
      if (sel == 1) registry.execute(List.of("inv", "stop"), ctx);
      if (sel == 2) {
        int ms = askInt(ui, L("Duration ms", "Davomiylik (ms)", "Длительность (мс)"), 1000);
        registry.execute(List.of("inv-once", String.valueOf(ms)), ctx);
        pause(ui);
      }
      if (sel == 3) {
        if (!ctx.reader().isConnected()) {
          ui.setStatusMessage(L("Not connected.", "Ulanmagan.", "Не подключено."));
          continue;
        }
        InventoryParams p = ctx.reader().getInventoryParams();
        printInventoryParams(ui, p);
      }
      if (sel == 4) {
        if (!ctx.reader().isConnected()) {
          ui.setStatusMessage(L("Not connected.", "Ulanmagan.", "Не подключено."));
          continue;
        }
        InventoryParams current = ctx.reader().getInventoryParams();
        InventoryParams p = promptInventoryParams(ui, current, ctx.reader().getAntennaCount());
        Result r = ctx.reader().setInventoryParams(p);
        ui.println(r.ok() ? L("Inventory params updated.", "Inventar parametrlari yangilandi.", "Параметры обновлены.")
            : L("SetInventoryParameter failed: ", "SetInventoryParameter xatosi: ", "Ошибка SetInventoryParameter: ") + r.code());
        pause(ui);
      }
    }
  }

  private static void menuTagOps(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {
        L("Read EPC", "EPC o'qish", "Читать EPC"),
        L("Read TID", "TID o'qish", "Читать TID"),
        L("Write EPC", "EPC yozish", "Записать EPC"),
        L("Write TID", "TID yozish", "Записать TID"),
        L("Write EPC ID", "EPC ID yozish", "Записать EPC ID"),
        L("Write EPC by TID", "TID orqali EPC yozish", "Записать EPC по TID"),
        L("Lock", "Qulflash", "Блокировка"),
        L("Kill", "O'chirish", "Уничтожить"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Tag Ops", "Tag amallari", "Операции тегов"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 8) return;
      switch (sel) {
        case 0 -> registry.execute(List.of("read-epc",
            askString(ui, L("EPC", "EPC", "EPC")),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, L("WordPtr", "WordPtr", "WordPtr"), 0)),
            String.valueOf(askInt(ui, L("Num", "Soni", "Кол-во"), 1)),
            askString(ui, L("Password", "Parol", "Пароль"))), ctx);
        case 1 -> registry.execute(List.of("read-tid",
            askString(ui, L("TID", "TID", "TID")),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, L("WordPtr", "WordPtr", "WordPtr"), 0)),
            String.valueOf(askInt(ui, L("Num", "Soni", "Кол-во"), 1)),
            askString(ui, L("Password", "Parol", "Пароль"))), ctx);
        case 2 -> registry.execute(List.of("write-epc",
            askString(ui, L("EPC", "EPC", "EPC")),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, L("WordPtr", "WordPtr", "WordPtr"), 0)),
            askString(ui, L("Password", "Parol", "Пароль")),
            askString(ui, L("Data", "Ma'lumot", "Данные"))), ctx);
        case 3 -> registry.execute(List.of("write-tid",
            askString(ui, L("TID", "TID", "TID")),
            String.valueOf(selectMem(ui)),
            String.valueOf(askInt(ui, L("WordPtr", "WordPtr", "WordPtr"), 0)),
            askString(ui, L("Password", "Parol", "Пароль")),
            askString(ui, L("Data", "Ma'lumot", "Данные"))), ctx);
        case 4 -> registry.execute(List.of("write-epc-id",
            askString(ui, L("EPC", "EPC", "EPC")),
            askString(ui, L("Password", "Parol", "Пароль"))), ctx);
        case 5 -> registry.execute(List.of("write-epc-by-tid",
            askString(ui, L("TID", "TID", "TID")),
            askString(ui, L("EPC", "EPC", "EPC")),
            askString(ui, L("Password", "Parol", "Пароль"))), ctx);
        case 6 -> registry.execute(List.of("lock",
            askString(ui, L("EPC", "EPC", "EPC")),
            String.valueOf(askInt(ui, L("Select", "Tanlash", "Выбор"), 0)),
            String.valueOf(askInt(ui, L("Protect", "Himoya", "Защита"), 0)),
            askString(ui, L("Password", "Parol", "Пароль"))), ctx);
        case 7 -> registry.execute(List.of("kill",
            askString(ui, L("EPC", "EPC", "EPC")),
            askString(ui, L("Password", "Parol", "Пароль"))), ctx);
      }
    }
  }

  private static void menuConfig(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {
        L("Tag Output", "Tag chiqishi", "Вывод тегов"),
        L("Agent URLs", "Agent URL", "URL агента"),
        L("RF Power", "RF quvvat", "RF мощность"),
        L("Read Profile", "O'qish profili", "Профиль чтения"),
        L("Write Power", "Yozish quvvati", "Мощность записи"),
        L("Per-Antenna Power", "Antenna bo'yicha", "По антеннам"),
        L("Region", "Mintaqa", "Регион"),
        L("Beep", "Signal", "Сигнал"),
        L("Antenna Check", "Antenna tekshiruv", "Проверка антенны"),
        L("Return Loss", "Qaytish yo'qotish", "Возвратные потери"),
        L("GPIO Get", "GPIO olish", "GPIO чтение"),
        L("GPIO Set", "GPIO sozlash", "GPIO запись"),
        L("Relay", "Rele", "Реле"),
        L("Antenna", "Antenna", "Антенна"),
        L("ERP Push", "ERP Push", "ERP Push"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Config/IO", "Sozlamalar/IO", "Настройки/IO"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 15) return;
      switch (sel) {
        case 0 -> {
          int mode = ui.selectOption(
              L("Tag Output", "Tag chiqishi", "Вывод тегов"),
              new String[]{L("Counts only", "Faqat son", "Только счет"), L("Show tag lines", "Tag satrlarini ko'rsat", "Показать строки тегов")},
              TAG_OUTPUT.show ? 1 : 0
          );
          if (mode == ConsoleUi.NAV_BACK) break;
          if (mode == ConsoleUi.NAV_FORWARD) mode = ui.getLastMenuIndex();
          TAG_OUTPUT.show = mode == 1;
          ui.setStatusMessage(TAG_OUTPUT.show
              ? L("Tag output: ON", "Tag chiqishi: Yoqildi", "Вывод тегов: Вкл")
              : L("Tag output: OFF", "Tag chiqishi: O'chirildi", "Вывод тегов: Выкл"));
        }
        case 1 -> {
          int port = parseInt(System.getenv("RFID_AGENT_PORT"), 0);
          if (port <= 0) {
            ui.showLines(L("Agent URLs", "Agent URL", "URL агента"),
                List.of(L("Agent HTTP is disabled.", "Agent HTTP o'chirilgan.", "Agent HTTP отключен."),
                    L("Set RFID_AGENT_PORT to enable.", "RFID_AGENT_PORT ni sozlang.", "Задайте RFID_AGENT_PORT.")));
            break;
          }
          List<String> urls = listAgentUrls(port);
          if (urls.isEmpty()) {
            ui.showLines(L("Agent URLs", "Agent URL", "URL агента"),
                List.of(L("No LAN IP found.", "LAN IP topilmadi.", "LAN IP не найден."),
                    L("Use: http://127.0.0.1:" + port, "Foydalaning: http://127.0.0.1:" + port, "Используйте: http://127.0.0.1:" + port)));
          } else {
            ui.showLines(L("Agent URLs", "Agent URL", "URL агента"), urls);
          }
        }
        case 2 -> {
          int p = selectPowerValue(ui, L("RF Power", "RF quvvat", "RF мощность"), 30);
          if (p == ConsoleUi.NAV_BACK) return;
          registry.execute(List.of("power", String.valueOf(p)), ctx);
        }
        case 3 -> {
          String[] profiles = {
              L("Short range", "Qisqa masofa", "Короткая дистанция"),
              L("Balanced", "Muvozanat", "Сбалансировано"),
              L("Long range", "Uzoq masofa", "Длинная дистанция"),
              L("Back", "Orqaga", "Назад")
          };
          int selProfile = ui.selectOption(L("Read Profile", "O'qish profili", "Профиль чтения"), profiles, 1);
          if (selProfile == ConsoleUi.NAV_BACK) break;
          if (selProfile == ConsoleUi.NAV_FORWARD) selProfile = ui.getLastMenuIndex();
          if (selProfile == 3) break;
          Double meters = selectDistanceMeters(ui, selProfile);
          if (meters == null) break;
          int power = estimatePowerForProfile(selProfile, meters);
          if (!ctx.reader().isConnected()) {
            ui.setStatusMessage(L("Not connected. Suggested power: ", "Ulanmagan. Tavsiya quvvat: ", "Не подключено. Реком. мощность: ")
                + power + " dBm (" + meters + " m)");
            break;
          }
          Result r = ctx.reader().setPower(power);
          ui.setStatusMessage(r.ok()
              ? L("Read profile set: ", "O'qish profili o'rnatildi: ", "Профиль чтения установлен: ")
              + profiles[selProfile] + " (" + meters + " m → " + power + " dBm)"
              : L("SetRfPower failed: ", "SetRfPower xato: ", "SetRfPower ошибка: ") + r.code());
        }
        case 4 -> menuWritePower(ui, ctx, registry);
        case 5 -> menuAntennaPower(ui, ctx);
        case 6 -> {
          RegionSelection region = selectRegion(ui);
          if (region == null) break;
          registry.execute(List.of("region",
              String.valueOf(region.band()),
              String.valueOf(region.maxFreq()),
              String.valueOf(region.minFreq())), ctx);
        }
        case 7 -> {
          int b = ui.selectOption(L("Beep", "Signal", "Сигнал"),
              new String[]{L("On (1)", "Yoqilgan (1)", "Вкл (1)"), L("Off (0)", "O'chirilgan (0)", "Выкл (0)")}, 0);
          if (b == ConsoleUi.NAV_BACK) return;
          if (b == ConsoleUi.NAV_FORWARD) b = ui.getLastMenuIndex();
          int val = b == 0 ? 1 : 0;
          registry.execute(List.of("beep", String.valueOf(val)), ctx);
        }
        case 8 -> menuAntennaCheck(ui, ctx);
        case 9 -> menuReturnLoss(ui, ctx);
        case 10 -> registry.execute(List.of("gpio", "get"), ctx);
        case 11 -> registry.execute(List.of("gpio", "set", String.valueOf(askInt(ui, L("GPIO mask", "GPIO maska", "GPIO маска"), 0))), ctx);
        case 12 -> registry.execute(List.of("relay", String.valueOf(askInt(ui, L("Relay value", "Rele qiymati", "Значение реле"), 0))), ctx);
        case 13 -> registry.execute(List.of("antenna",
            String.valueOf(askInt(ui, L("Arg1", "Arg1", "Arg1"), 0)),
            String.valueOf(askInt(ui, L("Arg2", "Arg2", "Arg2"), 0))), ctx);
        case 14 -> menuErp(ui, ctx);
      }
    }
  }

  private static void menuErp(ConsoleUi ui, CommandContext ctx) {
    String[] options = {
        L("Status", "Holat", "Статус"),
        L("Enable", "Yoqish", "Включить"),
        L("Disable", "O'chirish", "Выключить"),
        L("Set URL", "URL sozlash", "URL"),
        L("Test (fake)", "Test (soxta)", "Тест (fake)"),
        L("Set batch (ms)", "Batch (ms)", "Batch (мс)"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("ERP Push", "ERP Push", "ERP Push"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 6) return;
      ErpConfig cfg = copyErpConfig(ctx.erp().config());
      switch (sel) {
        case 0 -> ui.showLines(L("ERP Status", "ERP holati", "Статус ERP"), List.of(
            L("enabled", "yoqilgan", "включено") + "=" + cfg.enabled,
            L("url", "url", "url") + "=" + safe(cfg.baseUrl),
            L("endpoint", "endpoint", "endpoint") + "=" + safe(cfg.endpoint),
            L("device", "qurilma", "устройство") + "=" + safe(cfg.device),
            L("auth", "auth", "auth") + "=" + (cfg.auth == null || cfg.auth.isBlank() ? L("(empty)", "(bo'sh)", "(пусто)") : "***"),
            L("secret", "secret", "secret") + "=" + (cfg.secret == null || cfg.secret.isBlank() ? L("(empty)", "(bo'sh)", "(пусто)") : "***")
        ));
        case 1 -> {
          cfg.enabled = true;
          saveErpConfig(ctx.erp(), cfg);
          ui.setStatusMessage(L("ERP push enabled.", "ERP push yoqildi.", "ERP push включен."));
        }
        case 2 -> {
          cfg.enabled = false;
          saveErpConfig(ctx.erp(), cfg);
          ui.setStatusMessage(L("ERP push disabled.", "ERP push o'chirildi.", "ERP push выключен."));
        }
        case 3 -> {
          String url;
          while (true) {
            url = askStringOrBack(ui, L("ERP URL", "ERP URL", "ERP URL"), safe(cfg.baseUrl));
            if (url == null) break;
            if (!url.isBlank()) break;
            ui.setStatusMessage(L("ERP URL required.", "ERP URL kerak.", "Требуется ERP URL."));
          }
          if (url == null) break;
          String token = askStringOrBack(ui, L("ERP Token (api_key:api_secret)", "ERP Token (api_key:api_secret)", "ERP Token (api_key:api_secret)"), safe(cfg.auth));
          if (token == null) break;
          cfg.baseUrl = url;
          cfg.auth = token;
          saveErpConfig(ctx.erp(), cfg);
          final boolean[] ok = {false};
          ui.runWithSpinner(L("Checking ERP", "ERP tekshirish", "Проверка ERP"), () -> ok[0] = ctx.erp().testOnce());
          if (ok[0]) {
            ui.setStatusMessage(L("ERP check: ok", "ERP tekshiruv: ok", "ERP проверка: ok"));
          } else {
            String msg = ctx.erp().lastErrMsg();
            ui.setStatusMessage(msg == null || msg.isBlank()
                ? L("ERP check: failed", "ERP tekshiruv: xato", "ERP проверка: ошибка")
                : L("ERP check: failed (", "ERP tekshiruv: xato (", "ERP проверка: ошибка (") + msg + ")");
          }
        }
        case 4 -> {
          int count = askInt(ui, L("Fake tags count", "Fake tag soni", "Кол-во fake тегов"), 5);
          final boolean[] ok = {false};
          ui.runWithSpinner(L("Sending test tags", "Test taglar yuborilmoqda", "Отправка тестовых тегов"),
              () -> ok[0] = ctx.erp().pushTestTags(count));
          if (ok[0]) {
            ui.setStatusMessage(L("ERP test tags: sent", "ERP test taglar: yuborildi", "ERP тестовые теги: отправлено"));
          } else {
            String msg = ctx.erp().lastErrMsg();
            ui.setStatusMessage(msg == null || msg.isBlank()
                ? L("ERP test tags: failed", "ERP test taglar: xato", "ERP тестовые теги: ошибка")
                : L("ERP test tags: failed (", "ERP test taglar: xato (", "ERP тестовые теги: ошибка (") + msg + ")");
          }
        }
        case 5 -> {
          String[] items = {
              L("Instant (0 ms)", "Darhol (0 ms)", "Сразу (0 мс)"),
              "10 ms",
              "20 ms",
              "50 ms",
              "100 ms",
              "150 ms",
              "200 ms",
              "250 ms",
              "500 ms",
              "1000 ms"
          };
          int defIdx = switch (cfg.batchMs) {
            case 0 -> 0;
            case 10 -> 1;
            case 20 -> 2;
            case 50 -> 3;
            case 100 -> 4;
            case 150 -> 5;
            case 200 -> 6;
            case 250 -> 7;
            case 500 -> 8;
            case 1000 -> 9;
            default -> 4;
          };
          int choice = ui.selectOption(L("Batch ms", "Batch ms", "Batch мс"), items, defIdx);
          if (choice == ConsoleUi.NAV_BACK) break;
          if (choice == ConsoleUi.NAV_FORWARD) choice = ui.getLastMenuIndex();
          int ms = switch (choice) {
            case 0 -> 0;
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 50;
            case 4 -> 100;
            case 5 -> 150;
            case 6 -> 200;
            case 7 -> 250;
            case 8 -> 500;
            case 9 -> 1000;
            default -> 100;
          };
          cfg.batchMs = ms;
          saveErpConfig(ctx.erp(), cfg);
          ui.setStatusMessage(L("Batch ms set: ", "Batch ms o'rnatildi: ", "Batch мс установлено: ") + ms);
        }
      }
    }
  }

  private static void menuAntennaPower(ConsoleUi ui, CommandContext ctx) {
    String[] options = {
        L("Set all", "Hammasini sozlash", "Установить все"),
        L("Set per-antenna", "Har antennani alohida", "По антеннам"),
        L("Get", "Ko'rish", "Получить"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Per-Antenna Power", "Antenna bo'yicha quvvat", "Мощность по антеннам"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 3) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage(L("Not connected.", "Ulanmagan.", "Не подключено."));
        continue;
      }
      int count = ctx.reader().getAntennaCount();
      if (sel == 2) {
        AntennaPowerInfo info = ctx.reader().getRfPowerByAnt(count);
        if (!info.result().ok()) {
          ui.setStatusMessage(L("GetRfPowerByAnt failed: ", "GetRfPowerByAnt xato: ", "GetRfPowerByAnt ошибка: ") + info.result().code());
          continue;
        }
        String[] lines = new String[info.powers().length];
        for (int i = 0; i < info.powers().length; i++) {
          lines[i] = L("Ant", "Ant", "Ант") + " " + (i + 1) + ": " + info.powers()[i] + " dBm";
        }
        ui.showLines(L("Per-Antenna Power", "Antenna bo'yicha quvvat", "Мощность по антеннам"), List.of(lines));
        continue;
      }
      if (sel == 0) {
        int p = selectPowerValue(ui, L("Set all power", "Hammasi quvvati", "Установить мощность"), 30);
        if (p == ConsoleUi.NAV_BACK) continue;
        int[] powers = new int[count];
        for (int i = 0; i < count; i++) powers[i] = p;
        Result r = ctx.reader().setRfPowerByAnt(powers);
        ui.setStatusMessage(r.ok()
            ? L("Per-antenna power set.", "Antenna quvvati sozlandi.", "Мощность по антеннам установлена.")
            : L("SetRfPowerByAnt failed: ", "SetRfPowerByAnt xato: ", "SetRfPowerByAnt ошибка: ") + r.code());
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
        int p = selectPowerValue(ui, L("Ant ", "Ant ", "Ант ") + (i + 1) + L(" Power", " quvvati", " мощность"), powers[i]);
        if (p == ConsoleUi.NAV_BACK) {
          cancelled = true;
          break;
        }
        powers[i] = p;
      }
      if (cancelled) continue;
      Result r = ctx.reader().setRfPowerByAnt(powers);
      ui.setStatusMessage(r.ok()
          ? L("Per-antenna power set.", "Antenna quvvati sozlandi.", "Мощность по антеннам установлена.")
          : L("SetRfPowerByAnt failed: ", "SetRfPowerByAnt xato: ", "SetRfPowerByAnt ошибка: ") + r.code());
    }
  }

  private static void menuAntennaCheck(ConsoleUi ui, CommandContext ctx) {
    String[] options = {
        L("Enable (1)", "Yoqish (1)", "Вкл (1)"),
        L("Disable (0)", "O'chirish (0)", "Выкл (0)"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Antenna Check", "Antenna tekshiruv", "Проверка антенны"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage(L("Not connected.", "Ulanmagan.", "Не подключено."));
        continue;
      }
      boolean enabled = sel == 0;
      Result r = ctx.reader().setCheckAnt(enabled);
      ui.setStatusMessage(r.ok()
          ? L("Antenna check ", "Antenna tekshiruv ", "Проверка антенны ")
          + (enabled ? L("enabled", "yoqildi", "включена") : L("disabled", "o'chirildi", "выключена")) + "."
          : L("SetCheckAnt failed: ", "SetCheckAnt xato: ", "SetCheckAnt ошибка: ") + r.code());
    }
  }

  private static void menuReturnLoss(ConsoleUi ui, CommandContext ctx) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Return Loss", "Qaytish yo'qotish", "Возвратные потери"),
          new String[]{L("Measure", "O'lchash", "Измерить"), L("Back", "Orqaga", "Назад")}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 1) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage(L("Not connected.", "Ulanmagan.", "Не подключено."));
        continue;
      }
      int ant = selectAntennaIndex(ui, ctx.reader().getAntennaCount(), 0);
      if (ant == ConsoleUi.NAV_BACK) continue;
      Double freqMHz = selectReturnLossFreq(ui);
      if (freqMHz == null) continue;
      int freqKhz = (int) Math.round(freqMHz * 1000.0);
      ReturnLossInfo info = ctx.reader().measureReturnLoss(ant, freqKhz);
      if (!info.result().ok()) {
        ui.setStatusMessage(L("MeasureReturnLoss failed: ", "MeasureReturnLoss xato: ", "MeasureReturnLoss ошибка: ") + info.result().code());
      } else {
        ui.showLines(L("Return Loss", "Qaytish yo'qotish", "Возвратные потери"), List.of(
            L("Antenna: ", "Antenna: ", "Антенна: ") + (ant + 1) + " (" + ant + ")",
            L("Freq: ", "Chastota: ", "Частота: ") + formatMHz(freqMHz),
            L("Loss: ", "Yo'qotish: ", "Потери: ") + info.lossDb() + " dB"
        ));
      }
    }
  }

  private static void menuWritePower(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    String[] options = {
        L("Set (normal)", "Sozlash (oddiy)", "Установить (норм)"),
        L("Set (high)", "Sozlash (yuqori)", "Установить (высок)"),
        L("Get", "Ko'rish", "Получить"),
        L("Back", "Orqaga", "Назад")
    };
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Write Power", "Yozish quvvati", "Мощность записи"), options, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 3) return;
      if (!ctx.reader().isConnected()) {
        ui.setStatusMessage(L("Not connected.", "Ulanmagan.", "Не подключено."));
        continue;
      }
      if (sel == 2) {
        registry.execute(List.of("wpower", "get"), ctx);
        continue;
      }
      int def = 30;
      WritePowerInfo info = ctx.reader().getWritePower();
      if (info.result().ok()) def = info.power();
      int p = selectPowerValue(ui, L("Write Power", "Yozish quvvati", "Мощность записи"), def);
      if (p == ConsoleUi.NAV_BACK) continue;
      registry.execute(List.of("wpower", "set", String.valueOf(p), sel == 1 ? "1" : "0"), ctx);
    }
  }

  private static void menuInfo(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    while (true) {
      updateStatus(ui, ctx.reader(), ctx.erp());
      int sel = ui.selectOption(L("Info", "Ma'lumot", "Инфо"),
          new String[]{L("Reader info", "Reader ma'lumoti", "Инфо ридера"), L("Serial", "Seriya raqam", "Серийный"), L("Back", "Orqaga", "Назад")}, 0);
      if (sel == ConsoleUi.NAV_BACK) return;
      if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
      if (sel == 2) return;
      if (sel == 0) registry.execute(List.of("info"), ctx);
      if (sel == 1) registry.execute(List.of("serial"), ctx);
    }
  }

  private static ShellExit commandShell(ConsoleUi ui, CommandContext ctx, CommandRegistry registry) {
    ui.println(L("Command shell. Type 'menu' to return.",
        "Buyruq oynasi. Qaytish uchun 'menu' yozing.",
        "Командная строка. Для возврата введите 'menu'."));
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
        ui.println(L("Unknown command. Type 'help'.",
            "Noma'lum buyruq. 'help' yozing.",
            "Неизвестная команда. Введите 'help'."));
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

  private static Double askDoubleOrBack(ConsoleUi ui, String label, double def) {
    String line = ui.readLineInMenuOrBack(label + " [" + def + "]: ");
    if (line == null) return null;
    if (line.isBlank()) return def;
    return parseDouble(line, def);
  }

  private static Double selectDistanceMeters(ConsoleUi ui, int profileIdx) {
    double[] distances = profileDistances(profileIdx);
    String[] options = new String[distances.length + 2];
    for (int i = 0; i < distances.length; i++) {
      options[i] = formatDistance(distances[i]);
    }
    options[options.length - 2] = L("Custom", "Maxsus", "Пользовательский");
    options[options.length - 1] = L("Back", "Orqaga", "Назад");
    int def = Math.min(distances.length - 1, distances.length / 2);
    int sel = ui.selectOption(L("Distance (m)", "Masofa (m)", "Дистанция (м)"), options, def);
    if (sel == ConsoleUi.NAV_BACK) return null;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel == options.length - 1) return null;
    if (sel == options.length - 2) {
      Double m = askDoubleOrBack(ui, L("Distance (m)", "Masofa (m)", "Дистанция (м)"), distances[def]);
      if (m == null) return null;
      double min = distances[0];
      double max = distances[distances.length - 1];
      if (m < min) m = min;
      if (m > max) m = max;
      return m;
    }
    if (sel < 0 || sel >= distances.length) return distances[def];
    return distances[sel];
  }

  private static int estimatePowerForProfile(int profileIdx, double meters) {
    double[] d = profileDistances(profileIdx);
    int[] p = profilePowers(profileIdx);
    if (meters <= d[0]) return p[0];
    if (meters >= d[d.length - 1]) return p[p.length - 1];
    for (int i = 1; i < d.length; i++) {
      if (meters <= d[i]) {
        double t = (meters - d[i - 1]) / (d[i] - d[i - 1]);
        double val = p[i - 1] + t * (p[i] - p[i - 1]);
        int power = (int) Math.round(val);
        if (power < 5) power = 5;
        if (power > 33) power = 33;
        return power;
      }
    }
    return p[p.length - 1];
  }

  private static double[] profileDistances(int profileIdx) {
    return switch (profileIdx) {
      case 0 -> new double[]{0.5, 1, 2, 3, 5, 7, 10};
      case 2 -> new double[]{2, 3, 5, 7, 10, 15, 20, 25, 30};
      default -> new double[]{1, 2, 3, 5, 7, 10, 12, 15};
    };
  }

  private static int[] profilePowers(int profileIdx) {
    return switch (profileIdx) {
      case 0 -> new int[]{8, 10, 12, 14, 16, 18, 20};
      case 2 -> new int[]{14, 16, 19, 22, 26, 29, 31, 32, 33};
      default -> new int[]{10, 12, 14, 17, 19, 22, 24, 26};
    };
  }

  private static String formatDistance(double meters) {
    if (Math.abs(meters - Math.round(meters)) < 0.01) {
      return (int) Math.round(meters) + " m";
    }
    return meters + " m";
  }

  private static String askString(ConsoleUi ui, String label) {
    return ui.readLineInMenu(label + ": ");
  }

  private static List<Integer> choosePorts(ConsoleUi ui) {
    String[] options = {
        L("auto (default)", "auto (standart)", "auto (по умолчанию)"),
        L("auto+ (wide)", "auto+ (keng)", "auto+ (широко)"),
        L("27011 only", "faqat 27011", "только 27011"),
        L("27011 + 2022", "27011 + 2022", "27011 + 2022"),
        L("2022 only", "faqat 2022", "только 2022")
    };
    int mode = ui.selectOption(L("Ports", "Portlar", "Порты"), options, 0);
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
      ui.setStatusMessage(L("No LAN prefixes found.", "LAN prefikslar topilmadi.", "LAN префиксы не найдены."));
      return new PrefixChoice(null, true);
    }
    String[] options = new String[prefixes.size() + 1];
    options[0] = L("Auto (all detected)", "Auto (hammasi)", "Авто (все найденные)");
    for (int i = 0; i < prefixes.size(); i++) {
      options[i + 1] = prefixes.get(i);
    }
    int sel = ui.selectOption(L("Subnet", "Subnet", "Подсеть"), options, 0);
    if (sel == ConsoleUi.NAV_BACK) return new PrefixChoice(null, true);
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel <= 0) return new PrefixChoice(null, false);
    return new PrefixChoice(prefixes.get(sel - 1), false);
  }

  private static int selectMem(ConsoleUi ui) {
    int mode = ui.selectOption(L("Mem", "Xotira", "Память"),
        new String[]{L("Password(0)", "Parol(0)", "Пароль(0)"), "EPC(1)", "TID(2)", L("User(3)", "Foydalanuvchi(3)", "Пользователь(3)")}, 1);
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
    labels[labels.length - 1] = L("Custom", "Maxsus", "Пользовательский");
    int sel = ui.selectOption(L("Region", "Mintaqa", "Регион"), labels, 0);
    if (sel == ConsoleUi.NAV_BACK) return null;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel >= 0 && sel < options.length) {
      RegionOption opt = options[sel];
      double[] freqs = buildFreqList(opt.startMhz(), opt.stepMhz(), opt.count());
      String[] items = new String[freqs.length];
      for (int i = 0; i < freqs.length; i++) {
        items[i] = i + ": " + formatMHz(freqs[i]);
      }
      int minIdx = ui.selectOptionPaged(L("MinFreq", "Min chastota", "Мин частота"), items, 0, 12);
      if (minIdx == ConsoleUi.NAV_BACK) return null;
      if (minIdx == ConsoleUi.NAV_FORWARD) minIdx = ui.getLastMenuIndex();
      if (minIdx < 0) minIdx = 0;
      String[] maxItems = new String[items.length + 1];
      maxItems[0] = L("Same as Min (", "Min bilan bir xil (", "Так же как Min (") + minIdx + ")";
      System.arraycopy(items, 0, maxItems, 1, items.length);
      int maxSel = ui.selectOptionPaged(L("MaxFreq", "Max chastota", "Макс частота"), maxItems, minIdx + 1, 12);
      if (maxSel == ConsoleUi.NAV_BACK) return null;
      if (maxSel == ConsoleUi.NAV_FORWARD) maxSel = ui.getLastMenuIndex();
      int maxIdx = maxSel <= 0 ? minIdx : maxSel - 1;
      return new RegionSelection(opt.band(), maxIdx, minIdx);
    }
    int band = askInt(ui, L("Band", "Band", "Диапазон"), 0);
    int max = askInt(ui, L("MaxFreq", "Max chastota", "Макс частота"), 0);
    int min = askInt(ui, L("MinFreq", "Min chastota", "Мин частота"), 0);
    return new RegionSelection(band, max, min);
  }

  private static RegionOption[] regionOptions() {
    return new RegionOption[] {
        new RegionOption(L("Chinese band1", "Xitoy band1", "Китай band1"), 8, 840.125, 0.25, 20),
        new RegionOption(L("US band", "AQSH band", "США band"), 2, 902.75, 0.5, 50),
        new RegionOption(L("Korean band", "Koreya band", "Корея band"), 3, 917.1, 0.2, 32),
        new RegionOption(L("EU band", "Yevropa band", "Европа band"), 4, 865.1, 0.2, 15),
        new RegionOption(L("Chinese band2", "Xitoy band2", "Китай band2"), 1, 920.125, 0.25, 20),
        new RegionOption(L("US band3", "AQSH band3", "США band3"), 12, 902.0, 0.5, 53),
        new RegionOption(L("ALL band", "HAMMA band", "ВСЕ band"), 0, 840.0, 2.0, 61)
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

  private static InventoryParams promptInventoryParams(ConsoleUi ui, InventoryParams current, int antennaCount) {
    int addrDefault = current.address() == 255 ? 0 : 1;
    int addrSel = ui.selectOption(L("Address", "Manzil", "Адрес"),
        new String[]{L("Broadcast (255)", "Broadcast (255)", "Broadcast (255)"), L("Custom", "Maxsus", "Пользовательский")}, addrDefault);
    int address = addrSel == 0 ? 255 : askInt(ui, L("Address (0-255)", "Manzil (0-255)", "Адрес (0-255)"), current.address());
    int session = askInt(ui, L("Session", "Session", "Сессия"), current.session());
    int q = askInt(ui, L("QValue", "Q qiymat", "Q значение"), current.qValue());
    int scanTime = askInt(ui, L("ScanTime", "Skan vaqti", "Время скана"), current.scanTime());
    int antenna = selectInventoryAntenna(ui, antennaCount, current.antenna());
    int readType = askInt(ui, L("ReadType", "O'qish turi", "Тип чтения"), current.readType());
    int readMem = askInt(ui, L("ReadMem", "O'qish xotirasi", "Память чтения"), current.readMem());
    int readPtr = askInt(ui, L("ReadPtr", "O'qish manzili", "Адрес чтения"), current.readPtr());
    int readLen = askInt(ui, L("ReadLength", "O'qish uzunligi", "Длина чтения"), current.readLength());
    int tidPtr = askInt(ui, L("TID Ptr", "TID manzili", "TID адрес"), current.tidPtr());
    int tidLen = askInt(ui, L("TID Len", "TID uzunligi", "TID длина"), current.tidLen());
    String pwd = askString(ui, L("Password", "Parol", "Пароль"), current.password() == null ? "" : current.password());
    return new InventoryParams(Result.success(), address, tidPtr, tidLen, session, q, scanTime, antenna,
        readType, readMem, readPtr, readLen, pwd);
  }

  private static int selectInventoryAntenna(ConsoleUi ui, int antennaCount, int currentAntenna) {
    int n = antennaCount > 0 ? antennaCount : 4;
    int normalized = normalizeInventoryAntenna(currentAntenna, n);
    int def = 0;
    if (normalized >= 0x80 && normalized < 0x80 + n) {
      def = normalized - 0x80;
    } else if (normalized >= 1 && normalized <= n) {
      def = normalized - 1;
    } else if (normalized >= 0 && normalized < n) {
      def = normalized;
    }
    String[] items = new String[n + 1];
    for (int i = 0; i < n; i++) {
      items[i] = L("Ant", "Ant", "Ант") + " " + (i + 1) + " (" + (0x80 + i) + ")";
    }
    items[n] = L("Custom (raw)", "Maxsus (son)", "Пользовательский (число)");
    int sel = ui.selectOption(L("Inventory antenna", "Inventar antenna", "Антенна инвентаря"), items, Math.max(0, Math.min(def, n - 1)));
    if (sel == ConsoleUi.NAV_BACK) return currentAntenna;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel >= 0 && sel < n) return 0x80 + sel;
    int raw = askInt(ui, L("Antenna (raw)", "Antenna (son)", "Антенна (число)"), currentAntenna);
    return normalizeInventoryAntenna(raw, n);
  }

  private static int normalizeInventoryAntenna(int antenna, int antennaCount) {
    int n = antennaCount > 0 ? antennaCount : 4;
    // Already in vendor format (Ant1..AntN => 0x80..)
    if (antenna >= 0x80 && antenna < 0x80 + n) return antenna;
    // Accept 1..N (human) and map to 0x80..
    if (antenna >= 1 && antenna <= n) return 0x80 + (antenna - 1);
    // Accept 0..N-1 (zero-based) and map to 0x80..
    if (antenna >= 0 && antenna < n) return 0x80 + antenna;
    return antenna;
  }

  private static void ensureInventoryAntennaNormalized(CommandContext ctx) {
    try {
      InventoryParams cur = ctx.reader().getInventoryParams();
      if (!cur.result().ok()) return;
      int n = ctx.reader().getAntennaCount();
      int normalized = normalizeInventoryAntenna(cur.antenna(), n);
      if (normalized == cur.antenna()) return;
      InventoryParams fixed = new InventoryParams(Result.success(), cur.address(), cur.tidPtr(), cur.tidLen(), cur.session(), cur.qValue(),
          cur.scanTime(), normalized, cur.readType(), cur.readMem(), cur.readPtr(), cur.readLength(), cur.password());
      Result r = ctx.reader().setInventoryParams(fixed);
      if (r.ok()) {
        ctx.ui().setStatusMessage(L("Inventory antenna normalized: ", "Inventar antenna moslandi: ", "Антенна нормализована: ") + normalized);
      }
    } catch (Throwable ignored) {
    }
  }

  private static Result tryStartInventoryOnAnyAntenna(CommandContext ctx) {
    InventoryParams cur = ctx.reader().getInventoryParams();
    if (!cur.result().ok()) return Result.fail(cur.result().code());
    int n = ctx.reader().getAntennaCount();
    int original = cur.antenna();
    int originalNorm = normalizeInventoryAntenna(original, n);

    // Try each antenna port in vendor-expected encoding.
    for (int i = 0; i < Math.max(1, n); i++) {
      int ant = 0x80 + i;
      if (ant == originalNorm) continue;
      InventoryParams p = new InventoryParams(Result.success(), cur.address(), cur.tidPtr(), cur.tidLen(), cur.session(), cur.qValue(),
          cur.scanTime(), ant, cur.readType(), cur.readMem(), cur.readPtr(), cur.readLength(), cur.password());
      Result set = ctx.reader().setInventoryParams(p);
      if (!set.ok()) continue;
      Result start = ctx.reader().startInventory();
      if (start.ok()) {
        ctx.ui().setStatusMessage(L("Inventory antenna auto-selected: ", "Inventar antenna avto-tanlandi: ", "Антенна выбрана: ") + ant);
        return start;
      }
    }

    // Restore original antenna (best-effort) if we tried others.
    InventoryParams restore = new InventoryParams(Result.success(), cur.address(), cur.tidPtr(), cur.tidLen(), cur.session(), cur.qValue(),
        cur.scanTime(), originalNorm, cur.readType(), cur.readMem(), cur.readPtr(), cur.readLength(), cur.password());
    try { ctx.reader().setInventoryParams(restore); } catch (Throwable ignored) {}
    return Result.fail(255);
  }

  private static void printInventoryParams(ConsoleUi ui, InventoryParams p) {
    if (!p.result().ok()) {
      if (p.result().code() == 0x36) {
        ui.showLines(L("Not connected", "Ulanmagan", "Не подключено"),
            List.of(L("Please connect first.", "Avval ulang.", "Сначала подключитесь.")));
      } else {
        ui.showLines(L("GetInventoryParameter failed", "GetInventoryParameter xato", "GetInventoryParameter ошибка"),
            List.of("code=" + p.result().code()));
      }
      return;
    }
    ui.showLines(L("Inventory Params", "Inventar parametrlari", "Параметры инвентаря"), List.of(
        L("address", "manzil", "адрес") + "=" + p.address() + " " + L("session", "session", "сессия") + "=" + p.session()
            + " q=" + p.qValue() + " " + L("scanTime", "scanTime", "scanTime") + "=" + p.scanTime()
            + " " + L("antenna", "antenna", "антенна") + "=" + p.antenna(),
        L("readType", "readType", "readType") + "=" + p.readType() + " " + L("readMem", "readMem", "readMem")
            + "=" + p.readMem() + " " + L("readPtr", "readPtr", "readPtr") + "=" + p.readPtr()
            + " " + L("readLen", "readLen", "readLen") + "=" + p.readLength(),
        "tidPtr=" + p.tidPtr() + " tidLen=" + p.tidLen() + " " + L("password", "parol", "пароль") + "=" + p.password()
    ));
  }

  private static void pause(ConsoleUi ui) {
    ui.readLineInMenu(L("Press Enter to continue...", "Davom etish uchun Enter bosing...", "Нажмите Enter чтобы продолжить..."));
  }

  private record PrefixChoice(String prefix, boolean cancelled) {
  }

  private static int selectReaderType(ConsoleUi ui, int def) {
    int idx = def == 16 ? 1 : 0;
    int sel = ui.selectOption(L("ReaderType", "Reader turi", "Тип ридера"), new String[]{"4", "16"}, idx);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    return sel == 1 ? 16 : 4;
  }

  private static int selectLog(ConsoleUi ui, int def) {
    int idx = def == 1 ? 1 : 0;
    int sel = ui.selectOption(L("Log", "Log", "Лог"),
        new String[]{L("0 (off)", "0 (o‘chiq)", "0 (выкл)"), L("1 (on)", "1 (yoqiq)", "1 (вкл)")}, idx);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    return sel == 1 ? 1 : 0;
  }

  private static int selectAntennaIndex(ConsoleUi ui, int count, int def) {
    int n = count > 0 ? count : 4;
    String[] items = new String[n];
    for (int i = 0; i < n; i++) {
      items[i] = L("Ant", "Ant", "Ант") + " " + (i + 1) + " (" + i + ")";
    }
    int sel = ui.selectOptionPaged(L("Antenna", "Antenna", "Антенна"), items, Math.max(0, Math.min(def, n - 1)), 10);
    if (sel == ConsoleUi.NAV_BACK) return ConsoleUi.NAV_BACK;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    return sel;
  }

  private static Double selectReturnLossFreq(ConsoleUi ui) {
    RegionOption[] options = regionOptions();
    String[] labels = new String[options.length + 1];
    for (int i = 0; i < options.length; i++) labels[i] = options[i].label();
    labels[labels.length - 1] = L("Custom (MHz)", "Maxsus (MHz)", "Пользовательский (MHz)");
    int sel = ui.selectOption(L("Return Loss Freq", "Qaytish yo'qotish chastotasi", "Частота потерь"), labels, 0);
    if (sel == ConsoleUi.NAV_BACK) return null;
    if (sel == ConsoleUi.NAV_FORWARD) sel = ui.getLastMenuIndex();
    if (sel >= 0 && sel < options.length) {
      RegionOption opt = options[sel];
      double[] freqs = buildFreqList(opt.startMhz(), opt.stepMhz(), opt.count());
      String[] items = new String[freqs.length];
      for (int i = 0; i < freqs.length; i++) {
        items[i] = i + ": " + formatMHz(freqs[i]);
      }
      int idx = ui.selectOptionPaged(L("Frequency", "Chastota", "Частота"), items, freqs.length / 2, 12);
      if (idx == ConsoleUi.NAV_BACK) return null;
      if (idx == ConsoleUi.NAV_FORWARD) idx = ui.getLastMenuIndex();
      if (idx < 0) idx = 0;
      return freqs[idx];
    }
    String line = ui.readLineInMenu(L("Freq MHz (e.g. 915.25): ", "Chastota MHz (masalan 915.25): ", "Частота MHz (например 915.25): "));
    double freq = parseDouble(line, -1);
    if (freq <= 0) {
      ui.setStatusMessage(L("Invalid frequency.", "Chastota noto'g'ri.", "Неверная частота."));
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
    String readerState = reader.isConnected()
        ? L("UHF: connected", "UHF: ulangan", "UHF: подключено")
        : L("UHF: disconnected", "UHF: uzilgan", "UHF: отключено");
    String erpState = erpStatus(erp, ERP_AGENT);
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

  private static boolean isPortOpen(String host, int port, int timeoutMs) {
    if (host == null || host.isBlank() || port <= 0) return false;
    try (Socket s = new Socket()) {
      s.connect(new java.net.InetSocketAddress(host, port), Math.max(50, timeoutMs));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void rememberConnection(String host, int port, int readerType, int log) {
    if (host == null || host.isBlank()) return;
    Properties p = new Properties();
    p.setProperty("host", host);
    p.setProperty("port", String.valueOf(port));
    p.setProperty("readerType", String.valueOf(readerType));
    p.setProperty("log", String.valueOf(log));
    try {
      Path file = lastConnectionPath();
      Files.createDirectories(file.getParent());
      try (var out = Files.newOutputStream(file)) {
        p.store(out, "Last connection");
      }
    } catch (Exception ignored) {
    }
  }

  private static LastConnection loadLastConnection() {
    Path file = lastConnectionPath();
    if (!Files.exists(file)) return null;
    Properties p = new Properties();
    try (var in = Files.newInputStream(file)) {
      p.load(in);
      String host = p.getProperty("host", "").trim();
      int port = parseInt(p.getProperty("port"), 0);
      int readerType = parseInt(p.getProperty("readerType"), 4);
      int log = parseInt(p.getProperty("log"), 0);
      if (host.isBlank() || port <= 0) return null;
      return new LastConnection(host, port, readerType, log);
    } catch (Exception e) {
      return null;
    }
  }

  private static Path lastConnectionPath() {
    return Path.of("UhfTuiLinux", "last_connection.properties");
  }

  private record LastConnection(String host, int port, int readerType, int log) {
  }

  private static Lang loadLang() {
    Path file = uiConfigPath();
    if (!Files.exists(file)) return Lang.EN;
    Properties p = new Properties();
    try (var in = Files.newInputStream(file)) {
      p.load(in);
      String code = p.getProperty("lang", "en").trim();
      return Lang.from(code);
    } catch (Exception e) {
      return Lang.EN;
    }
  }

  private static void saveLang(Lang lang) {
    try {
      Path file = uiConfigPath();
      Files.createDirectories(file.getParent());
      Properties p = new Properties();
      p.setProperty("lang", lang.code);
      try (var out = Files.newOutputStream(file)) {
        p.store(out, "UI Config");
      }
    } catch (Exception ignored) {
    }
  }

  private static Path uiConfigPath() {
    return Path.of("UhfTuiLinux", "ui.properties");
  }

  private static String L(String en, String uz, String ru) {
    return switch (LANG) {
      case UZ -> uz;
      case RU -> ru;
      default -> en;
    };
  }

  private enum Lang {
    EN("en", "English"),
    UZ("uz", "O‘zbek"),
    RU("ru", "Русский");

    final String code;
    final String label;

    Lang(String code, String label) {
      this.code = code;
      this.label = label;
    }

    static Lang from(String code) {
      if (code == null) return EN;
      String c = code.trim().toLowerCase();
      for (Lang l : values()) {
        if (l.code.equals(c)) return l;
      }
      return EN;
    }
  }

  private static String erpStatus(ErpPusher erp, ErpAgentRegistrar agent) {
    if (erp == null) return "";
    if (erp.config() == null || erp.config().baseUrl == null || erp.config().baseUrl.isBlank()) {
      return L("ERP: inactive", "ERP: o‘chiq", "ERP: неактивно");
    }
    long now = System.currentTimeMillis();
    long ok = erp.lastOkAt();
    long err = erp.lastErrAt();
    long aok = agent == null ? 0 : agent.lastOkAt();
    long aerr = agent == null ? 0 : agent.lastErrAt();
    if (aok > 0 && now - aok <= 60000) return L("ERP: online", "ERP: online", "ERP: онлайн");
    if (aerr > aok && now - aerr <= 60000) return L("ERP: agent-error", "ERP: agent xato", "ERP: ошибка агента");
    if (ok > 0 && err <= ok && now - ok <= 60000) return L("ERP: active", "ERP: faol", "ERP: активен");
    if (!erp.isEnabled()) return L("ERP: configured", "ERP: sozlangan", "ERP: настроено");
    if (err > ok) return L("ERP: error", "ERP: xato", "ERP: ошибка");
    if (ok == 0) return L("ERP: waiting", "ERP: kutilmoqda", "ERP: ожидание");
    long age = now - ok;
    if (age <= 60000) return L("ERP: connected", "ERP: ulangan", "ERP: подключено");
    return L("ERP: stale", "ERP: eskirgan", "ERP: устарело");
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
      return L("Tags", "Taglar", "Теги") + ": " + total + " | " + L("Rate", "Tezlik", "Скорость") + ": " + rate + "/s";
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
    SETTINGS
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
    if (ERP_AGENT != null) ERP_AGENT.applyConfig(cfg);
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
