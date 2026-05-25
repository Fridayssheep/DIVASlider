# DIVA Slider Android

Native Android sender for `divaslider-server`.

## Protocol

The app sends `DVS` UDP packets to port `52468` by default. The sender loop
sleeps for 1 ms between packets, matching Brokenithm's Android sender style.

The app can also send input through WebSocket to `ws://host:52469/ws`. This is
useful for USB debugging with `adb reverse`. WebSocket mode sends the same
binary `DVS` packet as UDP.

Only one sender should be active at a time. Stop the previous client before
switching to a different sender.

## Build

Open this `App` directory in Android Studio and build `app`.

Command-line builds require Android SDK plus JDK 17:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Use

1. Start `divaslider-server.exe` on the PC.
2. Put the phone and PC on the same LAN.
3. Enter the PC IP in the app.
4. Tap `Connect`.

The top slider area maps to 32 pressure cells. The left four cells are the
cabinet L area and the right four cells are the R area.

## USB WebSocket Mode

Start the PC server, then run:

```powershell
adb reverse tcp:52469 tcp:52469
```

In the app:

- Host: `127.0.0.1`
- Mode: `WS`
- Tap `Connect`
