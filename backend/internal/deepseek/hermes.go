package deepseek

import (
	"encoding/json"
	"os"
	"sort"
	"time"
)

// Row is one (day, profile, provider, model) line written by the Hermes usage exporter.
type Row struct {
	Day       string  `json:"day"`
	Profile   string  `json:"profile"`
	Provider  string  `json:"provider"`
	Model     string  `json:"model"`
	Calls     int64   `json:"calls"`
	Input     int64   `json:"input"`
	Output    int64   `json:"output"`
	CacheRead int64   `json:"cacheRead"`
	Reasoning int64   `json:"reasoning"`
	CostUSD   float64 `json:"costUsd"`
}

type Totals struct {
	Calls     int64   `json:"calls"`
	Input     int64   `json:"input"`
	Output    int64   `json:"output"`
	CacheRead int64   `json:"cacheRead"`
	CostUSD   float64 `json:"costUsd"`
}

type ModelUse struct {
	Profile string `json:"profile"`
	Model   string `json:"model"`
	Totals
}

type TokenDay struct {
	Date      string `json:"date"`
	Input     int64  `json:"input"`
	Output    int64  `json:"output"`
	CacheRead int64  `json:"cacheRead"`
	Calls     int64  `json:"calls"`
}

type HermesUsage struct {
	UpdatedAt int64      `json:"updatedAt"`
	Stale     bool       `json:"stale"`
	Today     Totals     `json:"today"`
	Last7     Totals     `json:"last7"`
	Last30    Totals     `json:"last30"`
	ByModel   []ModelUse `json:"byModel"`
	Daily     []TokenDay `json:"daily"`
}

func (t *Totals) add(r Row) {
	t.Calls += r.Calls
	t.Input += r.Input
	t.Output += r.Output
	t.CacheRead += r.CacheRead
	t.CostUSD += r.CostUSD
}

// ReadHermes aggregates the exported file. Only rows whose provider is "deepseek" are counted.
// It returns nil when the file does not exist yet.
func ReadHermes(path string) *HermesUsage {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var f struct {
		GeneratedAt int64 `json:"generatedAt"`
		Rows        []Row `json:"rows"`
	}
	if json.Unmarshal(raw, &f) != nil {
		return nil
	}
	now := time.Now()
	out := &HermesUsage{UpdatedAt: f.GeneratedAt, Stale: now.Unix()-f.GeneratedAt > 900}
	today := now.Format("2006-01-02")
	d7 := now.AddDate(0, 0, -6).Format("2006-01-02")
	d30 := now.AddDate(0, 0, -29).Format("2006-01-02")
	byModel := map[string]*ModelUse{}
	byDay := map[string]*TokenDay{}
	for _, r := range f.Rows {
		if r.Provider != "deepseek" || r.Day < d30 {
			continue
		}
		out.Last30.add(r)
		if r.Day >= d7 {
			out.Last7.add(r)
		}
		if r.Day == today {
			out.Today.add(r)
		}
		k := r.Profile + "\x00" + r.Model
		m := byModel[k]
		if m == nil {
			m = &ModelUse{Profile: r.Profile, Model: r.Model}
			byModel[k] = m
		}
		m.add(r)
		d := byDay[r.Day]
		if d == nil {
			d = &TokenDay{Date: r.Day}
			byDay[r.Day] = d
		}
		d.Input += r.Input
		d.Output += r.Output
		d.CacheRead += r.CacheRead
		d.Calls += r.Calls
	}
	for _, m := range byModel {
		out.ByModel = append(out.ByModel, *m)
	}
	sort.Slice(out.ByModel, func(i, j int) bool {
		return out.ByModel[i].Input+out.ByModel[i].Output > out.ByModel[j].Input+out.ByModel[j].Output
	})
	for i := 13; i >= 0; i-- {
		k := now.AddDate(0, 0, -i).Format("2006-01-02")
		if d := byDay[k]; d != nil {
			out.Daily = append(out.Daily, *d)
		} else {
			out.Daily = append(out.Daily, TokenDay{Date: k})
		}
	}
	return out
}
