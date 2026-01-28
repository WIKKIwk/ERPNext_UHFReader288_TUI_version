# UhfTuiLinux (Linux‑only)

Enterprise‑ready Linux TUI for **UHFReader288 / ST‑8504 / E710** class devices, built on the vendor’s **Linux Java SDK** (`CReader.jar`).  
This module is **Linux‑only**; the Windows C# SDK (`UHFReader288.dll`) is not used.

---

## 1) What This Is

UhfTuiLinux is a terminal UI (TUI) that:

- Connects to UHF readers over **TCP/IP (LAN)** using `CReader.jar`
- Starts/stops inventory scans
- Streams EPC reads in real time
- Supports auto‑discovery on common ports and subnets

It is suitable for **production operations**, **QA**, and **integration testing**.

---

## 2) Dependencies & Requirements

**Runtime**

- Linux (x86_64 recommended)
- Internet access **only if** `javac` is not installed (for auto JDK download)
- `curl` or `wget` for JDK auto‑download

**SDK**

- Vendor Linux Java SDK: **`CReader.jar`**

**JDK**

- If `javac` exists in PATH, it will be used.
- If not, `run.sh` downloads **JDK 17** locally (no sudo required).

---

## 3) SDK Placement (Required)

Place the vendor JAR here:

```
lib/CReader.jar
```

Or run with:

```
SDK_JAR=/path/to/CReader.jar ./UhfTuiLinux/run.sh
```

---

## 4) Quick Start

```bash
./UhfTuiLinux/run.sh
```

On startup, you will see single‑line “swipe” choices:

```
Auto connect [LAN|USB|Skip] (↑/↓ + Enter)
```

- **LAN** → automatically scan subnets and connect  
- **USB** → tries to detect **USB network interfaces (RNDIS/ECM)** and then uses LAN logic  
- **Skip** → manual mode

If ANSI/TTY features are missing, the UI falls back to numeric selection.

---

## 5) Command Reference

### 5.1 Manual Connection

```
connect <ip> [port] [readerType] [log]
```

- `port` default: `27011`
- `readerType` default: `4` (use `16` for 16‑antenna models)
- `log` default: `0`

### 5.2 Auto‑Discovery

```
scan [ports|auto|auto+] [readerType] [log] [prefix]
```

Examples:

```
scan auto 4 0
scan auto+ 4 0
scan 27011,2022 4 0 192.168.1
scan 2000-2100 4 0
```

**Modes**

- `auto` → default port list
- `auto+` → wide port list (more thorough, slower)
- `ports` → custom list/range

### 5.3 Inventory

```
inv start
inv stop
```

### 5.4 Status / Configuration

```
info
serial
power <0-33>
region <band> <maxFreq> <minFreq>
beep <0|1>
gpio get
gpio set <mask>
relay <value>
antenna <arg1> <arg2>
disconnect
quit
```

### 5.5 Tag Read/Write (C1G2)

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

**Safety:** write/lock/kill commands require confirmation (`Type YES to continue`).

---

## 6) Auto‑Discovery Details

**Subnet Detection**

The tool enumerates active IPv4 interfaces and builds `/24` prefixes.  
You can force a prefix (e.g., `192.168.1`) to narrow the search.

**Port Scanning**

Default ports include:

```
27011, 2022, 2000, 4001, 4002, 5000, 5001, 6000, 7000, 8000, 9000,
10000, 12000, 15000, 16000, 20000, 21000, 22000, 23000, 24000, 25000,
26000, 28000, 29000, 30000, 40000, 50000, 60000
```

`auto+` adds wider ranges (2000‑2100, 27000‑27150, etc).

**Performance**

- More ports → slower scan
- Use a specific subnet and port list for fastest results

---

## 7) USB Support (Important)

The **Linux vendor SDK supports TCP/IP only**.  
“USB” in this UI means:

- If the device exposes a **USB network interface** (RNDIS/ECM), it will be detected and scanned.
- If the device appears only as **serial** (`/dev/ttyUSB*` or `/dev/ttyACM*`), it **cannot be used** by this SDK.

If you need **true USB/serial support** on Linux:

- Obtain a **native Linux `.so` library** or **serial protocol specification** from the vendor.

---

## 8) Build/Compile (Manual)

```bash
javac -encoding UTF-8 -cp lib/CReader.jar -d UhfTuiLinux/out UhfTuiLinux/src/UhfTuiLinux.java
java -cp UhfTuiLinux/out:lib/CReader.jar UhfTuiLinux
```

---

## 9) Security & Operations Guidance

- Place `CReader.jar` in `lib/` but **do not commit vendor binaries** to public Git.
- Restrict network scanning in production environments to known subnets.
- Use dedicated VLANs for reader infrastructure.

---

## 10) Troubleshooting

**No device found**

- Ensure device IP is in the same subnet
- Try a known port (`connect <ip> 27011 4 0`)
- Use `scan auto` or `scan 27011,2022 4 0`

**USB not working**

- Confirm the device exposes a network interface (RNDIS/ECM)
- Run `ip a` and check for `usb*` or `rndis*` interfaces
- If only `/dev/ttyUSB*` appears, Linux SDK cannot use it

**JDK download fails**

- Install JDK manually (e.g., `pacman -S jdk-openjdk`)
- Or ensure `javac` is on PATH

---

## 11) License / SDK

`CReader.jar` is vendor‑provided and may be proprietary.  
Make sure to comply with vendor licensing terms.
