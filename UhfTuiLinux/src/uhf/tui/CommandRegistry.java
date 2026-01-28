package uhf.tui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public final class CommandRegistry {
  public interface Command {
    void run(List<String> args, CommandContext ctx);
  }

  public record CommandDef(String name, String help, Command handler) {}

  private final Map<String, CommandDef> commands = new LinkedHashMap<>();

  public void register(String name, String help, Command handler, String... aliases) {
    CommandDef def = new CommandDef(name, help, handler);
    commands.put(name, def);
    if (aliases != null) {
      for (String a : aliases) {
        if (a != null && !a.isBlank()) commands.put(a, def);
      }
    }
  }

  public boolean execute(List<String> tokens, CommandContext ctx) {
    if (tokens == null || tokens.isEmpty()) return true;
    CommandDef def = commands.get(tokens.get(0));
    if (def == null) return false;
    def.handler().run(tokens, ctx);
    return true;
  }

  public List<CommandDef> listUnique() {
    Map<String, CommandDef> unique = new LinkedHashMap<>();
    for (CommandDef def : commands.values()) {
      unique.putIfAbsent(def.name(), def);
    }
    return new ArrayList<>(unique.values());
  }
}

