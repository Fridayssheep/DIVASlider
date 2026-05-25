//go:build !windows

package shm

func Open() (*Buffer, error) {
	return &Buffer{data: make([]byte, Size)}, nil
}
