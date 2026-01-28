package uhf.tui;

import uhf.sdk.ReaderClient;

public record CommandContext(ReaderClient reader, ConsoleUi ui) {}

