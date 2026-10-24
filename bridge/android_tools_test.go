package main

import (
	"context"
	"encoding/json"
	"testing"
)

func TestAndroidCommandsRequireObservedTargetsAndRejectExtraneousFields(t *testing.T) {
	good := []string{
		`{"action":"android","operation":"state"}`, `{"action":"android","operation":"apps"}`,
		`{"action":"android","operation":"open_app","snapshotId":"s1","packageName":"com.google.android.youtube.tv"}`,
		`{"action":"android","operation":"click","snapshotId":"s1","targetId":"s1:n2"}`,
		`{"action":"android","operation":"focus","snapshotId":"s1","targetId":"s1:n2"}`,
		`{"action":"android","operation":"scroll","snapshotId":"s1","targetId":"s1:n2","direction":"forward"}`,
		`{"action":"android","operation":"set_text","snapshotId":"s1","targetId":"s1:n2","text":"科技 podcast"}`,
		`{"action":"android","operation":"home","snapshotId":"s1"}`, `{"action":"android","operation":"back","snapshotId":"s1"}`,
	}
	bad := []string{
		`{"action":"android","operation":"click","targetId":"s1:n2"}`,
		`{"action":"android","operation":"click","snapshotId":"s1"}`,
		`{"action":"android","operation":"state","text":"extra"}`,
		`{"action":"android","operation":"set_text","snapshotId":"s1","targetId":"s1:n2","text":23}`,
		`{"action":"android","operation":"scroll","snapshotId":"s1","targetId":"s1:n2","direction":"diagonal"}`,
		`{"action":"android","operation":"open_app","snapshotId":"s1","packageName":"https://bad.invalid"}`,
		`{"action":"android","operation":"back","snapshotId":"s1","targetId":"n1"}`,
		`{"action":"android","operation":"shell","snapshotId":"s1"}`,
	}
	for _, raw := range good {
		var a map[string]any
		json.Unmarshal([]byte(raw), &a)
		if _, err := validateAction(a); err != nil {
			t.Fatalf("rejected %s: %v", raw, err)
		}
	}
	for _, raw := range bad {
		var a map[string]any
		json.Unmarshal([]byte(raw), &a)
		if _, err := validateAction(a); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}
func TestObservationToolsCannotExecuteWritesOrOverrideDispatcher(t *testing.T) {
	for _, a := range []map[string]any{{"operation": "home"}, {"action": "volume"}, {"operation": "state", "snapshotId": "s1"}} {
		if r := (&androidStateTool{}).Execute(context.Background(), a); r.Err == nil {
			t.Fatal("readonly tool accepted mutation", a)
		}
	}
	if r := (&androidControlTool{}).Execute(context.Background(), map[string]any{"action": "volume", "percent": 30.0}); r.Err == nil {
		t.Fatal("action override accepted")
	}
}
