package uhf.sdk;

import com.rfid.CReader;
import com.rfid.ReadTag;
import com.rfid.TagCallback;
import java.util.function.Consumer;
import uhf.core.GpioStatus;
import uhf.core.ReaderInfo;
import uhf.core.Result;
import uhf.core.TagRead;

public final class ReaderClient {
  private CReader reader;
  private boolean connected;
  private Consumer<TagRead> tagConsumer = t -> {};
  private Runnable stopListener = () -> {};

  public boolean isConnected() {
    return connected;
  }

  public Result connect(
      String ip,
      int port,
      int readerType,
      int log,
      Consumer<TagRead> onTag,
      Runnable onStop
  ) {
    if (connected) return Result.fail(0x35);
    try {
      tagConsumer = onTag == null ? t -> {} : onTag;
      stopListener = onStop == null ? () -> {} : onStop;
      reader = new CReader(ip, port, readerType, log);
      reader.SetCallBack(new TagCallback() {
        @Override
        public void tagCallback(ReadTag tag) {
          tagConsumer.accept(new TagRead(
              tag.ipAddr,
              tag.epcId,
              tag.memId,
              tag.rssi,
              tag.antId
          ));
        }

        @Override
        public void StopReadCallback() {
          stopListener.run();
        }
      });
      int rc = reader.Connect();
      if (rc == 0) {
        connected = true;
        return Result.ok();
      }
      reader = null;
      return Result.fail(rc);
    } catch (Throwable t) {
      reader = null;
      return Result.fail(-1);
    }
  }

  public Result disconnect() {
    if (!connected || reader == null) return Result.ok();
    try {
      try { reader.StopRead(); } catch (Throwable ignored) {}
      reader.DisConnect();
      reader = null;
      connected = false;
      return Result.ok();
    } catch (Throwable t) {
      reader = null;
      connected = false;
      return Result.fail(-1);
    }
  }

  public Result startInventory() {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.StartRead();
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public Result stopInventory() {
    if (!connected || reader == null) return Result.fail(0x36);
    try {
      reader.StopRead();
      return Result.ok();
    } catch (Throwable t) {
      return Result.fail(-1);
    }
  }

  public Result setPower(int powerDbm) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetRfPower(powerDbm);
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public Result setRegion(int band, int maxFreq, int minFreq) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetRegion(band, maxFreq, minFreq);
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public Result setBeep(int enabled) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetBeepNotification(enabled);
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public Result setRelay(int value) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetRelay(value);
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public Result setGpio(int mask) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetGPIO(mask);
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public GpioStatus getGpio() {
    if (!connected || reader == null) return new GpioStatus(Result.fail(0x36), 0);
    byte[] out = new byte[1];
    int rc = reader.GetGPIOStatus(out);
    int mask = out[0] & 0xFF;
    return new GpioStatus(rc == 0 ? Result.ok() : Result.fail(rc), mask);
  }

  public ReaderInfo getInfo() {
    if (!connected || reader == null) return new ReaderInfo(Result.fail(0x36), 0, 0, 0, 0, 0, 0, 0, 0);
    byte[] version = new byte[2];
    byte[] power = new byte[1];
    byte[] band = new byte[1];
    byte[] maxFre = new byte[1];
    byte[] minFre = new byte[1];
    byte[] beep = new byte[1];
    int[] ant = new int[1];
    int rc = reader.GetUHFInformation(version, power, band, maxFre, minFre, beep, ant);
    Result result = rc == 0 ? Result.ok() : Result.fail(rc);
    return new ReaderInfo(
        result,
        version[0] & 0xFF,
        version[1] & 0xFF,
        power[0] & 0xFF,
        band[0] & 0xFF,
        minFre[0] & 0xFF,
        maxFre[0] & 0xFF,
        beep[0] & 0xFF,
        ant[0]
    );
  }

  public String getSerialNumber() {
    if (!connected || reader == null) return null;
    try {
      return reader.GetSerialNo();
    } catch (Throwable t) {
      return null;
    }
  }

  public Result setAntenna(int arg1, int arg2) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetAntenna(arg1, arg2);
    return rc == 0 ? Result.ok() : Result.fail(rc);
  }

  public static boolean probe(String ip, int port, int readerType, int log) {
    try {
      CReader r = new CReader(ip, port, readerType, log);
      int rc = r.Connect();
      if (rc == 0) {
        r.DisConnect();
        return true;
      }
      return false;
    } catch (Throwable t) {
      return false;
    }
  }
}

