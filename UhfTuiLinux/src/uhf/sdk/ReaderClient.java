package uhf.sdk;

import com.rfid.CReader;
import com.rfid.ReadTag;
import com.rfid.ReaderParameter;
import com.rfid.TagCallback;
import java.util.function.Consumer;
import uhf.core.AntennaPowerInfo;
import uhf.core.GpioStatus;
import uhf.core.InventoryParams;
import uhf.core.ReaderInfo;
import uhf.core.Result;
import uhf.core.TagRead;
import uhf.core.ReturnLossInfo;
import uhf.core.WritePowerInfo;

public final class ReaderClient {
  private CReader reader;
  private boolean connected;
  private int antennaCount = 4;
  private Consumer<TagRead> tagConsumer = t -> {};
  private Runnable stopListener = () -> {};

  public boolean isConnected() {
    return connected;
  }

  public int getAntennaCount() {
    return antennaCount;
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
      antennaCount = readerType == 16 ? 16 : 4;
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
        return Result.success();
      }
      reader = null;
      return Result.fail(rc);
    } catch (Throwable t) {
      reader = null;
      return Result.fail(-1);
    }
  }

  public Result disconnect() {
    if (!connected || reader == null) return Result.success();
    try {
      try { reader.StopRead(); } catch (Throwable ignored) {}
      reader.DisConnect();
      reader = null;
      connected = false;
      return Result.success();
    } catch (Throwable t) {
      reader = null;
      connected = false;
      return Result.fail(-1);
    }
  }

  public Result startInventory() {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.StartRead();
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result stopInventory() {
    if (!connected || reader == null) return Result.fail(0x36);
    try {
      reader.StopRead();
      return Result.success();
    } catch (Throwable t) {
      return Result.fail(-1);
    }
  }

  public Result setPower(int powerDbm) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetRfPower(powerDbm);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result setRegion(int band, int maxFreq, int minFreq) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetRegion(band, maxFreq, minFreq);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result setBeep(int enabled) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetBeepNotification(enabled);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result setRelay(int value) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetRelay(value);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result setGpio(int mask) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetGPIO(mask);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public GpioStatus getGpio() {
    if (!connected || reader == null) return new GpioStatus(Result.fail(0x36), 0);
    byte[] out = new byte[1];
    int rc = reader.GetGPIOStatus(out);
    int mask = out[0] & 0xFF;
    return new GpioStatus(rc == 0 ? Result.success() : Result.fail(rc), mask);
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
    Result result = rc == 0 ? Result.success() : Result.fail(rc);
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
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result setRfPowerByAnt(int[] powers) {
    if (!connected || reader == null) return Result.fail(0x36);
    if (powers == null || powers.length == 0) return Result.fail(-1);
    byte[] out = new byte[powers.length];
    for (int i = 0; i < powers.length; i++) {
      int p = powers[i];
      if (p < 0) p = 0;
      if (p > 33) p = 33;
      out[i] = (byte) (p & 0xFF);
    }
    int rc = reader.SetRfPowerByAnt(out);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public AntennaPowerInfo getRfPowerByAnt(int count) {
    if (!connected || reader == null) return new AntennaPowerInfo(Result.fail(0x36), new int[0]);
    int n = count > 0 ? count : antennaCount;
    byte[] out = new byte[n];
    int rc = reader.GetRfPowerByAnt(out);
    Result r = rc == 0 ? Result.success() : Result.fail(rc);
    int[] powers = new int[n];
    for (int i = 0; i < n; i++) powers[i] = out[i] & 0xFF;
    return new AntennaPowerInfo(r, powers);
  }

  public Result setCheckAnt(boolean enabled) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.SetCheckAnt((byte) (enabled ? 1 : 0));
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public ReturnLossInfo measureReturnLoss(int antenna, int freqKhz) {
    if (!connected || reader == null) return new ReturnLossInfo(Result.fail(0x36), 0, freqKhz, antenna);
    int freq = Math.max(0, freqKhz);
    byte[] testFreq = new byte[] {
        (byte) ((freq >> 24) & 0xFF),
        (byte) ((freq >> 16) & 0xFF),
        (byte) ((freq >> 8) & 0xFF),
        (byte) (freq & 0xFF)
    };
    byte[] out = new byte[1];
    int rc = reader.MeasureReturnLoss(testFreq, (byte) antenna, out);
    Result r = rc == 0 ? Result.success() : Result.fail(rc);
    int loss = out[0] & 0xFF;
    return new ReturnLossInfo(r, loss, freq, antenna);
  }

  public Result setWritePower(int powerDbm, boolean highMode) {
    if (!connected || reader == null) return Result.fail(0x36);
    int value = powerDbm & 0x3F;
    if (highMode) value |= 0x80;
    int rc = reader.SetWritePower((byte) value);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public WritePowerInfo getWritePower() {
    if (!connected || reader == null) return new WritePowerInfo(Result.fail(0x36), 0, false);
    byte[] out = new byte[1];
    int rc = reader.GetWritePower(out);
    Result r = rc == 0 ? Result.success() : Result.fail(rc);
    int raw = out[0] & 0xFF;
    boolean high = (raw & 0x80) != 0;
    int power = raw & 0x3F;
    return new WritePowerInfo(r, power, high);
  }

  public InventoryParams getInventoryParams() {
    if (!connected || reader == null) {
      return defaultInventoryParams(Result.fail(0x36));
    }
    try {
      ReaderParameter p = reader.GetInventoryParameter();
      if (p == null) return defaultInventoryParams(Result.fail(-1));
      return new InventoryParams(
          Result.success(),
          p.GetAddress() & 0xFF,
          p.GetTidPtr(),
          p.GetTidLen(),
          p.GetSession(),
          p.GetQValue(),
          p.GetScanTime(),
          p.GetAntenna(),
          p.GetReadType(),
          p.GetReadMem(),
          p.GetReadPtr(),
          p.GetReadLength(),
          p.GetPassword()
      );
    } catch (Throwable t) {
      return defaultInventoryParams(Result.fail(-1));
    }
  }

  public Result setInventoryParams(InventoryParams params) {
    if (!connected || reader == null) return Result.fail(0x36);
    try {
      ReaderParameter p = new ReaderParameter();
      p.SetAddress((byte) params.address());
      p.SetTidPtr(params.tidPtr());
      p.SetTidLen(params.tidLen());
      p.SetSession(params.session());
      p.SetQValue(params.qValue());
      p.SetScanTime(params.scanTime());
      p.SetAntenna(params.antenna());
      p.SetReadType(params.readType());
      p.SetReadMem(params.readMem());
      p.SetReadPtr(params.readPtr());
      p.SetReadLength(params.readLength());
      if (params.password() != null) {
        p.SetPassword(params.password());
      }
      reader.SetInventoryParameter(p);
      return Result.success();
    } catch (Throwable t) {
      return Result.fail(-1);
    }
  }

  public String readDataByEpc(String epc, int mem, int wordPtr, int num, String password) {
    if (!connected || reader == null) return null;
    try {
      return reader.ReadDataByEPC(epc, (byte) mem, (byte) wordPtr, (byte) num, password);
    } catch (Throwable t) {
      return null;
    }
  }

  public String readDataByTid(String tid, int mem, int wordPtr, int num, String password) {
    if (!connected || reader == null) return null;
    try {
      return reader.ReadDataByTID(tid, (byte) mem, (byte) wordPtr, (byte) num, password);
    } catch (Throwable t) {
      return null;
    }
  }

  public Result writeDataByEpc(String epc, int mem, int wordPtr, String password, String data) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.WriteDataByEPC(epc, (byte) mem, (byte) wordPtr, password, data);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result writeDataByTid(String tid, int mem, int wordPtr, String password, String data) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.WriteDataByTID(tid, (byte) mem, (byte) wordPtr, password, data);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result writeEpc(String epc, String password) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.WriteEPC(epc, password);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result writeEpcByTid(String tid, String epc, String password) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.WriteEPCByTID(tid, epc, password);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result lock(String epc, int select, int protect, String password) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.Lock(epc, (byte) select, (byte) protect, password);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  public Result kill(String epc, String password) {
    if (!connected || reader == null) return Result.fail(0x36);
    int rc = reader.Kill(epc, password);
    return rc == 0 ? Result.success() : Result.fail(rc);
  }

  private static InventoryParams defaultInventoryParams(Result result) {
    return new InventoryParams(
        result,
        0xFF,
        0,
        6,
        1,
        4,
        10,
        1,
        0,
        3,
        0,
        6,
        "00000000"
    );
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
