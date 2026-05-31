package inputmethod

import "fmt"

const (
	MethodTLAC           = "dll-tlac"
	MethodJoystickSlider = "joystick-slider"

	DefaultHTTPAddr          = ":52469"
	DefaultUDPAddr           = ":52468"
	DefaultRefreshMillis int = 1
)

type Config struct {
	ID                  string `json:"id"`
	HTTPAddr            string `json:"httpAddr"`
	UDPAddr             string `json:"udpAddr"`
	HTTPEnabled         bool   `json:"httpEnabled"`
	UDPEnabled          bool   `json:"udpEnabled"`
	DLLInjection        bool   `json:"dllInjection"`
	JoystickSlider      bool   `json:"joystickSlider"`
	OutputRefreshMillis int    `json:"outputRefreshMillis"`
	Debug               bool   `json:"debug"`
}

type Info struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
}

func Default(id string) Config {
	cfg := Config{
		ID:                  MethodTLAC,
		HTTPAddr:            DefaultHTTPAddr,
		UDPAddr:             DefaultUDPAddr,
		HTTPEnabled:         true,
		UDPEnabled:          true,
		DLLInjection:        true,
		OutputRefreshMillis: DefaultRefreshMillis,
	}

	switch id {
	case "", MethodTLAC:
	case MethodJoystickSlider:
		cfg.ID = MethodJoystickSlider
		cfg.DLLInjection = false
		cfg.JoystickSlider = true
	default:
		cfg.ID = id
		cfg.DLLInjection = false
	}

	return cfg
}

func List() []Info {
	return []Info{
		{
			ID:          MethodTLAC,
			Name:        "DLL / TLAC 注入",
			Description: "写入共享内存，给现有 divaio DLL、PDLoader、TLAC 路径使用。",
		},
		{
			ID:          MethodJoystickSlider,
			Name:        "摇杆模拟滑动",
			Description: "通过 ViGEmBus 创建 DS4 虚拟手柄，把滑动方向转成普通摇杆动作。",
		},
	}
}

func Normalize(cfg Config) (Config, error) {
	if cfg.ID == "" {
		cfg.ID = MethodTLAC
	}
	if cfg.HTTPAddr == "" {
		cfg.HTTPAddr = DefaultHTTPAddr
	}
	if cfg.UDPAddr == "" {
		cfg.UDPAddr = DefaultUDPAddr
	}
	cfg.HTTPAddr = normalizeListenAddr(cfg.HTTPAddr)
	cfg.UDPAddr = normalizeListenAddr(cfg.UDPAddr)
	if cfg.OutputRefreshMillis <= 0 {
		cfg.OutputRefreshMillis = DefaultRefreshMillis
	}

	switch cfg.ID {
	case MethodTLAC:
		cfg.DLLInjection = true
		cfg.JoystickSlider = false
	case MethodJoystickSlider:
		cfg.DLLInjection = false
		cfg.JoystickSlider = true
	default:
		return cfg, fmt.Errorf("unknown input method %q", cfg.ID)
	}

	return cfg, nil
}

func normalizeListenAddr(addr string) string {
	if addr == "" || addr[0] == ':' {
		return addr
	}
	for _, ch := range addr {
		if ch < '0' || ch > '9' {
			return addr
		}
	}
	return ":" + addr
}
