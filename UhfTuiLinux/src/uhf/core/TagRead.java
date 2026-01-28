package uhf.core;

public record TagRead(
    String ipAddr,
    String epcId,
    String memId,
    int rssi,
    int antId
) {}

