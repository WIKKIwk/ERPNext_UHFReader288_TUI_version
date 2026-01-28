package uhf.core;

public record InventoryParams(
    Result result,
    int address,
    int tidPtr,
    int tidLen,
    int session,
    int qValue,
    int scanTime,
    int antenna,
    int readType,
    int readMem,
    int readPtr,
    int readLength,
    String password
) {
}
