package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/hey-meadow/meadow-code/go/core/tools"
)

func TestQueueAcceptsOnlyBoundedTypedOperations(t *testing.T) {
	for _, raw := range []string{
		`{"action":"queue","operation":"list"}`,
		`{"action":"queue","operation":"search","query":"哇賽心理學"}`,
		`{"action":"queue","operation":"add","itemIds":["pod:a","pod:b"]}`,
		`{"action":"queue","operation":"play","itemId":"pod:a"}`,
		`{"action":"queue","operation":"move","index":0,"to":2}`,
	} {
		var args map[string]any
		json.Unmarshal([]byte(raw), &args)
		if _, err := validateAction(args); err != nil {
			t.Fatalf("valid queue operation: %s: %v", raw, err)
		}
	}
	for _, raw := range []string{
		`{"action":"queue","operation":"add","url":"https://example.org/a.mp3"}`,
		`{"action":"queue","operation":"add","itemIds":[]}`,
		`{"action":"queue","operation":"add","itemIds":["a"],"itemId":"b"}`,
		`{"action":"queue","operation":"play","itemId":"a","index":0}`,
		`{"action":"queue","operation":"move","index":0.5,"to":1}`,
		`{"action":"queue","operation":"remove","index":50}`,
		`{"action":"queue","operation":"search","query":" "}`,
		`{"action":"queue","operation":"shell"}`,
		`{"action":"queue","operation":"list","query":"ignored"}`,
	} {
		var args map[string]any
		json.Unmarshal([]byte(raw), &args)
		if _, err := validateAction(args); err == nil {
			t.Fatalf("accepted invalid queue operation: %s", raw)
		}
	}
}

func TestPodcastShowOperationsUseReturnedShowIDs(t *testing.T) {
	for _, raw := range []string{
		`{"action":"queue","operation":"search_shows","query":"百靈果"}`,
		`{"action":"queue","operation":"follow_show","showId":"show:apple:1438198971"}`,
		`{"action":"queue","operation":"episodes","showId":"show:feed:returned-cache-token"}`,
		`{"action":"queue","operation":"unfollow_show","showId":"show:apple:1438198971"}`,
		`{"action":"queue","operation":"list_shows"}`,
	} {
		args := decodeQueueArguments(t, raw)
		command, err := validateAction(args)
		if err != nil {
			t.Fatalf("valid podcast show operation: %s: %v", raw, err)
		}
		if id, ok := args["showId"].(string); ok && command.ShowID != id {
			t.Fatalf("show identity changed: got %q, wanted %q", command.ShowID, id)
		}
	}
}

func TestPodcastShowOperationsRejectMissingOrAmbiguousArguments(t *testing.T) {
	for _, raw := range []string{
		`{"action":"queue","operation":"search_shows"}`,
		`{"action":"queue","operation":"search_shows","query":" "}`,
		`{"action":"queue","operation":"search_shows","query":12}`,
		`{"action":"queue","operation":"search_shows","query":"show","showId":"show:apple:1"}`,
		`{"action":"queue","operation":"search_shows","query":"show","url":"https://example.org/feed"}`,
		`{"action":"queue","operation":"follow_show"}`,
		`{"action":"queue","operation":"follow_show","showId":""}`,
		`{"action":"queue","operation":"follow_show","showId":17}`,
		`{"action":"queue","operation":"follow_show","showId":"show:apple:1","query":"ignored"}`,
		`{"action":"queue","operation":"follow_show","showId":"show:apple:1","itemId":"pod:a"}`,
		`{"action":"queue","operation":"unfollow_show"}`,
		`{"action":"queue","operation":"unfollow_show","showId":null}`,
		`{"action":"queue","operation":"episodes"}`,
		`{"action":"queue","operation":"episodes","query":"a title is not an ID"}`,
		`{"action":"queue","operation":"episodes","showId":"show:apple:1","index":0}`,
		`{"action":"queue","operation":"list_shows","query":"ignored"}`,
		`{"action":"queue","operation":"list_shows","showId":"show:apple:1"}`,
		`{"action":"queue","operation":"list_shows","unknown":true}`,
		`{"action":"queue","operation":17}`,
	} {
		if _, err := validateAction(decodeQueueArguments(t, raw)); err == nil {
			t.Fatalf("accepted invalid podcast show operation: %s", raw)
		}
	}
}

func TestQueueIDsAreBoundedOpaqueTokensNotURLs(t *testing.T) {
	for _, operation := range []string{"follow_show", "unfollow_show", "episodes", "add", "play"} {
		key := "showId"
		if operation == "add" || operation == "play" {
			key = "itemId"
		}
		for _, id := range []string{"x", strings.Repeat("x", 100), "show:apple:123", "pod:cached-episode"} {
			args := map[string]any{"action": "queue", "operation": operation, key: id}
			if _, err := validateAction(args); err != nil {
				t.Fatalf("%s rejected bounded opaque ID %q: %v", operation, id, err)
			}
		}
		for _, id := range []string{"", strings.Repeat("x", 101), " ", "show:apple: 1", "show:apple:\n1", "https://example.org/feed.xml", "HTTP:example.org", "file:/private/feed", "data:audio/mp3,a", "//example.org/feed", "../feed.xml"} {
			args := map[string]any{"action": "queue", "operation": operation, key: id}
			if _, err := validateAction(args); err == nil {
				t.Fatalf("%s accepted invalid or URL ID %q", operation, id)
			}
		}
	}
	if _, err := validateAction(map[string]any{"action": "queue", "operation": "add", "itemIds": []any{"pod:valid", "https://example.org/audio.mp3"}}); err == nil {
		t.Fatal("batch IDs must reject URLs too")
	}
}

func TestPodcastQueriesAreTypedAndBoundedInUnicodeCharacters(t *testing.T) {
	for _, operation := range []string{"search", "search_video", "search_shows"} {
		for _, query := range []string{strings.Repeat("台", 100), "  台灣通勤第一品牌  "} {
			command, err := validateAction(map[string]any{"action": "queue", "operation": operation, "query": query})
			if err != nil {
				t.Fatalf("valid %s query: %v", operation, err)
			}
			if command.Query != strings.TrimSpace(query) {
				t.Fatal("query must retain its content without leading/trailing spaces")
			}
		}
		for _, query := range []any{strings.Repeat("台", 101), "\t", "valid\nquery", "valid\x00query", true, nil} {
			if _, err := validateAction(map[string]any{"action": "queue", "operation": operation, "query": query}); err == nil {
				t.Fatalf("%s accepted invalid query %#v", operation, query)
			}
		}
	}
}

func TestPodcastSearchDelegatesToTVShowCacheAndReturnsActualReceipt(t *testing.T) {
	job := &run{Ack: make(chan map[string]any, 1)}
	tool := &podcastTool{run: job}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	result := make(chan tools.Result, 1)
	go func() { result <- tool.Execute(ctx, map[string]any{"query": "  台灣通勤第一品牌  "}) }()

	var pending command
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for pending.ID == "" {
		job.mu.Lock()
		if job.Pending != nil {
			pending = *job.Pending
		}
		job.mu.Unlock()
		if pending.ID == "" {
			select {
			case <-ticker.C:
			case <-ctx.Done():
				t.Fatal("podcast search did not create a paired TV command")
			}
		}
	}
	if pending.Action != "queue" || pending.Operation != "search_shows" || pending.Query != "台灣通勤第一品牌" {
		t.Fatalf("unexpected podcast command: %#v", pending)
	}
	select {
	case <-result:
		t.Fatal("search must wait for the TV receipt, not return a separate directory result")
	default:
	}
	receipt := map[string]any{"ok": true, "operation": "search_shows", "shows": []any{map[string]any{"showId": "show:apple:123", "title": "台灣通勤第一品牌"}}}
	job.Ack <- receipt
	select {
	case actual := <-result:
		if actual.Err != nil {
			t.Fatal(actual.Err)
		}
		var decoded map[string]any
		if err := json.Unmarshal([]byte(actual.LLMContent), &decoded); err != nil {
			t.Fatal(err)
		}
		if decoded["operation"] != "search_shows" || decoded["shows"].([]any)[0].(map[string]any)["showId"] != "show:apple:123" {
			t.Fatalf("TV result lost real show identity: %s", actual.LLMContent)
		}
	case <-ctx.Done():
		t.Fatal("podcast search did not return the TV acknowledgement")
	}
	job.mu.Lock()
	defer job.mu.Unlock()
	if job.Pending != nil {
		t.Fatal("acknowledged command must be removed")
	}
}

func TestPodcastSearchRejectsInvalidArgumentsBeforeAnyTVAction(t *testing.T) {
	tool := &podcastTool{}
	for _, args := range []map[string]any{
		{}, {"query": ""}, {"query": " "}, {"query": 12}, {"query": "valid", "showId": "show:apple:1"}, {"query": "valid", "url": "https://example.org/feed"},
	} {
		if result := tool.Execute(context.Background(), args); result.Err == nil {
			t.Fatalf("accepted invalid podcast search: %#v", args)
		}
	}
	if result := tool.Execute(context.Background(), map[string]any{"query": "valid"}); result.Err == nil || !strings.Contains(result.LLMContent, "unavailable") {
		t.Fatal("unpaired search must fail without inventing directory results")
	}
}

func TestMediaQueueCannotOverrideItsTVAction(t *testing.T) {
	tool := &queueTool{}
	if result := tool.Execute(context.Background(), map[string]any{"action": "inputs"}); result.Err == nil {
		t.Fatal("media_queue must not accept an unrelated TV action")
	}
}

func decodeQueueArguments(t *testing.T, raw string) map[string]any {
	t.Helper()
	var args map[string]any
	if err := json.Unmarshal([]byte(raw), &args); err != nil {
		t.Fatal(err)
	}
	return args
}
