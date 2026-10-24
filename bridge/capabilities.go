package main

import (
	"context"
	"encoding/json"
	"github.com/hey-meadow/meadow-code/go/core/tools"
)

type capabilitiesTool struct{ Available []tools.Tool }

func (*capabilitiesTool) Name() string { return "tv_capabilities" }
func (*capabilitiesTool) Description() string {
	return "Discover the TV tool directory and actual feature limits before choosing a tool."
}
func (*capabilitiesTool) Kind() tools.Kind { return tools.KindReadOnly }
func (*capabilitiesTool) Schema() map[string]any {
	return map[string]any{"type": "object", "properties": map[string]any{}, "additionalProperties": false}
}
func (t *capabilitiesTool) Execute(context.Context, map[string]any) tools.Result {
	items := make([]map[string]any, 0, len(t.Available))
	for _, tool := range t.Available {
		items = append(items, map[string]any{"name": tool.Name(), "description": tool.Description(), "schema": tool.Schema()})
	}
	data, _ := json.Marshal(map[string]any{"tools": items, "discovery": "android_tool_search ranks registered tools using BM25; the model decides the plan", "observe": "android_state reports current capabilities and UI; it is not an app internal back stack", "podcast_background_autoadvance": true, "external_video_autoadvance": false, "arbitrary_urls_or_shell": false, "accessibility_required_for_cross_app_ui": true})
	return tools.Result{LLMContent: string(data)}
}
