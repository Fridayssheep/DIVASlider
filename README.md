# DIVA Slider

[中文文档](README.zh-CN.md)

DIVA Slider is a phone and browser based input bridge for Project DIVA Arcade style setups.
It provides a 32-cell touch slider, cabinet buttons, coin pulses, and LED feedback without
modifying game files.

This repository **does not** include game files, Segatools, or PDLoader binaries.

## Features

- 32-cell slider pressure input that simulates the arcade slider's behavior.
- Circle, Cross, Square, Triangle, Start, Test, Service, and Coin input.
- Browser panel with live slider/button LED display.
- Android app with UDP/WebSocket modes and live LED feedback.


## Quick Start

Download the latest release package then extract it
use the included server, DLL, and Android APK.

Expected release files:

```text
divaslider-server.exe
divaslider.dll
DIVA-Slider.apk
```

1. Configure Segatools/divahook to load the DLL:

```ini
[divaio]
path=path\to\divaslider.dll
```

2. Start the server before starting the game:

```powershell
.\divaslider-server.exe
```

Use `--debug` only when you need verbose input state logs:

```powershell
.\divaslider-server.exe --debug
```

3. Install and open the Android app, or open the browser client.

- Android UDP: PC IP, port `52468`.
- Android WebSocket: PC IP, port `52469`.
- Browser client: `http://127.0.0.1:52469/`.

Only one active input sender should be enabled at a time. The browser page defaults to
`INPUT OFF`, so it can be left open for LED display without fighting the Android app.

## PDLoader / TLAC Notes

For PDLoader/TLAC setups, the DLL's TLAC path is designed to write input after TLAC's own
per-frame input refresh. Use these settings so TLAC does not overwrite this bridge:

```ini
; plugins/components.ini
input_emulator = false
touch_slider_emulator = false

; plugins/config.ini
hardware_slider = 1
```

`hardware_slider = 1` keeps the original hardware slider flow alive. The DLL then behaves like a real slider backend rather than a keyboard emulator.



## Default Ports

- UDP input: `52468`
- HTTP/WebSocket/debug page: `52469`
- Shared memory: `Local\DIVASLIDER_SHARED_BUFFER`

## Button Bits

```text
0x01 Circle
0x02 Cross
0x04 Square
0x08 Triangle
0x10 Start
0x20 Test
0x40 Service
0x80 Coin pulse
```

Test and Service are treated as short arcade-style pulses by the clients. Start and the
four game buttons support normal hold behavior.

## Android App

Install the APK from the release package:

```powershell
adb install -r .\DIVA-Slider.apk
```

For USB WebSocket mode:

```powershell
adb reverse tcp:52469 tcp:52469
```

Then use host `127.0.0.1`, mode `WebSocket`, port `52469` in the app.

## Browser Client

Open:

```text
http://127.0.0.1:52469/
```

The browser client shows game-returned slider RGB LEDs and button LED brightness. Use the
top-right `INPUT OFF / INPUT ON` switch when you actually want the browser to send input.

**NOTE**: Do not leave the browser client open with `INPUT ON` when using the Android app, or they will fight for input control.

Useful debug endpoints:

```text
http://127.0.0.1:52469/status
http://127.0.0.1:52469/debug/hold?button=circle&ms=5000
http://127.0.0.1:52469/debug/slider?cell=16&pressure=80&ms=5000
```

## Repository Layout

```text
.
+-- App/      Android controller app
+-- Dll/      Segatools/divahook-compatible divaio DLL
+-- Server/   Go input server and browser debug client
`-- Resource/ Local-only references/dependencies, ignored by git
```

## Build Requirements

Release users do not need these. They are only required when building from source.

- Windows.
- Go 1.25 or newer for `Server/`.
- Visual Studio Build Tools with the x64 C/C++ toolchain for `Dll/`.
- Android Studio or Android SDK plus JDK 17 for `App/`.
- Detours headers and library for the DLL build.

The current DLL build script expects these local files:

```text
Resource/PDloader/Code/source-code/dependencies/detours/include/detours.h
Resource/PDloader/Code/source-code/dependencies/detours/lib/detours.lib
```

`Resource/` is intentionally ignored by git because it is for local references and
third-party files that should not be published in this repository.

## Build From Source

Build the server:

```powershell
cd Server
go build -o .\build\divaslider-server.exe .\cmd\divaslider-server
```

Build the DLL from an x64 Visual Studio Developer PowerShell:

```powershell
cd Dll
.\build.ps1
```

Build the Android app:

```powershell
cd App
.\gradlew.bat :app:assembleDebug
```

Server tests:

```powershell
cd Server
go test ./...
```

## Development

DLL diagnostics are written to the game's working directory:

```text
divaslider-dll.log
```

Useful log lines:

- `shared memory opened`: the DLL can see the server.
- `jvs_state`: JVS button and coin state read by the DLL.
- `slider_state`: slider pressure state read by the DLL.
- `pdloader_bridge: engine input hook active`: TLAC hook path is active.

## Legal

This is an unofficial compatibility project. It does not distribute copyrighted game data or third-party loader binaries.
