# DIVA Slider

[English](README.md)

DIVA Slider 是一个面向 Project DIVA Arcade 风格环境的手机和浏览器输入桥接工具。
它提供 32 格触摸滑条、机台按钮、投币脉冲和灯光反馈，不需要修改游戏文件。

本仓库 **不包含** 游戏文件、Segatools 或 PDLoader 二进制文件。

## 功能

- 模拟街机滑条行为的 32 格滑条压力输入。
- Circle、Cross、Square、Triangle、Start、Test、Service 和 Coin 输入。
- 带实时滑条/按钮灯光显示的浏览器面板。
- 支持 UDP/WebSocket 和实时灯光反馈的 Android App。

## 快速开始

下载最新 Release 包并解压，使用其中附带的服务端、DLL 和 Android APK。

Release 包预计包含：

```text
divaslider-server.exe
divaslider.dll
DIVA-Slider.apk
```

1. 配置 Segatools/divahook 加载 DLL：

```ini
[divaio]
path=path\to\divaslider.dll
```

2. 启动游戏前先启动服务端：

```powershell
.\divaslider-server.exe
```

只有需要详细输入状态日志时才使用 `--debug`：

```powershell
.\divaslider-server.exe --debug
```

3. 安装并打开 Android App，或者打开浏览器客户端。

- Android UDP：PC IP，端口 `52468`。
- Android WebSocket：PC IP，端口 `52469`。
- 浏览器客户端：`http://127.0.0.1:52469/`。

同一时间只应启用一个输入发送端。浏览器页面默认是 `INPUT OFF`，因此可以只用它看灯光，
不会和 Android App 抢输入控制。

## PDLoader / TLAC 说明

在 PDLoader/TLAC 环境下，DLL 的 TLAC 路径会在 TLAC 每帧刷新输入之后写入输入状态。
请使用以下配置，避免 TLAC 覆盖本项目写入的输入：

```ini
; plugins/components.ini
input_emulator = false
touch_slider_emulator = false

; plugins/config.ini
hardware_slider = 1
```

`hardware_slider = 1` 会保留原始硬件滑条流程。此时 DLL 的行为更接近真实滑条后端，
而不是键盘模拟器。

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
```

Test 和 Service 在客户端中按街机逻辑处理为短脉冲。Start 和四个游戏按钮支持正常长按。

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

浏览器客户端会显示游戏返回的滑条 RGB 灯光和按钮灯亮度。只有需要让浏览器发送输入时，
才使用右上角的 `INPUT OFF / INPUT ON` 开关。

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
+-- App/      Android controller app
+-- Dll/      Segatools/divahook-compatible divaio DLL
+-- Server/   Go input server and browser debug client
`-- Resource/ Local-only references/dependencies, ignored by git
```

## 构建要求

Release 用户不需要这些依赖。只有从源码构建时才需要准备。

- Windows。
- 用于 `Server/` 的 Go 1.25 或更新版本。
- 用于 `Dll/` 的 Visual Studio Build Tools x64 C/C++ 工具链。
- 用于 `App/` 的 Android Studio，或 Android SDK 加 JDK 17。
- DLL 构建所需的 Detours 头文件和库。

当前 DLL 构建脚本期望以下本地文件存在：

```text
Resource/PDloader/Code/source-code/dependencies/detours/include/detours.h
Resource/PDloader/Code/source-code/dependencies/detours/lib/detours.lib
```

`Resource/` 会被 git 忽略，因为它只用于本地参考文件和不应发布到仓库的第三方文件。

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

常用日志行：

- `shared memory opened`：DLL 可以看到服务端。
- `jvs_state`：DLL 读取到的 JVS 按钮和投币状态。
- `slider_state`：DLL 读取到的滑条压力状态。
- `pdloader_bridge: engine input hook active`：TLAC hook 路径已激活。

## 法律说明

这是一个非官方兼容项目。本项目不分发任何受版权保护的游戏数据或第三方 loader 二进制文件。
