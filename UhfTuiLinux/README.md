# UhfTuiLinux — Enterprise‑Ready Linux TUI for ST‑8504 / UHFReader288 / E710

**UhfTuiLinux** is a production‑grade terminal UI (TUI) for UHF readers based on the vendor’s **Linux Java SDK** (`CReader.jar`).  
It runs **Linux‑only**, provides **fast menu navigation**, and supports **LAN auto‑discovery** for field and warehouse deployments.

> This project does **not** use the Windows C# SDK (`UHFReader288.dll`), and it does **not** support USB‑serial on Linux.

---

## 1) What You Get (Enterprise Summary)

- **Stable Linux TUI** with structured menus (no noisy screen spam)
- **One‑command startup**: `./run.sh`
- **Auto‑discovery** on LAN (scan subnets & ports)
- **Inventory + Tag Ops** (read/write/lock/kill)
- **Config / IO**: power, write‑power, per‑antenna power, region, beep, relay, GPIO
- **Diagnostics**: antenna check, return loss
- **Standalone bundling** for offline deployment (includes local JRE + SDK)

---

## 2) Requirements

### Runtime
- Linux (x86_64 recommended)
- Terminal with ANSI support (fallback available)

### SDK (Required)
- Vendor Linux Java SDK: **`CReader.jar`**

### JDK/JRE
- If `javac` exists in `PATH`, it is used
- If not, the launcher auto‑downloads **JDK 17** to `~/.cache/uhftui-linux`

---

## 3) Quick Start (One Command)

From repo root:

```bash
./run.sh
```

This:
- Finds `CReader.jar`
- Downloads JDK if needed
- Compiles and runs the TUI

---

## 4) SDK Placement (Required)

Place the vendor JAR here:

```
lib/CReader.jar
```

Or run with:

```
SDK_JAR=/path/to/CReader.jar ./run.sh
```

---

## 5) Project Layout

```
run.sh                  # one‑command launcher
UhfTuiLinux/
  run.sh                # build+run (auto JDK)
  bundle.sh             # standalone bundle builder
  dist/                 # offline bundle output (ignored in git)
  src/                  # Java sources
  out/                  # compiled classes (ignored)
lib/
  CReader.jar           # vendor SDK (required)
```

---

## 6) TUI Navigation

- **↑ / ↓**: move
- **Enter**: select
- **Esc**: back
- **← / →**: back / forward (history)

If ANSI is not available, the UI falls back to line prompts.

---

## 7) Connectivity Model

### LAN (TCP/IP)
- Primary supported mode
- Auto‑scan subnets and ports

### USB (RNDIS/ECM only)
- If reader exposes **USB network**, it is detected as LAN
- **USB‑serial (`/dev/ttyUSB*`, `/dev/ttyACM*`) is NOT supported** by the Java SDK

---

## 8) Main Menu Map

- **Connection**: connect/disconnect
- **Scan/Auto**: LAN/USB auto‑scan
- **Inventory**: start/stop/once + params view/set
- **Tag Ops**: read/write/lock/kill
- **Config/IO**: power, region, GPIO, relay, antenna, diagnostics
- **Info**: reader info, serial
- **Command shell**: CLI commands
- **Quit**

---

## 9) Command Reference (Shell)

### Connection
```
connect <ip> [port] [readerType] [log]
disconnect
```

### Auto‑Discovery
```
scan [ports|auto|auto+] [readerType] [log] [prefix]
```

### Inventory
```
inv start
inv stop
inv-once [ms]
```

### Inventory Parameters
```
inv-param get
inv-param set [session q scanTime readType readMem readPtr readLen tidPtr tidLen antenna password [address]]
```

### Tag Ops (C1G2)
```
read-epc <epc> <mem> <wordPtr> <num> <password>
read-tid <tid> <mem> <wordPtr> <num> <password>
write-epc <epc> <mem> <wordPtr> <password> <data>
write-tid <tid> <mem> <wordPtr> <password> <data>
write-epc-id <epc> <password>
write-epc-by-tid <tid> <epc> <password>
lock <epc> <select> <protect> <password>
kill <epc> <password>
```

### Config / IO
```
power <0-33>
wpower get | set <0-33> [mode]
antpower get | set <0-33> [count]
region <band> <maxFreq> <minFreq>
beep <0|1>
gpio get
gpio set <mask>
relay <value>
antenna <arg1> <arg2>
checkant <0|1>
returnloss <antenna> <freqMHz>
```

---

## 10) Inventory Parameters (Notes)

- `address` default is `255` (broadcast)
- `readMem`: `0=Reserve, 1=EPC, 2=TID, 3=User`
- All fields are editable via **Inventory → Params (set)**

---

## 11) Safety Controls

Write/lock/kill operations ask for explicit confirmation (**Type YES**).  
This prevents accidental destructive actions.

---

## 12) Auto‑Discovery Details

### Subnet Detection
The tool inspects active IPv4 interfaces and builds `/24` prefixes.

### Port Scanning
Default ports include:
```
27011, 2022, 2000, 4001, 4002, 5000, 5001, 6000, 7000, 8000, 9000,
10000, 12000, 15000, 16000, 20000, 21000, 22000, 23000, 24000, 25000,
26000, 28000, 29000, 30000, 40000, 50000, 60000
```
Use specific ports for faster scans.

---

## 13) Standalone Bundle (Offline Deployment)

Create a **self‑contained** folder for sharing:

```bash
./UhfTuiLinux/bundle.sh
```

Output:

```
UhfTuiLinux/dist/
  start.sh
  app/
  lib/CReader.jar
  jre/
```

Run on another machine:

```bash
./start.sh
```

**Notes**
- Linux‑only
- Larger size (bundled JRE)
- `CReader.jar` may be proprietary; distribute responsibly

---

## 14) Manual Build (Optional)

```bash
javac -encoding UTF-8 -cp lib/CReader.jar -d UhfTuiLinux/out $(find UhfTuiLinux/src -name "*.java")
java -cp UhfTuiLinux/out:lib/CReader.jar uhf.tui.Main
```

---

## 15) Operations & Security (Enterprise Guidance)

- **Do not commit** vendor SDKs to public repositories.
- Restrict subnet scans in production (known VLAN/subnets only).
- Use dedicated VLANs for reader infrastructure.
- Keep reader firmware and SDK versions aligned across sites.

---

## 16) Troubleshooting

**No device found**
- Confirm same subnet
- Try a known port: `connect <ip> 27011 4 0`

**USB not working**
- Linux SDK does **not** support USB‑serial
- Only RNDIS/ECM (USB network) is supported

**Menu looks broken**
- Ensure a real TTY (not a redirected stdout)
- ANSI must be enabled for best rendering

---

## 17) Support & Ownership

This project relies on vendor SDK behavior.  
For low‑level protocol details or USB‑serial support, request an official Linux `.so` SDK from the vendor.
