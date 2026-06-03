# DIVA Slider

[English](README.md)

本项目是一个面向 Project DIVA Arcade 的手机和浏览器滑条模拟器
，提供 32 格触摸滑条、机台按钮灯光反馈，不需要修改游戏文件，项目灵感来源于[brokenithm](https://github.com/tindy2013/Brokenithm-Android)

本仓库 **不包含** 游戏文件、Segatools 或 PDLoader 文件。

## 功能

- 支持 DLL/TLAC 路径和摇杆模拟模式的触摸滑条输入。
- Circle、Cross、Square、Triangle、Start、Test、Service 和 Coin 输入。
- 带实时滑条/按钮灯光显示的浏览器面板。
- 支持 UDP/WebSocket 和实时灯光反馈的 Android App。

## 快速开始

下载最新 Release 包并解压其中附带的服务端、DLL 和 Android APK。

1. 配置 Segatools/divahook 加载 DLL：

```ini
[divaio]
path=path\to\divaslider.dll
```

2. 启动游戏前先启动服务端：

```powershell
.\divaslider-server.exe
```

需要详细输入状态日志时使用 `--debug`：

```powershell
.\divaslider-server.exe --debug
```

3. 安装并打开 Android App，或者打开浏览器客户端。

- Android UDP：PC IP，端口 `52468`。
- Android WebSocket：PC IP，端口 `52469`。
- 浏览器客户端：`http://127.0.0.1:52469/`。

同一时间只应启用一个输入发送端。浏览器页面默认是 `INPUT OFF`，需要使用请手动打开，保持关闭则可以让它一直显示 LED 灯光而不干扰 Android App。

## PDLoader / TLAC

在 PDLoader/TLAC 环境下，DLL 的 TLAC 路径会在 TLAC 每帧刷新输入之后写入输入状态。
请使用以下配置，避免 TLAC 覆盖本项目写入的输入：

```ini
; plugins/components.ini
input_emulator = false
touch_slider_emulator = false

; plugins/config.ini
hardware_slider = 1
```

## 默认端口

- UDP 输入：`52468`
- HTTP/WebSocket/调试页面：`52469`
- 共享内存：`Local\DIVASLIDER_SHARED_BUFFER`

## 按钮位

```text
0x01 Circle
0x02 Cross
0x04 Square
0x08 Triangle
0x10 Start
0x20 Test
0x40 Service
0x80 Coin pulse
0x100 Nav layer
```

Test 和 Service 在客户端中按街机逻辑处理为短脉冲。Start 和四个游戏按钮支持正常长按。Nav layer 仅用于摇杆模拟模式：按住 NAV 时，Triangle/Square/Cross/Circle 会临时映射为 DS4 上/左/下/右方向键。

## Android App

从 Release 包安装 APK：

```powershell
adb install -r .\DIVA-Slider.apk
```

USB WebSocket 模式：

```powershell
adb reverse tcp:52469 tcp:52469
```

然后在 App 中使用 Host `127.0.0.1`，模式 `WebSocket`，端口 `52469`。

## 浏览器客户端

打开：

```text
http://127.0.0.1:52469/
```

浏览器客户端会显示游戏返回的滑条 RGB 灯光和按钮灯亮度。当你需要使用WEB端发送输入时，点击 `INPUT OFF` 切换为 `INPUT ON`。

**注意**：使用 Android App 时，不要让浏览器客户端保持 `INPUT ON`，否则两边会争抢输入控制。

可用的调试端点：

```text
http://127.0.0.1:52469/status
http://127.0.0.1:52469/debug/hold?button=circle&ms=5000
http://127.0.0.1:52469/debug/slider?cell=16&pressure=80&ms=5000
```

## 仓库结构

```text
.
App/      Android controller app
Dll/      Segatools/divahook-compatible divaio DLL
Server/   Go input server and browser debug client
Resource/ Local-only references/dependencies, ignored by git
```

## 构建要求


- Windows。
- 用于 `Server/` 的 Go 1.25 或更新版本。
- 用于 `Dll/` 的 Visual Studio Build Tools x64 C/C++ 工具链。
- 用于 `App/` 的 Android Studio，或 Android SDK 加 JDK 17。
- DLL 构建所需的 Detours 头文件和库。

当前 DLL 构建脚本依赖PDloader的以下本地文件：

```text
Resource/PDloader/Code/source-code/dependencies/detours/include/detours.h
Resource/PDloader/Code/source-code/dependencies/detours/lib/detours.lib
```

## 从源码构建

构建服务端：

```powershell
cd Server
go build -o .\build\divaslider-server.exe .\cmd\divaslider-server
```

在 x64 Visual Studio Developer PowerShell 中构建 DLL：

```powershell
cd Dll
.\build.ps1
```

构建 Android App：

```powershell
cd App
.\gradlew.bat :app:assembleDebug
```

运行服务端测试：

```powershell
cd Server
go test ./...
```

## 开发调试

DLL 诊断日志会写入游戏当前工作目录：

```text
divaslider-dll.log
```

- `shared memory opened`：DLL 可以看到服务端。
- `jvs_state`：DLL 读取到的 JVS 按钮和投币状态。
- `slider_state`：DLL 读取到的滑条压力状态。
- `pdloader_bridge: engine input hook active`：TLAC hook 路径已激活。
