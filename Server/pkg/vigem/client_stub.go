//go:build !windows

package vigem

import "errors"

type Client struct{}

func Open() (*Client, error) {
	return nil, errors.New("ViGEmBus is only available on Windows")
}

func (c *Client) Update(report DS4Report) error {
	return errors.New("ViGEmBus is only available on Windows")
}

func (c *Client) Close() error {
	return nil
}
