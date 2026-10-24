package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
)

// Private signaling only; the bundled Codex owns ChatGPT authentication.
type voiceProxy struct {
	mu           sync.Mutex
	node, script string
	codexBinary  string
	process      *exec.Cmd
	input        io.WriteCloser
	next         int
	pending      map[int]chan voiceResponse
}
type voiceResponse struct {
	ID     int             `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  string          `json:"error"`
}

func (v *voiceProxy) startLocked() error {
	if v.process != nil {
		return nil
	}
	cmd := exec.Command(v.node, v.script)
	if v.codexBinary != "" {
		for _, value := range os.Environ() {
			key := strings.SplitN(value, "=", 2)[0]
			if key != "MEADOW_TV_VOICE_CODEX" && key != "OPENAI_API_KEY" && key != "CODEX_API_KEY" && key != "CODEX_ACCESS_TOKEN" {
				cmd.Env = append(cmd.Env, value)
			}
		}
		cmd.Env = append(cmd.Env, "MEADOW_TV_VOICE_CODEX="+v.codexBinary)
	}
	in, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err = cmd.Start(); err != nil {
		return err
	}
	v.process = cmd
	v.input = in
	v.pending = map[int]chan voiceResponse{}
	go func() {
		scanner := bufio.NewScanner(out)
		scanner.Buffer(make([]byte, 8192), 256*1024)
		for scanner.Scan() {
			var r voiceResponse
			if json.Unmarshal(scanner.Bytes(), &r) != nil {
				continue
			}
			v.mu.Lock()
			ch := v.pending[r.ID]
			delete(v.pending, r.ID)
			v.mu.Unlock()
			if ch != nil {
				ch <- r
			}
		}
		cmd.Wait()
		v.mu.Lock()
		for id, ch := range v.pending {
			ch <- voiceResponse{Error: "Voice bridge ended"}
			delete(v.pending, id)
		}
		if v.process == cmd {
			v.process = nil
		}
		v.mu.Unlock()
	}()
	return nil
}
func (v *voiceProxy) call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	v.mu.Lock()
	if err := v.startLocked(); err != nil {
		v.mu.Unlock()
		return nil, err
	}
	v.next++
	id := v.next
	ch := make(chan voiceResponse, 1)
	v.pending[id] = ch
	data, _ := json.Marshal(map[string]any{"id": id, "method": method, "params": params})
	_, err := v.input.Write(append(data, '\n'))
	v.mu.Unlock()
	if err != nil {
		return nil, err
	}
	select {
	case r := <-ch:
		if r.Error != "" {
			return nil, errors.New(r.Error)
		}
		return r.Result, nil
	case <-ctx.Done():
		v.mu.Lock()
		delete(v.pending, id)
		v.mu.Unlock()
		return nil, ctx.Err()
	}
}
