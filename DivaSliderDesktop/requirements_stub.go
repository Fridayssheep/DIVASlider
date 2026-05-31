//go:build !windows

package main

func checkJoystickRequirement() MethodRequirement {
	return MethodRequirement{
		MethodID: "joystick-slider",
		OK:       false,
		Severity: "error",
		Title:    "ViGEmBus 仅支持 Windows",
		Message:  "摇杆模拟滑动依赖 ViGEmBus DS4 虚拟手柄，当前平台不可用。",
		Action:   "请在 Windows 上使用这个输入方式。",
	}
}
