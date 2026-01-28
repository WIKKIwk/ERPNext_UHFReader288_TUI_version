package uhf.core;

public record ReaderInfo(
    Result result,
    int versionMajor,
    int versionMinor,
    int power,
    int band,
    int minFreq,
    int maxFreq,
    int beep,
    int antenna
) {}

