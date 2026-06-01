//go:build windows

package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"

	"golang.org/x/sys/windows/svc/mgr"
)

func checkJoystickRequirement() MethodRequirement {
	if err := loadViGEmClient(); err != nil {
		return MethodRequirement{
			MethodID: "joystick-slider",
			OK:       false,
			Severity: "error",
			Title:    "缺少 ViGEmClient.dll",
			Message:  "摇杆模拟需要 ViGEmClient.dll，程序没有找到可加载的客户端库。",
			Detail:   err.Error(),
			Action:   "请使用桌面端 build.ps1 构建，或把 ViGEmClient.dll 放到 DivaSliderDesktop.exe 同目录。",
		}
	}

	stateName, err := vigemBusServiceState()
	if err != nil {
		// 检查是否是权限问题
		if isAccessDeniedError(err) {
			return MethodRequirement{
				MethodID: "joystick-slider",
				OK:       false,
				Severity: "error",
				Title:    "权限不足",
				Message:  "无法访问 ViGEmBus 服务，可能是因为程序没有以管理员权限运行。",
				Detail:   err.Error(),
				Action:   "请右键点击程序，选择\"以管理员身份运行\"，然后重新选择摇杆模拟滑动。",
			}
		}
		return MethodRequirement{
			MethodID: "joystick-slider",
			OK:       false,
			Severity: "error",
			Title:    "未检测到 ViGEmBus 驱动",
			Message:  "摇杆模拟需要系统安装并运行 ViGEmBus 驱动，否则无法创建 DS4 虚拟手柄。",
			Detail:   err.Error(),
			Action:   "请先安装 ViGEmBus 驱动（https://github.com/ViGEm/ViGEmBus/releases），然后重新打开本程序。",
		}
	}
	if stateName != "Running" {
		return MethodRequirement{
			MethodID: "joystick-slider",
			OK:       false,
			Severity: "warning",
			Title:    "ViGEmBus 未运行",
			Message:  "系统里能找到 ViGEmBus，但服务当前没有运行。",
			Detail:   "当前状态：" + stateName,
			Action:   "请在服务或设备管理器里启动 ViGEmBus，必要时重新安装驱动。",
		}
	}

	return MethodRequirement{
		MethodID: "joystick-slider",
		OK:       true,
		Severity: "ok",
		Title:    "ViGEmBus 可用",
		Message:  "已检测到 ViGEmClient.dll 和正在运行的 ViGEmBus 驱动。",
		Action:   "可以启动服务并使用 DS4 摇杆模拟滑动。",
	}
}

func loadViGEmClient() error {
	for _, candidate := range vigemClientCandidates() {
		dll := syscall.NewLazyDLL(candidate)
		if err := dll.Load(); err == nil {
			return nil
		}
	}
	return errors.New("ViGEmClient.dll was not found in the application or resource directories")
}

func vigemClientCandidates() []string {
	executable, _ := os.Executable()
	executableDir := filepath.Dir(executable)
	candidates := []string{
		"ViGEmClient.dll",
		filepath.Join(executableDir, "ViGEmClient.dll"),
	}
	if wd, err := os.Getwd(); err == nil {
		candidates = append(candidates, projectDLLCandidates(wd)...)
	}
	candidates = append(candidates, projectDLLCandidates(executableDir)...)
	return candidates
}

func vigemBusServiceState() (string, error) {
	manager, err := mgr.Connect()
	if err != nil {
		return "", fmt.Errorf("open service manager: %w", err)
	}
	defer manager.Disconnect()

	service, err := manager.OpenService("ViGEmBus")
	if err != nil {
		return "", fmt.Errorf("open ViGEmBus service: %w", err)
	}
	defer service.Close()

	status, err := service.Query()
	if err != nil {
		return "", fmt.Errorf("query ViGEmBus service: %w", err)
	}
	return serviceStateName(uint32(status.State)), nil
}

func serviceStateName(state uint32) string {
	switch state {
	case 1:
		return "Stopped"
	case 2:
		return "StartPending"
	case 3:
		return "StopPending"
	case 4:
		return "Running"
	case 5:
		return "ContinuePending"
	case 6:
		return "PausePending"
	case 7:
		return "Paused"
	default:
		return fmt.Sprintf("Unknown(%d)", state)
	}
}

func projectDLLCandidates(start string) []string {
	var candidates []string
	dir := filepath.Clean(start)
	for {
		candidates = append(candidates,
			filepath.Join(dir, "ViGEmClient.dll"),
			filepath.Join(dir, "Resource", "ViGEmBus", "sdk", "bin", "release", "x64", "ViGEmClient.dll"),
		)
		parent := filepath.Dir(dir)
		if parent == dir {
			return candidates
		}
		dir = parent
	}
}

func isAccessDeniedError(err error) bool {
	if err == nil {
		return false
	}
	// Windows 访问拒绝错误通常包含错误码 5 (ERROR_ACCESS_DENIED)
	return syscall.Errno(5) == errors.Unwrap(err) ||
		errors.Is(err, syscall.ERROR_ACCESS_DENIED) ||
		errors.Is(err, os.ErrPermission)
}
