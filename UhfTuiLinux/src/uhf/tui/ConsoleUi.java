package uhf.tui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.List;
import uhf.core.TagRead;

public final class ConsoleUi {
  private final Object lock = new Object();
  private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
  private int lastMenuLines = 0;
  private static final String ANSI_RESET = "\033[0m";
  private static final String ANSI_BOLD = "\033[1m";
  private static final String ANSI_DIM = "\033[2m";
  private static final String ANSI_REVERSE = "\033[7m";

  public void println(String s) {
    synchronized (lock) {
      System.out.println(s);
    }
  }

  public void prompt() {
    System.out.print("uhf> ");
    System.out.flush();
  }

  public String readLine() {
    try {
      return reader.readLine();
    } catch (Throwable t) {
      return null;
    }
  }

  public String readLine(String prompt) {
    try {
      if (prompt != null && !prompt.isEmpty()) {
        System.out.print(prompt);
        System.out.flush();
      }
      return reader.readLine();
    } catch (Throwable t) {
      return null;
    }
  }

  public int selectOption(String label, String[] options, int defaultIndex) {
    if (!setTerminalRaw(true)) {
      return selectOptionLine(label, options, defaultIndex);
    }
    int idx = Math.max(0, Math.min(defaultIndex, options.length - 1));
    try {
      renderSwipeMenu(label, options, idx, true);
      while (true) {
        int ch = System.in.read();
        if (ch == -1) return idx;
        if (ch == '\r' || ch == '\n') {
          return idx;
        }
        if (ch == 27) { // ESC
          int ch1 = System.in.read();
          if (ch1 == -1) return idx;
          if (ch1 == '[') {
            int ch2 = System.in.read();
            if (ch2 == 'A') {
              idx = (idx - 1 + options.length) % options.length;
              renderSwipeMenu(label, options, idx, false);
              continue;
            }
            if (ch2 == 'B') {
              idx = (idx + 1) % options.length;
              renderSwipeMenu(label, options, idx, false);
              continue;
            }
          } else {
            return idx;
          }
        }
        if (ch == 'j' || ch == 'J') {
          idx = (idx + 1) % options.length;
          renderSwipeMenu(label, options, idx, false);
          continue;
        }
        if (ch == 'k' || ch == 'K') {
          idx = (idx - 1 + options.length) % options.length;
          renderSwipeMenu(label, options, idx, false);
          continue;
        }
        if (ch >= '1' && ch <= '9') {
          int n = (ch - '1');
          if (n >= 0 && n < options.length) {
            idx = n;
            renderSwipeMenu(label, options, idx, false);
          }
        }
      }
    } catch (Throwable t) {
      return selectOptionLine(label, options, defaultIndex);
    } finally {
      setTerminalRaw(false);
    }
  }

  public void printTag(TagRead tag) {
    synchronized (lock) {
      String t = LocalTime.now().toString();
      System.out.println(t + " EPC=" + tag.epcId() + " RSSI=" + tag.rssi() + " ANT=" + tag.antId());
      prompt();
    }
  }

  public boolean confirm(String message) {
    String line = readLine(message + " Type YES to continue: ");
    if (line == null) return false;
    return "YES".equalsIgnoreCase(line.trim());
  }

  private void renderSwipeMenu(String label, String[] options, int idx, boolean first) {
    MenuStyle style = style();
    String hint = style.fancy ? "↑/↓ move · Enter select · Esc back" : "Up/Down move, Enter select, Esc back";
    int width = Math.max(label.length(), hint.length());
    for (String opt : options) {
      int len = (style.fancy ? 2 : 2) + opt.length();
      if (len > width) width = len;
    }
    String h = style.unicode ? "─" : "-";
    String v = style.unicode ? "│" : "|";
    String tl = style.unicode ? "┌" : "+";
    String tr = style.unicode ? "┐" : "+";
    String bl = style.unicode ? "└" : "+";
    String br = style.unicode ? "┘" : "+";
    String jm = style.unicode ? "├" : "+";
    String jmr = style.unicode ? "┤" : "+";

    if (!first && lastMenuLines > 0) {
      moveCursorUp(lastMenuLines);
    }

    System.out.print(clearLine(tl + repeat(h, width + 2) + tr) + "\n");
    System.out.print(clearLine(v + " " + applyStyle(padRight(label, width), style.bold) + " " + v) + "\n");
    System.out.print(clearLine(jm + repeat(h, width + 2) + jmr) + "\n");
    for (int i = 0; i < options.length; i++) {
      String prefix = (i == idx) ? (style.unicode ? "▶ " : "> ") : "  ";
      String raw = padRight(prefix + options[i], width);
      String line = (i == idx && style.ansi) ? ANSI_REVERSE + raw + ANSI_RESET : raw;
      System.out.print(clearLine(v + " " + line + " " + v) + "\n");
    }
    System.out.print(clearLine(jm + repeat(h, width + 2) + jmr) + "\n");
    System.out.print(clearLine(v + " " + applyStyle(padRight(hint, width), style.dim) + " " + v) + "\n");
    System.out.print(clearLine(bl + repeat(h, width + 2) + br) + "\n");
    System.out.flush();
    lastMenuLines = 6 + options.length;
  }

  private void moveCursorUp(int lines) {
    if (lines <= 0) return;
    System.out.print("\033[" + lines + "A\r");
  }

  private String clearLine(String s) {
    return "\033[2K" + s;
  }

  private String applyStyle(String s, String style) {
    if (style.isEmpty()) return s;
    return style + s + ANSI_RESET;
  }

  private String padRight(String s, int width) {
    if (s.length() >= width) return s;
    StringBuilder sb = new StringBuilder(width);
    sb.append(s);
    while (sb.length() < width) sb.append(' ');
    return sb.toString();
  }

  private String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(n * s.length());
    for (int i = 0; i < n; i++) sb.append(s);
    return sb.toString();
  }

  private MenuStyle style() {
    boolean ansi = supportsAnsi();
    boolean unicode = ansi;
    String bold = ansi ? ANSI_BOLD : "";
    String dim = ansi ? ANSI_DIM : "";
    return new MenuStyle(ansi, unicode, bold, dim, ansi);
  }

  private boolean supportsAnsi() {
    String term = System.getenv("TERM");
    if (term == null || term.equalsIgnoreCase("dumb")) return false;
    if (System.getenv("NO_COLOR") != null) return false;
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win")) return false;
    return true;
  }

  private record MenuStyle(boolean ansi, boolean unicode, String bold, String dim, boolean fancy) {
  }

  private int selectOptionLine(String label, String[] options, int defaultIndex) {
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
      String line = reader.readLine();
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

  private boolean setTerminalRaw(boolean enable) {
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
}
