package uhf.tui;

import java.util.ArrayList;
import java.util.List;

public final class Tokenizer {
  private Tokenizer() {}

  public static List<String> tokenize(String line) {
    List<String> out = new ArrayList<>();
    if (line == null) return out;
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
}

