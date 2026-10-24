package main

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func catalogFixture(provider string, id int, at time.Time) map[string]any {
	item := map[string]any{"id": id, "title": fmt.Sprintf("台灣目錄測試%d", id), "provider": provider, "region": "TW", "locale": "zh-TW", "verifiedAt": at.UnixMilli(), "poster": "https://is1-ssl.mzstatic.com/image/poster.jpg", "backdrop": "https://is1-ssl.mzstatic.com/image/hero.jpg", "availability": "listed_tw"}
	switch provider {
	case "netflix":
		item["source"] = "https://www.netflix.com/tw/browse/genre/83"
		item["official"] = fmt.Sprintf("https://www.netflix.com/tw/title/%d", 80000000+id)
	case "apple":
		item["source"] = "https://tv.apple.com/tw"
		item["official"] = fmt.Sprintf("https://tv.apple.com/tw/show/test/umc.%d", id)
	case "hbo":
		item["source"] = "https://www.hbomax.com/tw/zh/series"
		item["official"] = fmt.Sprintf("https://www.hbomax.com/tw/zh/show/title-%d", id)
		item["availability"] = "offering_active_tw"
		item["availabilityStarts"] = at.Add(-time.Hour).Format(time.RFC3339)
		item["availabilityEnds"] = at.Add(24 * time.Hour).Format(time.RFC3339)
	}
	return item
}
func catalogBytes(items []map[string]any) []byte {
	data, _ := json.Marshal(map[string]any{"items": items})
	return data
}
func TestTaiwanCatalogRejectsLegacyAndOtherRegions(t *testing.T) {
	now := time.Now()
	for _, change := range []map[string]any{{"region": "US"}, {"locale": "en-US"}, {"source": "https://www.tvmaze.com/shows/1"}, {"official": "https://www.netflix.com/title/80000001"}, {"source": "https://user@www.netflix.com/tw/browse/genre/83"}, {"verifiedAt": now.Add(time.Hour).UnixMilli()}} {
		item := catalogFixture("netflix", 1, now)
		for key, value := range change {
			item[key] = value
		}
		if len(readTaiwanCatalog(catalogBytes([]map[string]any{item}), now)) != 0 {
			t.Fatalf("accepted invalid Taiwan proof: %v", change)
		}
	}
}
func TestTaiwanCatalogRejectsExpiredOrUpcomingHbo(t *testing.T) {
	now := time.Now()
	item := catalogFixture("hbo", 1, now)
	if len(readTaiwanCatalog(catalogBytes([]map[string]any{item}), now)) != 1 {
		t.Fatal("valid offering rejected")
	}
	item["availabilityEnds"] = now.Add(-time.Minute).Format(time.RFC3339)
	if len(readTaiwanCatalog(catalogBytes([]map[string]any{item}), now)) != 0 {
		t.Fatal("expired offering accepted")
	}
	item = catalogFixture("hbo", 1, now)
	item["availabilityStarts"] = now.Add(time.Hour).Format(time.RFC3339)
	if len(readTaiwanCatalog(catalogBytes([]map[string]any{item}), now)) != 0 {
		t.Fatal("upcoming offering accepted")
	}
}
func TestTaiwanCatalogMergesProvidersAndPreservesEvidence(t *testing.T) {
	now := time.Now()
	seed := []map[string]any{}
	for _, provider := range []string{"netflix", "hbo", "apple"} {
		for n := 1; n <= 20; n++ {
			seed = append(seed, catalogFixture(provider, n, now.Add(-time.Hour)))
		}
	}
	incoming := []map[string]any{}
	for n := 100; n < 124; n++ {
		incoming = append(incoming, catalogFixture("netflix", n, now))
	}
	incoming = append(incoming, catalogFixture("hbo", 999, now))
	data, stale := mergeTaiwanCatalog(catalogBytes(seed), catalogBytes(incoming), now)
	if stale {
		t.Fatal("complete fresh selection marked stale")
	}
	items := readTaiwanCatalog(data, now)
	counts := map[string]int{}
	for _, item := range items {
		counts[item.Provider]++
		if item.Region != "TW" || item.Locale != "zh-TW" {
			t.Fatal("evidence lost")
		}
	}
	if counts["netflix"] != 24 || counts["hbo"] != 21 || counts["apple"] != 20 {
		t.Fatalf("provider overwritten: %v", counts)
	}
}
func TestPublicCatalogServesVerifiedSeedInsteadOfLegacyCache(t *testing.T) {
	now := time.Now()
	folder := t.TempDir()
	seed := []map[string]any{}
	for _, provider := range []string{"netflix", "hbo", "apple"} {
		for n := 1; n <= 16; n++ {
			seed = append(seed, catalogFixture(provider, n, now))
		}
	}
	seedPath := filepath.Join(folder, "seed.json")
	cachePath := filepath.Join(folder, "cache.json")
	if err := os.WriteFile(seedPath, catalogBytes(seed), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cachePath, []byte(`{"items":[{"provider":"hbo","title":"US schedule"}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	catalog := &publicCatalog{path: cachePath, seed: seedPath, attempt: now}
	response := httptest.NewRecorder()
	catalog.serve(response)
	if response.Code != 200 {
		t.Fatalf("status %d", response.Code)
	}
	if len(readTaiwanCatalog(response.Body.Bytes(), now)) != 48 {
		t.Fatal("valid seed was replaced by invalid cache")
	}
}
func TestPublicCatalogHasNoUsFallbackWhenNoVerifiedSnapshotExists(t *testing.T) {
	folder := t.TempDir()
	legacy := filepath.Join(folder, "legacy.json")
	if err := os.WriteFile(legacy, []byte(`{"items":[{"provider":"netflix","source":"https://www.tvmaze.com/shows/1"}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	catalog := &publicCatalog{path: legacy, seed: legacy, attempt: time.Now()}
	response := httptest.NewRecorder()
	catalog.serve(response)
	if response.Code != 503 {
		t.Fatalf("unverified catalog served with status %d", response.Code)
	}
}
