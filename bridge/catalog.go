package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Fixed public-page updater, never controlled by model arguments. Serves verified TW last-good data.
type publicCatalog struct {
	mu                 sync.Mutex
	path, seed, script string
	loading            bool
	attempt            time.Time
}
type taiwanCatalogItem struct {
	raw          json.RawMessage
	Provider     string `json:"provider"`
	Region       string `json:"region"`
	Locale       string `json:"locale"`
	Availability string `json:"availability"`
	Source       string `json:"source"`
	Official     string `json:"official"`
	Title        string `json:"title"`
	Poster       string `json:"poster"`
	Backdrop     string `json:"backdrop"`
	VerifiedAt   int64  `json:"verifiedAt"`
	Starts       string `json:"availabilityStarts"`
	Ends         string `json:"availabilityEnds"`
}

func catalogURL(raw string) *url.URL {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil || parsed.Port() != "" {
		return nil
	}
	return parsed
}
func catalogArtwork(raw string) bool {
	parsed := catalogURL(raw)
	if parsed == nil {
		return false
	}
	host := parsed.Hostname()
	return strings.HasSuffix(host, ".mzstatic.com") || strings.HasSuffix(host, ".nflxso.net") || strings.HasSuffix(host, ".nflximg.net") || host == "beam-images.warnermediacdn.com"
}
func (item taiwanCatalogItem) valid(now time.Time) bool {
	if item.Region != "TW" || item.Locale != "zh-TW" || item.VerifiedAt <= 0 || item.VerifiedAt > now.Add(5*time.Minute).UnixMilli() || item.Title == "" || !catalogArtwork(item.Poster) || !catalogArtwork(item.Backdrop) {
		return false
	}
	source, official := catalogURL(item.Source), catalogURL(item.Official)
	if source == nil || official == nil {
		return false
	}
	switch item.Provider {
	case "netflix":
		if source.Hostname() != "www.netflix.com" || !strings.HasPrefix(source.Path, "/tw/") || official.Hostname() != "www.netflix.com" || !strings.HasPrefix(official.Path, "/tw/title/") || item.Availability != "listed_tw" {
			return false
		}
		identifier := strings.TrimPrefix(official.Path, "/tw/title/")
		id, err := strconv.ParseInt(identifier, 10, 64)
		return err == nil && id > 0
	case "apple":
		return source.Hostname() == "tv.apple.com" && (source.Path == "/tw" || strings.HasPrefix(source.Path, "/tw/")) && official.Hostname() == "tv.apple.com" && (strings.HasPrefix(official.Path, "/tw/show/") || strings.HasPrefix(official.Path, "/tw/movie/")) && item.Availability == "listed_tw"
	case "hbo":
		start, startErr := time.Parse(time.RFC3339, item.Starts)
		end, endErr := time.Parse(time.RFC3339, item.Ends)
		return source.Hostname() == "www.hbomax.com" && (source.Path == "/tw/zh" || strings.HasPrefix(source.Path, "/tw/zh/")) && official.Hostname() == "www.hbomax.com" && (strings.HasPrefix(official.Path, "/tw/zh/show/") || strings.HasPrefix(official.Path, "/tw/zh/movie/")) && item.Availability == "offering_active_tw" && startErr == nil && endErr == nil && !now.Before(start) && now.Before(end)
	}
	return false
}
func readTaiwanCatalog(data []byte, now time.Time) []taiwanCatalogItem {
	var envelope struct {
		Items []json.RawMessage `json:"items"`
	}
	if len(data) > 1024*1024 || json.Unmarshal(data, &envelope) != nil {
		return nil
	}
	items := []taiwanCatalogItem{}
	for _, raw := range envelope.Items {
		var item taiwanCatalogItem
		if json.Unmarshal(raw, &item) == nil && item.valid(now) {
			item.raw = raw
			items = append(items, item)
		}
	}
	return items
}

// Provider-level merging prevents an incomplete refresh from wiping another platform's TW selection.
func mergeTaiwanCatalog(seed, cached []byte, now time.Time) ([]byte, bool) {
	baseline, incoming := readTaiwanCatalog(seed, now), readTaiwanCatalog(cached, now)
	selected := []json.RawMessage{}
	latest := int64(0)
	needsRefresh := false
	for _, provider := range []string{"netflix", "hbo", "apple"} {
		old, fresh := []taiwanCatalogItem{}, []taiwanCatalogItem{}
		oldAt, freshAt := int64(0), int64(0)
		for _, item := range baseline {
			if item.Provider == provider {
				old = append(old, item)
				if item.VerifiedAt > oldAt {
					oldAt = item.VerifiedAt
				}
			}
		}
		for _, item := range incoming {
			if item.Provider == provider {
				fresh = append(fresh, item)
				if item.VerifiedAt > freshAt {
					freshAt = item.VerifiedAt
				}
			}
		}
		candidates := []taiwanCatalogItem{}
		if freshAt >= oldAt {
			candidates = append(candidates, fresh...)
			if len(fresh) < 16 {
				candidates = append(candidates, old...)
			}
		} else {
			candidates = append(candidates, old...)
			if len(old) < 16 {
				candidates = append(candidates, fresh...)
			}
		}
		seen := map[string]bool{}
		count := 0
		providerAt := int64(0)
		for _, item := range candidates {
			if !seen[item.Official] {
				seen[item.Official] = true
				selected = append(selected, item.raw)
				count++
				if item.VerifiedAt > providerAt {
					providerAt = item.VerifiedAt
				}
				if item.VerifiedAt > latest {
					latest = item.VerifiedAt
				}
				if count == 24 {
					break
				}
			}
		}
		if count < 16 || now.Sub(time.UnixMilli(providerAt)) > 6*time.Hour {
			needsRefresh = true
		}
	}
	if len(selected) == 0 {
		return nil, true
	}
	data, _ := json.Marshal(map[string]any{"schemaVersion": 2, "region": "TW", "locale": "zh-TW", "fetchedAt": latest, "items": selected})
	return data, needsRefresh
}
func (c *publicCatalog) serve(w http.ResponseWriter) {
	cached, _ := os.ReadFile(c.path)
	seed, _ := os.ReadFile(c.seed)
	data, stale := mergeTaiwanCatalog(seed, cached, time.Now())
	if len(data) == 0 {
		http.Error(w, "Verified Taiwan catalog unavailable", 503)
		return
	}
	c.mu.Lock()
	if stale && !c.loading && time.Since(c.attempt) > 30*time.Minute {
		c.loading = true
		c.attempt = time.Now()
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 75*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, "/usr/bin/python3", c.script, c.path)
			cmd.Stdout = io.Discard
			cmd.Stderr = io.Discard
			_ = cmd.Run()
			_ = os.Chmod(c.path, 0600)
			c.mu.Lock()
			c.loading = false
			c.mu.Unlock()
		}()
	}
	c.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(data)
}
