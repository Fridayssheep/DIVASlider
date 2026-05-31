package main

import "divaslider-server/pkg/inputmethod"

type MethodRequirement struct {
	MethodID string `json:"methodId"`
	OK       bool   `json:"ok"`
	Severity string `json:"severity"`
	Title    string `json:"title"`
	Message  string `json:"message"`
	Detail   string `json:"detail,omitempty"`
	Action   string `json:"action"`
}

func checkMethodRequirement(id string) MethodRequirement {
	switch id {
	case inputmethod.MethodTLAC, "":
		return MethodRequirement{
			MethodID: inputmethod.MethodTLAC,
			OK:       true,
			Severity: "info",
			Title:    "需要 divaslider.dll",
			Message:  "这个方式需要把 divaslider.dll 配置到 divahook、Segatools 或 PDLoader 的 divaio 路径。",
			Action:   "确认游戏加载器已经指向 divaslider.dll，并在启动游戏前先启动本服务。",
		}
	case inputmethod.MethodJoystickSlider:
		return checkJoystickRequirement()
	default:
		return MethodRequirement{
			MethodID: id,
			OK:       false,
			Severity: "error",
			Title:    "未知输入方式",
			Message:  "当前版本不支持这个输入方式。",
			Action:   "请选择 DLL / TLAC 注入或摇杆模拟滑动。",
		}
	}
}
