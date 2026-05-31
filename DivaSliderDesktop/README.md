# DIVA Slider Desktop

Wails desktop launcher for DIVA Slider settings.

The Web input page is still served by the Go server. This desktop app controls the same server runner and provides:

- Input method selection for `DLL / TLAC 注入` and `摇杆模拟滑动`
- HTTP/WebSocket and UDP input port settings
- Start/stop controls
- A shortcut to open the Web input page
- Output status for DLL shared memory and slider emulation mapping

## Run

```powershell
wails dev
```

## Build

```powershell
.\build.ps1
```

The built executable is written to `DivaSliderDesktop\build\bin\DivaSliderDesktop.exe`.
The build script also copies `ViGEmClient.dll` beside the executable.

## Slider Emulation

The desktop launcher exposes two input methods:

- `dll-tlac`: shared memory for the existing DLL/TLAC/PDLoader path.
- `joystick-slider`: ViGEmBus DS4 virtual controller output. Slider movement is translated to analog stick direction for games that accept normal controller input.

`joystick-slider` needs the ViGEmBus driver installed and running on Windows. `ViGEmClient.dll` is a user-mode client library and is copied from `Resource\ViGEmBus\sdk\bin\release\x64`.
