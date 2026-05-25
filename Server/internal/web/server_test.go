package web

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"divaslider-server/internal/shm"
	"divaslider-server/internal/state"
)

func TestStatusEncodesLEDsAsNumberArrays(t *testing.T) {
	buffer, err := shm.Open()
	if err != nil {
		t.Fatal(err)
	}
	defer buffer.Close()

	rgb := make([]byte, shm.SliderLEDSize)
	rgb[45] = 11
	rgb[46] = 22
	rgb[47] = 33
	buffer.WriteSliderLEDs(rgb)

	server := &Server{State: state.NewManager(), Buffer: buffer}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/status", nil)
	server.handleStatus(recorder, request)

	var response struct {
		LEDs struct {
			Slider []int `json:"slider"`
		} `json:"leds"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}

	if len(response.LEDs.Slider) != shm.SliderLEDSize {
		t.Fatalf("bad slider LED length: %d", len(response.LEDs.Slider))
	}
	if response.LEDs.Slider[45] != 11 || response.LEDs.Slider[46] != 22 || response.LEDs.Slider[47] != 33 {
		t.Fatalf("bad slider LED values: %+v", response.LEDs.Slider[45:48])
	}
}
