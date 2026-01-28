package uhf.tui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.List;
import uhf.core.TagRead;

public final class ConsoleUi {
  private final Object lock = new Object();
  private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

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
    int lines = 1 + options.length;
    if (!first) {
      moveCursorUp(lines);
    }
    System.out.print("\033[2K" + label + " (↑/↓ + Enter)\n");
    for (int i = 0; i < options.length; i++) {
      String prefix = (i == idx) ? "> " : "  ";
      System.out.print("\033[2K" + prefix + options[i] + "\n");
    }
    System.out.flush();
  }

  private void moveCursorUp(int lines) {
    if (lines <= 0) return;
    System.out.print("\033[" + lines + "A\r");
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
