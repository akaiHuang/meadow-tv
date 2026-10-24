package main

import (
	"context"
	"encoding/json"
	"github.com/hey-meadow/meadow-code/go/core/provider"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeModel struct{ calls int }

func (f *fakeModel) ChatStream(ctx context.Context, r provider.ChatRequest, h *provider.StreamHooks) (*provider.StreamResult, error) {
	f.calls++
	if f.calls == 1 {
		return &provider.StreamResult{ToolCalls: []provider.ToolCall{{ID: "call-1", Type: "function", Function: provider.FunctionCall{Name: "tv_control", Arguments: `{"action":"volume","percent":30}`}}}}, nil
	}
	return &provider.StreamResult{Content: "模擬電視已回報音量 30%。", FinishReason: "stop"}, nil
}
func TestActionBoundaries(t *testing.T) {
	for _, raw := range []string{`{"action":"shell","command":"rm"}`, `{"action":"volume","percent":101}`, `{"action":"volume","percent":-1}`, `{"action":"volume","percent":1.5}`, `{"action":"open_app","app":"settings"}`, `{"action":"hdmi","percent":40}`, `{"action":"open_app","app":"netflix","url":"http://bad"}`} {
		var args map[string]any
		json.Unmarshal([]byte(raw), &args)
		if _, err := validateAction(args); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
	var a map[string]any
	json.Unmarshal([]byte(`{"action":"volume","percent":30}`), &a)
	if _, e := validateAction(a); e != nil {
		t.Fatal(e)
	}
}
func TestSearchIsTypedAndCannotBecomeArbitraryNavigation(t *testing.T) {
	for _, raw := range []string{`{"action":"search","app":"youtube","query":"太空紀錄片"}`} {
		var args map[string]any
		json.Unmarshal([]byte(raw), &args)
		if _, e := validateAction(args); e != nil {
			t.Fatal(e)
		}
	}
	for _, raw := range []string{`{"action":"search","app":"spotify","query":"台灣通勤第一品牌"}`, `{"action":"search","app":"netflix","query":"test"}`, `{"action":"search","app":"spotify","query":""}`, `{"action":"search","app":"spotify","query":"test","url":"https://evil.invalid"}`} {
		var args map[string]any
		json.Unmarshal([]byte(raw), &args)
		if _, e := validateAction(args); e == nil {
			t.Fatal("invalid search accepted")
		}
	}
}

func TestOriginAndPeerCannotBypassPairing(t *testing.T) {
	b := &bridge{token: strings.Repeat("a", 40), tvHost: "192.168.50.31"}
	r := httptest.NewRequest("GET", "https://localhost/health", nil)
	r.RemoteAddr = "127.0.0.1:10"
	if b.authorized(r) {
		t.Fatal("missing token accepted")
	}
	r.Header.Set("Authorization", "Bearer "+b.token)
	if !b.authorized(r) {
		t.Fatal("paired local request rejected")
	}
	r.Header.Set("Origin", "https://evil.test")
	if b.authorized(r) {
		t.Fatal("browser origin accepted")
	}
	r.Header.Del("Origin")
	r.RemoteAddr = "192.168.50.50:10"
	if b.authorized(r) {
		t.Fatal("unpaired peer accepted")
	}
}
func TestLocalEmulatorNATStillRequiresPairingAndNoBrowserOrigin(t *testing.T) {
	b := &bridge{token: strings.Repeat("a", 40), tvHost: "192.168.50.31"}
	r := httptest.NewRequest("GET", "https://localhost/health", nil)
	r.RemoteAddr = "192.168.50.28:4567"
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.TCPAddr{IP: net.ParseIP("192.168.50.28"), Port: 8766}))
	if b.authorized(r) {
		t.Fatal("NAT bypassed token")
	}
	r.Header.Set("Authorization", "Bearer "+b.token)
	if !b.authorized(r) {
		t.Fatal("paired local NAT rejected")
	}
	r.Header.Set("Origin", "https://example.invalid")
	if b.authorized(r) {
		t.Fatal("Origin bypassed pairing")
	}
	r.Header.Del("Origin")
	r.RemoteAddr = "192.168.50.50:4567"
	if b.authorized(r) {
		t.Fatal("unpaired LAN accepted")
	}
}
func TestHarnessWaitsForActualReceiptAndRejectsReplay(t *testing.T) {
	b := &bridge{model: &fakeModel{}, workspace: t.TempDir(), token: strings.Repeat("a", 40), tvHost: "192.168.50.31", runs: map[string]*run{}}
	call := func(method, path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "https://localhost"+path, strings.NewReader(body))
		r.RemoteAddr = "127.0.0.1:12"
		r.Header.Set("Authorization", "Bearer "+b.token)
		w := httptest.NewRecorder()
		b.ServeHTTP(w, r)
		return w
	}
	w := call("POST", "/chat", `{"text":"音量 30%"}`)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var reply map[string]any
	json.Unmarshal(w.Body.Bytes(), &reply)
	id := reply["id"].(string)
	job := b.get(id)
	var commandID string
	for n := 0; n < 100; n++ {
		job.mu.Lock()
		if job.Pending != nil {
			commandID = job.Pending.ID
		}
		done := job.Done
		job.mu.Unlock()
		if done {
			t.Fatal("finished before TV acknowledgement")
		}
		if commandID != "" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if commandID == "" {
		t.Fatal("tool never delivered")
	}
	if w = call("POST", "/ack/"+id, `{"commandId":"stale","result":{"ok":true}}`); w.Code != 409 {
		t.Fatal("stale ack accepted")
	}
	ack := `{"commandId":"` + commandID + `","result":{"ok":true,"message":"SIMULATED TV volume 30%"}}`
	if w = call("POST", "/ack/"+id, ack); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w = call("POST", "/ack/"+id, ack); w.Code != 409 {
		t.Fatal("replay accepted")
	}
	for n := 0; n < 100; n++ {
		job.mu.Lock()
		done := job.Done
		calls := job.ToolCalls
		job.mu.Unlock()
		if done {
			if calls != 1 {
				t.Fatalf("tool calls %d", calls)
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("harness did not finish")
}

func TestCancelStopsOwnedModelProcessGroup(t *testing.T) {
	dir := t.TempDir()
	binary := filepath.Join(dir, "fake-codex")
	if err := os.WriteFile(binary, []byte("#!/bin/sh\nsleep 30\n"), 0700); err != nil {
		t.Fatal(err)
	}
	model := &codexModel{binary: binary, model: "test", workspace: dir}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := model.ChatStream(ctx, provider.ChatRequest{}, nil)
	if err == nil {
		t.Fatal("cancelled model succeeded")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("model process did not stop promptly")
	}
}
