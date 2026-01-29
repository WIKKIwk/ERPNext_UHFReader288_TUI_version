package uhf.erp;

public record ErpTagEvent(
    String epcId,
    String memId,
    int rssi,
    int antId,
    String ipAddr,
    long ts
) {
}
