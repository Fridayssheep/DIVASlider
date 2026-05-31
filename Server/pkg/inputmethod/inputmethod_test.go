package inputmethod

import "testing"

func TestDefaultJoystickSliderInputMethod(t *testing.T) {
	cfg := Default(MethodJoystickSlider)
	if !cfg.JoystickSlider || cfg.DLLInjection {
		t.Fatalf("bad joystick slider defaults: %+v", cfg)
	}
}

func TestNormalizeRejectsUnknownInputMethod(t *testing.T) {
	if _, err := Normalize(Default("bad")); err == nil {
		t.Fatal("expected unknown input method error")
	}
}

func TestNormalizeAcceptsPortOnlyAddresses(t *testing.T) {
	cfg := Default(MethodTLAC)
	cfg.HTTPAddr = "52469"
	cfg.UDPAddr = "52468"

	normalized, err := Normalize(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if normalized.HTTPAddr != ":52469" || normalized.UDPAddr != ":52468" {
		t.Fatalf("bad listen addresses: %+v", normalized)
	}
}
