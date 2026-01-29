package uhf.tui;

import uhf.erp.ErpPusher;
import uhf.sdk.ReaderClient;

public record CommandContext(ReaderClient reader, ConsoleUi ui, ErpPusher erp) {}
