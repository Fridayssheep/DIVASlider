# DIVA Slider Server

Go server for phone-based Project DIVA slider/button input.

## Run

```bash
go run ./cmd/divaslider-server
```

Verbose input state logs are disabled by default. Enable them only when debugging:

```bash
go run ./cmd/divaslider-server --debug
```

Defaults:

- HTTP/WebSocket debug page: `http://<pc-ip>:52469/`
- UDP input: `:52468`
- shared memory: `Local\DIVASLIDER_SHARED_BUFFER`

On non-Windows systems the shared memory layer uses an in-process stub so the server and tests can still run.

## Debugging

Open `http://127.0.0.1:52469/status` while the server is running to verify
that input reaches the server.

Only one input sender should be active at a time. If you switch from the web
client to the phone client, stop the previous sender first.

You can also inject test input without the phone client:

```text
http://127.0.0.1:52469/debug/hold?button=circle&ms=5000
http://127.0.0.1:52469/debug/slider?cell=16&pressure=80&ms=5000
```

If `/status` changes but the game does not react, inspect the game's
`divaslider-dll.log`. `shared memory opened`, `jvs_state`, and `slider_state`
mean the DLL is loaded and reading the server data.

## UDP Packets

All UDP packets start with a one-byte payload length followed by a three-byte ASCII type.

### DIVA Input

`DVS` sends the complete DIVA input state:

```text
[0]      payload length = 42
[1..3]   "DVS"
[4..7]   sequence, big-endian uint32
[8..9]   button bitmask, little-endian uint16
[10..41] slider pressure, 32 bytes
```

Button bits:

- `0x01` Circle
- `0x02` Cross
- `0x04` Square
- `0x08` Triangle
- `0x10` Start
- `0x20` Test
- `0x40` Service
- `0x80` Coin pulse

## Shared Memory Layout

The injected `divaio` DLL reads `Local\DIVASLIDER_SHARED_BUFFER`.

```text
[0..3]    magic: "DIVA"
[4..5]    layout version, little-endian uint16 = 1
[6..7]    buffer size, little-endian uint16 = 256
[8..11]   sequence, little-endian uint32
[12..19]  updated Unix milliseconds, little-endian uint64
[20..21]  live button bitmask, little-endian uint16
[22]      connected flag, 1 when recent input is available
[24..55]  slider pressure, 32 bytes
[56..57]  coin counter, little-endian uint16
```
