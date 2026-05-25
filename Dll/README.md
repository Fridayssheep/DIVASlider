# DIVA Slider DLL

Custom `divaio` DLL for Segatools Project DIVA hook.

This DLL does not speak to the phone app directly. It only reads the shared
memory block written by the Go server:

```text
Local\DIVASLIDER_SHARED_BUFFER
```

## Build

Open a Visual Studio Developer PowerShell on Windows. Use the same architecture
as your Segatools build, usually x64:

```powershell
cd Dll
.\build.ps1
```

The output is:

```text
Dll\build\divaslider.dll
```

Check the DLL before putting it into Segatools:

```powershell
dumpbin /headers .\build\divaslider.dll | findstr machine
dumpbin /exports .\build\divaslider.dll | findstr diva_io
```

For a normal 64-bit DIVA/divahook setup, the first command must show `x64`.
If Segatools reports `800700c1`, the DLL is usually the wrong architecture or
not a valid Windows DLL.

## Segatools Config

Put the built DLL somewhere next to your Segatools setup, then set:

```ini
[divaio]
path=path\to\divaslider.dll
```

Run `divaslider-server.exe` before starting the game. The DLL will return empty
input until the shared memory appears.

## Quick Diagnosis

If the server logs input but the game does not react, check these points first:

1. Confirm `segatools.ini` has `[divaio] path=...divaslider.dll`.
2. Confirm `divaslider-dll.log` appears in the game working directory.
3. Confirm the log contains `shared memory opened` and changing `jvs_state` or
   `slider_state` lines while you press the web controls.
4. If running through PDLoader/TLAC, disable its input emulators as shown below.

## PDLoader / TLAC Config

When running through PDLoader, TLAC can override the same game input state that
the original JVS path writes. For this DLL path, let divahook provide input and
let the game consume the original slider device path:

```ini
; plugins/components.ini
input_emulator = false
touch_slider_emulator = false

; plugins/config.ini
hardware_slider = 1
```

`input_emulator = true` makes TLAC write the game `InputState` every frame from
its own keyboard/controller bindings, which can overwrite JVS input coming from
`diva_io_jvs_poll`.

`hardware_slider = 0` tells PDLoader to patch out the original slider state
update so TLAC can write its own emulated slider. This DLL behaves like a real
COM11/divahook slider backend, so that original update path must remain enabled.

## Debug Log

The DLL writes a lightweight log file in the game's current working directory:

```text
divaslider-dll.log
```

Useful lines:

- `shared memory opened`: the DLL can see the server
- `OpenFileMappingW(...) failed: 2`: the server is not running, or it is in a different Windows session
- `OpenFileMappingW(...) failed: 5`: permission/elevation mismatch; run server and game at the same elevation
- `jvs_state`: buttons and coin counter seen by the JVS path
- `slider_state`: slider cells seen by the slider callback thread

## Exported API

The DLL exports DIVA IO API `0x0101`:

- `diva_io_jvs_*` reads buttons and the cumulative coin counter
- `diva_io_slider_*` starts a 1ms polling thread and sends 32 pressure cells
- `diva_io_led_*` is currently a no-op
- `diva_io_touch_*` currently emits no touch events

Button and slider data are DIVA-specific. Brokenithm is not used as a runtime
dependency or compatibility protocol.
