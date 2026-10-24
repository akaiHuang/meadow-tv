package main

import (
	"context"
	"errors"
	"strings"
	"unicode"

	"github.com/hey-meadow/meadow-code/go/core/tools"
)

type queueTool struct{ run *run }

func (*queueTool) Name() string { return "media_queue" }
func (*queueTool) Description() string {
	return "Manage the paired TV's real podcast shows and ordered podcast/video queue. search_shows(query) discovers any public podcast show; follow_show(showId) saves a returned show; list_shows lists saved shows; unfollow_show(showId) removes a saved show; episodes(showId) returns that show's playable episode IDs. Use the exact returned showId (for example show:apple:123), never a title, URL, or invented ID. Search and show results come from the TV's own cache. Existing search(query) searches playable episodes, search_video(query) searches cached videos, and list returns the queue. add accepts a returned itemId OR itemIds (1..20); play accepts an optional returned itemId OR zero-based index, or no target to resume. remove requires index; move requires index and to (0..49). Each operation accepts only its documented arguments. Podcast audio auto-advances on real completion and supports background playback. External streaming videos require playback confirmation inside their app and do not auto-advance. Wait for the TV's acknowledgement before claiming an action succeeded."
}
func (*queueTool) Kind() tools.Kind       { return tools.KindExecute }
func (*queueTool) Schema() map[string]any { return queueSchema() }

func queueSchema() map[string]any {
	query := map[string]any{"type": "string", "minLength": 1, "maxLength": 100}
	id := map[string]any{"type": "string", "minLength": 1, "maxLength": 100}
	ids := map[string]any{"type": "array", "minItems": 1, "maxItems": 20, "items": id}
	index := map[string]any{"type": "integer", "minimum": 0, "maximum": 49}
	operations := []string{"search", "search_video", "search_shows", "follow_show", "list_shows", "unfollow_show", "episodes", "list", "add", "play", "pause", "stop", "next", "remove", "move", "clear"}
	branch := func(ops []string, fields map[string]any) map[string]any {
		properties := map[string]any{"operation": map[string]any{"type": "string", "enum": ops}}
		required := []string{"operation"}
		for _, key := range []string{"query", "showId", "itemId", "itemIds", "index", "to"} {
			if field, ok := fields[key]; ok {
				properties[key] = field
				required = append(required, key)
			}
		}
		return map[string]any{"type": "object", "additionalProperties": false, "required": required, "properties": properties}
	}
	return map[string]any{
		"type": "object", "additionalProperties": false, "required": []string{"operation"},
		"properties": map[string]any{
			"operation": map[string]any{"type": "string", "enum": operations},
			"query":     query, "showId": id, "itemId": id, "itemIds": ids, "index": index, "to": index,
		},
		"oneOf": []any{
			branch([]string{"search", "search_video", "search_shows"}, map[string]any{"query": query}),
			branch([]string{"follow_show", "unfollow_show", "episodes"}, map[string]any{"showId": id}),
			branch([]string{"list", "list_shows", "pause", "stop", "next", "clear", "play"}, nil),
			branch([]string{"add", "play"}, map[string]any{"itemId": id}),
			branch([]string{"add"}, map[string]any{"itemIds": ids}),
			branch([]string{"play", "remove"}, map[string]any{"index": index}),
			branch([]string{"move"}, map[string]any{"index": index, "to": index}),
		},
	}
}

func (t *queueTool) Execute(ctx context.Context, args map[string]any) tools.Result {
	next := map[string]any{"action": "queue"}
	for k, v := range args {
		if k == "action" {
			err := errors.New("unexpected queue argument")
			return tools.Result{LLMContent: err.Error(), Err: err}
		}
		next[k] = v
	}
	return (&tvTool{run: t.run}).Execute(ctx, next)
}

func queueSearchQuery(value any) (string, error) {
	query, ok := value.(string)
	if !ok || strings.TrimSpace(query) == "" || len([]rune(query)) > 100 || strings.ContainsFunc(query, unicode.IsControl) {
		return "", errors.New("invalid podcast search")
	}
	return strings.TrimSpace(query), nil
}

// IDs are opaque tokens returned by the paired TV, not fetchable locations.
// The TV still checks cache membership; passing this shape check is not proof
// that an arbitrary token names a known show or episode.
func queueID(value any) (string, error) {
	id, ok := value.(string)
	if !ok || len([]rune(id)) < 1 || len([]rune(id)) > 100 || strings.ContainsFunc(id, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) || strings.ContainsAny(id, "/\\?#") {
		return "", errors.New("known ID required, not a URL")
	}
	lower := strings.ToLower(id)
	for _, prefix := range []string{"http:", "https:", "file:", "data:", "javascript:", "ftp:"} {
		if strings.HasPrefix(lower, prefix) {
			return "", errors.New("known ID required, not a URL")
		}
	}
	return id, nil
}

func validateQueue(args map[string]any, c *command) error {
	op, ok := args["operation"].(string)
	if !ok {
		return errors.New("queue operation required")
	}
	c.Operation = op
	allowed := map[string]bool{"action": true, "operation": true}
	number := func(key string) (*int, error) {
		v, ok := args[key].(float64)
		if !ok || v < 0 || v > 49 || float64(int(v)) != v {
			return nil, errors.New("queue index out of range")
		}
		n := int(v)
		return &n, nil
	}
	switch op {
	case "list", "list_shows", "pause", "stop", "next", "clear":
	case "search", "search_video", "search_shows":
		allowed["query"] = true
		query, err := queueSearchQuery(args["query"])
		if err != nil {
			return err
		}
		c.Query = query
	case "follow_show", "unfollow_show", "episodes":
		allowed["showId"] = true
		id, err := queueID(args["showId"])
		if err != nil {
			return err
		}
		c.ShowID = id
	case "add":
		if ids, ok := args["itemIds"]; ok {
			allowed["itemIds"] = true
			list, ok := ids.([]any)
			if !ok || len(list) == 0 || len(list) > 20 {
				return errors.New("invalid batch")
			}
			for _, value := range list {
				id, err := queueID(value)
				if err != nil {
					return err
				}
				c.ItemIDs = append(c.ItemIDs, id)
			}
		} else {
			allowed["itemId"] = true
			id, err := queueID(args["itemId"])
			if err != nil {
				return err
			}
			c.ItemID = id
		}
	case "play":
		if value, ok := args["itemId"]; ok {
			allowed["itemId"] = true
			id, err := queueID(value)
			if err != nil {
				return err
			}
			c.ItemID = id
		}
		if _, ok := args["index"]; ok {
			allowed["index"] = true
			n, err := number("index")
			if err != nil {
				return err
			}
			c.Index = n
		}
		if c.ItemID != "" && c.Index != nil {
			return errors.New("ambiguous play target")
		}
	case "remove", "move":
		allowed["index"] = true
		n, err := number("index")
		if err != nil {
			return err
		}
		c.Index = n
		if op == "move" {
			allowed["to"] = true
			n, err = number("to")
			if err != nil {
				return err
			}
			c.To = n
		}
	default:
		return errors.New("unsupported queue operation")
	}
	for key := range args {
		if !allowed[key] {
			return errors.New("unexpected queue argument")
		}
	}
	return nil
}
