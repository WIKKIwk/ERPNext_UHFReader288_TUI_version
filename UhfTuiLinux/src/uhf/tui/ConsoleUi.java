package uhf.tui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.List;
import uhf.core.TagRead;

public final class ConsoleUi {
  public static final int NAV_BACK = -1;
  public static final int NAV_FORWARD = -2;
  private final Object lock = new Object();
  private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
  private int lastMenuLines = 0;
  private boolean menuMode = false;
  private String lastMenuLabel;
  private String[] lastMenuOptions;
  private int lastMenuIndex = 0;
  private int lastMenuWidth = 0;
  private int cursorFromMenuBottom = 0;
  private int lastRenderedLines = 0;
  private int lastRenderedWidth = 0;
  private String statusBase;
  private String statusMessage;
  private String inputPrompt;
  private int cachedCols = 80;
  private long cachedColsAt = 0L;
  private static final String ANSI_RESET = "\033[0m";
  private static final String ANSI_BOLD = "\033[1m";
  private static final String ANSI_DIM = "\033[2m";
  private static final String ANSI_REVERSE = "\033[7m";

  public void println(String s) {
    if (menuMode && supportsAnsi() && lastMenuOptions != null) {
      setStatusMessage(s);
      return;
    }
    resetMenuState();
    synchronized (lock) {
      System.out.println(s);
    }
  }

  public void prompt() {
    resetMenuState();
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
        resetMenuState();
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
      menuMode = false;
      return selectOptionLine(label, options, defaultIndex);
    }
    menuMode = true;
    int idx = Math.max(0, Math.min(defaultIndex, options.length - 1));
    try {
      resetCursorOffset();
      renderSwipeMenu(label, options, idx, lastMenuLines == 0);
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
            if (ch2 == 'D') { // left
              return NAV_BACK;
            }
            if (ch2 == 'C') { // right
              return NAV_FORWARD;
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

  public int selectOptionPaged(String label, String[] options, int defaultIndex, int pageSize) {
    if (options == null || options.length == 0) return -1;
    int size = Math.max(4, pageSize);
    int idx = Math.max(0, Math.min(defaultIndex, options.length - 1));
    if (!setTerminalRaw(true)) {
      return selectOption(label, options, defaultIndex);
    }
    menuMode = true;
    int start = Math.max(0, Math.min(idx, Math.max(0, options.length - size)));
    try {
      while (true) {
        int end = Math.min(options.length, start + size);
        String pageLabel = label + " (" + (start + 1) + "-" + end + "/" + options.length + ")";
        String[] pageOptions = new String[end - start];
        for (int i = start; i < end; i++) {
          pageOptions[i - start] = options[i];
        }
        int local = idx - start;
        resetCursorOffset();
        renderSwipeMenu(pageLabel, pageOptions, Math.max(0, Math.min(local, pageOptions.length - 1)), lastMenuLines == 0);
        int ch = System.in.read();
        if (ch == -1) return idx;
        if (ch == '\r' || ch == '\n') return idx;
        if (ch == 27) { // ESC
          int ch1 = System.in.read();
          if (ch1 == -1) return idx;
          if (ch1 == '[') {
            int ch2 = System.in.read();
            if (ch2 == 'A') { // up
              idx = (idx - 1 + options.length) % options.length;
              continue;
            } else if (ch2 == 'B') { // down
              idx = (idx + 1) % options.length;
              continue;
            } else if (ch2 == 'D') { // left
              return NAV_BACK;
            } else if (ch2 == 'C') { // right
              return NAV_FORWARD;
            } else {
              continue;
            }
          } else {
            return idx;
          }
        } else if (ch == 'j' || ch == 'J') {
          idx = (idx + 1) % options.length;
        } else if (ch == 'k' || ch == 'K') {
          idx = (idx - 1 + options.length) % options.length;
        } else if (ch >= '1' && ch <= '9') {
          int n = (ch - '1');
          int candidate = start + n;
          if (candidate < options.length && n < size) idx = candidate;
        }
        if (idx < start) start = idx;
        if (idx >= start + size) start = idx - size + 1;
        if (start < 0) start = 0;
        int maxStart = Math.max(0, options.length - size);
        if (start > maxStart) start = maxStart;
      }
    } catch (Throwable t) {
      return selectOption(label, options, defaultIndex);
    } finally {
      setTerminalRaw(false);
    }
  }

  public int getLastMenuIndex() {
    return lastMenuIndex;
  }

  public void printTag(TagRead tag) {
    resetMenuState();
    synchronized (lock) {
      String t = LocalTime.now().toString();
      System.out.println(t + " EPC=" + tag.epcId() + " RSSI=" + tag.rssi() + " ANT=" + tag.antId());
      prompt();
    }
  }

  public boolean confirm(String message) {
    String line = readLineInMenu(message + " Type YES to continue: ");
    if (line == null) return false;
    return "YES".equalsIgnoreCase(line.trim());
  }

  public String readLineInMenu(String prompt) {
    if (!menuMode || !supportsAnsi() || lastMenuOptions == null) {
      return readLine(prompt);
    }
    if (!setTerminalRaw(true)) {
      return readLine(prompt);
    }
    inputPrompt = prompt == null ? "" : prompt;
    StringBuilder buf = new StringBuilder();
    try {
      renderSwipeMenu(lastMenuLabel, lastMenuOptions, lastMenuIndex, false);
      renderInputLine(buf.toString());
      while (true) {
        int ch = System.in.read();
        if (ch == -1) break;
        if (ch == '\r' || ch == '\n') break;
        if (ch == 27) { // ESC or arrows
          int ch1 = System.in.read();
          if (ch1 == '[') {
            System.in.read();
            continue;
          }
          break;
        }
        if (ch == 127 || ch == 8) {
          if (!buf.isEmpty()) buf.deleteCharAt(buf.length() - 1);
          renderInputLine(buf.toString());
          continue;
        }
        if (ch >= 32) {
          buf.append((char) ch);
          renderInputLine(buf.toString());
        }
      }
    } catch (Throwable ignored) {
    } finally {
      setTerminalRaw(false);
      inputPrompt = null;
      resetCursorOffset();
      renderSwipeMenu(lastMenuLabel, lastMenuOptions, lastMenuIndex, false);
    }
    return buf.toString();
  }

  public void showLines(String title, List<String> lines) {
    if (!menuMode || !supportsAnsi() || lastMenuOptions == null) {
      println(title);
      if (lines != null) {
        for (String l : lines) println(l);
      }
      return;
    }
    renderMessageBox(title, lines, "Press Enter to return");
    waitForEnter();
    renderSwipeMenu(lastMenuLabel, lastMenuOptions, lastMenuIndex, false);
  }

  public void setStatus(String message) {
    setStatusMessage(message);
  }

  public void setStatusBase(String message) {
    statusBase = message == null ? "" : message;
    if (menuMode && supportsAnsi() && lastMenuOptions != null) {
      renderSwipeMenu(lastMenuLabel, lastMenuOptions, lastMenuIndex, false);
    }
  }

  public void setStatusMessage(String message) {
    statusMessage = message == null ? "" : message;
    if (menuMode && supportsAnsi() && lastMenuOptions != null) {
      renderSwipeMenu(lastMenuLabel, lastMenuOptions, lastMenuIndex, false);
    }
  }

  public void exitMenuMode() {
    menuMode = false;
    resetMenuState();
  }

  private void renderSwipeMenu(String label, String[] options, int idx, boolean first) {
    MenuStyle style = style();
    String hint = style.fancy ? "↑/↓ move · Enter select · Esc back" : "Up/Down move, Enter select, Esc back";
    String status = combineStatus();
    String input = inputPrompt == null ? "" : inputPrompt;
    int width = Math.max(label.length(), hint.length());
    if (!status.isEmpty()) width = Math.max(width, status.length());
    if (!input.isEmpty()) width = Math.max(width, input.length());
    for (String opt : options) {
      int len = (style.fancy ? 2 : 2) + opt.length();
      if (len > width) width = len;
    }
    int minWidth = minContentWidth();
    if (minWidth > 0 && width < minWidth) width = minWidth;
    int maxWidth = maxContentWidth();
    if (maxWidth > 0 && width > maxWidth) width = maxWidth;
    String h = style.unicode ? "─" : "-";
    String v = style.unicode ? "│" : "|";
    String tl = style.unicode ? "┌" : "+";
    String tr = style.unicode ? "┐" : "+";
    String bl = style.unicode ? "└" : "+";
    String br = style.unicode ? "┘" : "+";
    String jm = style.unicode ? "├" : "+";
    String jmr = style.unicode ? "┤" : "+";

    int newLines = 8 + options.length;
    if (!first && lastMenuLines > 0) {
      int moveUp = lastMenuLines - cursorFromMenuBottom;
      if (moveUp > 0) moveCursorUp(moveUp);
      if (newLines < lastRenderedLines || width < lastRenderedWidth) {
        clearBelow();
      }
    }

    lastMenuLabel = label;
    lastMenuOptions = options;
    lastMenuIndex = idx;
    lastMenuWidth = width;

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
    System.out.print(clearLine(v + " " + applyStyle(padRight(status, width), style.dim) + " " + v) + "\n");
    System.out.print(clearLine(v + " " + padRight(input, width) + " " + v) + "\n");
    System.out.print(clearLine(v + " " + applyStyle(padRight(hint, width), style.dim) + " " + v) + "\n");
    System.out.print(clearLine(bl + repeat(h, width + 2) + br) + "\n");
    System.out.flush();
    lastMenuLines = newLines;
    lastRenderedLines = newLines;
    lastRenderedWidth = width;
    cursorFromMenuBottom = 0;
    System.out.print("\033[2K");
    System.out.flush();
  }

  private void moveCursorUp(int lines) {
    if (lines <= 0) return;
    System.out.print("\033[" + lines + "A\r");
  }

  private void resetMenuState() {
    lastMenuLines = 0;
    lastMenuWidth = 0;
    cursorFromMenuBottom = 0;
    lastRenderedLines = 0;
    lastRenderedWidth = 0;
    statusBase = null;
    statusMessage = null;
    inputPrompt = null;
    lastMenuLabel = null;
    lastMenuOptions = null;
  }

  private String clearLine(String s) {
    return "\033[2K" + s;
  }

  private String applyStyle(String s, String style) {
    if (style.isEmpty()) return s;
    return style + s + ANSI_RESET;
  }

  private String padRight(String s, int width) {
    if (s == null) s = "";
    if (s.length() > width) s = s.substring(0, width);
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

  private void resetCursorOffset() {
    cursorFromMenuBottom = 0;
  }

  private void clearBelow() {
    System.out.print("\033[J");
  }

  private int findBackIndex(String[] options) {
    if (options == null) return -1;
    for (int i = 0; i < options.length; i++) {
      String v = options[i];
      if (v == null) continue;
      String s = v.trim().toLowerCase();
      if (s.equals("back") || s.equals("return") || s.equals("exit") || s.equals("quit")) {
        return i;
      }
    }
    return -1;
  }

  private String combineStatus() {
    String base = statusBase == null ? "" : statusBase;
    String msg = statusMessage == null ? "" : statusMessage;
    if (base.isEmpty()) return msg;
    if (msg.isEmpty()) return base;
    return base + " | " + msg;
  }

  private int maxContentWidth() {
    int cols = terminalCols();
    if (cols <= 0) return 0;
    int max = cols - 4;
    return Math.max(20, max);
  }

  private int minContentWidth() {
    int cols = terminalCols();
    if (cols <= 0) return 0;
    int min = Math.max(50, cols * 70 / 100);
    int max = maxContentWidth();
    if (max > 0 && min > max) return max;
    return min;
  }

  private int terminalCols() {
    long now = System.currentTimeMillis();
    if (now - cachedColsAt < 1000 && cachedCols > 0) {
      return cachedCols;
    }
    try {
      Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty").start();
      byte[] data = p.getInputStream().readAllBytes();
      p.waitFor();
      String out = new String(data).trim();
      String[] parts = out.split("\\s+");
      if (parts.length >= 2) {
        cachedCols = Integer.parseInt(parts[1]);
        cachedColsAt = now;
        return cachedCols;
      }
    } catch (Throwable ignored) {
    }
    cachedCols = 80;
    cachedColsAt = now;
    return cachedCols;
  }

  private void renderInputLine(String value) {
    if (lastMenuWidth <= 0) return;
    String prompt = inputPrompt == null ? "" : inputPrompt;
    String full = prompt + value;
    String visible = full;
    if (visible.length() > lastMenuWidth) {
      visible = visible.substring(visible.length() - lastMenuWidth);
    }
    moveCursorUp(3);
    cursorFromMenuBottom = 3;
    System.out.print("\r");
    System.out.print("\033[2K");
    System.out.print("│ " + padRight(visible, lastMenuWidth) + " │");
    int cursorCol = 2 + Math.min(full.length(), lastMenuWidth);
    System.out.print("\r");
    System.out.print("\033[" + cursorCol + "C");
    System.out.flush();
  }

  private void renderMessageBox(String title, List<String> lines, String footer) {
    MenuStyle style = style();
    String h = style.unicode ? "─" : "-";
    String v = style.unicode ? "│" : "|";
    String tl = style.unicode ? "┌" : "+";
    String tr = style.unicode ? "┐" : "+";
    String bl = style.unicode ? "└" : "+";
    String br = style.unicode ? "┘" : "+";
    String jm = style.unicode ? "├" : "+";
    String jmr = style.unicode ? "┤" : "+";

    int width = title == null ? 0 : title.length();
    if (lines != null) {
      for (String l : lines) {
        if (l != null && l.length() > width) width = l.length();
      }
    }
    if (footer != null && footer.length() > width) width = footer.length();
    int maxWidth = maxContentWidth();
    if (maxWidth > 0 && width > maxWidth) width = maxWidth;

    if (lastMenuLines > 0) {
      int moveUp = lastMenuLines - cursorFromMenuBottom;
      if (moveUp > 0) moveCursorUp(moveUp);
      clearBelow();
    }
    System.out.print(clearLine(tl + repeat(h, width + 2) + tr) + "\n");
    System.out.print(clearLine(v + " " + applyStyle(padRight(title == null ? "" : title, width), style.bold) + " " + v) + "\n");
    System.out.print(clearLine(jm + repeat(h, width + 2) + jmr) + "\n");
    if (lines != null) {
      for (String l : lines) {
        System.out.print(clearLine(v + " " + padRight(l == null ? "" : l, width) + " " + v) + "\n");
      }
    }
    System.out.print(clearLine(jm + repeat(h, width + 2) + jmr) + "\n");
    System.out.print(clearLine(v + " " + applyStyle(padRight(footer == null ? "" : footer, width), style.dim) + " " + v) + "\n");
    System.out.print(clearLine(bl + repeat(h, width + 2) + br) + "\n");
    System.out.flush();
    int count = lines == null ? 0 : lines.size();
    lastMenuLines = 6 + count;
    cursorFromMenuBottom = 0;
  }

  private void waitForEnter() {
    if (!setTerminalRaw(true)) {
      readLine();
      return;
    }
    try {
      while (true) {
        int ch = System.in.read();
        if (ch == -1) break;
        if (ch == '\r' || ch == '\n') break;
        if (ch == 27) break;
      }
    } catch (Throwable ignored) {
    } finally {
      setTerminalRaw(false);
    }
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
