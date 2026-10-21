// Package deepseek reports the DeepSeek account balance and the money actually spent.
//
// DeepSeek has no usage API, so spending is derived from balance changes: a sampler records the
// balance whenever it changes, and a drop between two samples counts as spend (a rise as a top-up).
// Token usage comes from a file exported from Hermes (see hermes.go). The API key is read from a
// file and never leaves this process.
package deepseek

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	balanceURL     = "https://api.deepseek.com/user/balance"
	sampleEvery    = 5 * time.Minute
	heartbeatEvery = 6 * time.Hour
	maxSamples     = 50000
)

type sample struct {
	T   int64   `json:"t"`
	CNY float64 `json:"cny"`
}

type Balance struct {
	Currency  string  `json:"currency"`
	Total     float64 `json:"total"`
	Granted   float64 `json:"granted"`
	ToppedUp  float64 `json:"toppedUp"`
	Available bool    `json:"available"`
	FetchedAt int64   `json:"fetchedAt"`
}

type Day struct {
	Date  string  `json:"date"`
	Spend float64 `json:"spend"`
	Topup float64 `json:"topup"`
}

type Spend struct {
	Today      float64  `json:"today"`
	Last7      float64  `json:"last7"`
	Last30     float64  `json:"last30"`
	AvgPerDay  float64  `json:"avgPerDay"` // average over the last 7 days, or since tracking began if shorter
	DaysLeft   *float64 `json:"daysLeft"`  // balance / avg, nil when there is no spend yet
	Topups7    float64  `json:"topups7"`
	TrackedFor float64  `json:"trackedDays"`
}

type Snapshot struct {
	Balance      *Balance `json:"balance"`
	BalanceError string   `json:"balanceError,omitempty"`
	Spend        Spend    `json:"spend"`
	Daily        []Day    `json:"daily"`
	Since        string   `json:"since,omitempty"`
	Samples      int      `json:"samples"`
}

type Client struct {
	key      string
	http     *http.Client
	histPath string

	mu      sync.RWMutex
	bal     *Balance
	balErr  string
	samples []sample
}

// New reads the key from keyFile and loads the recorded history from stateDir.
func New(keyFile, stateDir string) (*Client, error) {
	raw, err := os.ReadFile(keyFile)
	if err != nil {
		return nil, err
	}
	key := strings.TrimSpace(string(raw))
	if key == "" {
		return nil, errors.New("deepseek: key file is empty")
	}
	c := &Client{key: key, http: &http.Client{Timeout: 10 * time.Second}}
	if stateDir != "" {
		c.histPath = filepath.Join(stateDir, "deepseek-balance.jsonl")
		c.load()
	}
	return c, nil
}

func (c *Client) load() {
	f, err := os.Open(c.histPath)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		var s sample
		if json.Unmarshal(sc.Bytes(), &s) == nil && s.T > 0 {
			c.samples = append(c.samples, s)
		}
	}
	if len(c.samples) > maxSamples {
		c.samples = c.samples[len(c.samples)-maxSamples:]
	}
}

func (c *Client) fetch(ctx context.Context) (*Balance, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, balanceURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.key)
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, errors.New("DeepSeek is unreachable")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DeepSeek answered %d", resp.StatusCode)
	}
	var r struct {
		IsAvailable  bool `json:"is_available"`
		BalanceInfos []struct {
			Currency string `json:"currency"`
			Total    string `json:"total_balance"`
			Granted  string `json:"granted_balance"`
			ToppedUp string `json:"topped_up_balance"`
		} `json:"balance_infos"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return nil, errors.New("unexpected DeepSeek response")
	}
	pick := -1
	for i, b := range r.BalanceInfos { // prefer CNY, else the first currency
		if b.Currency == "CNY" {
			pick = i
		}
	}
	if pick < 0 && len(r.BalanceInfos) > 0 {
		pick = 0
	}
	if pick < 0 {
		return nil, errors.New("no balance in the DeepSeek response")
	}
	b := r.BalanceInfos[pick]
	f := func(s string) float64 { v, _ := strconv.ParseFloat(s, 64); return v }
	return &Balance{Currency: b.Currency, Total: f(b.Total), Granted: f(b.Granted), ToppedUp: f(b.ToppedUp),
		Available: r.IsAvailable, FetchedAt: time.Now().Unix()}, nil
}

// Refresh fetches the balance now and records it when it changed.
func (c *Client) Refresh(ctx context.Context) {
	b, err := c.fetch(ctx)
	c.mu.Lock()
	defer c.mu.Unlock()
	if err != nil {
		c.balErr = err.Error()
		return
	}
	c.balErr, c.bal = "", b
	if b.Currency != "CNY" {
		return // history is kept in CNY only
	}
	now := time.Now().Unix()
	if n := len(c.samples); n > 0 {
		last := c.samples[n-1]
		if math.Abs(last.CNY-b.Total) < 0.00005 && time.Duration(now-last.T)*time.Second < heartbeatEvery {
			return
		}
	}
	s := sample{T: now, CNY: b.Total}
	c.samples = append(c.samples, s)
	if len(c.samples) > maxSamples {
		c.samples = c.samples[len(c.samples)-maxSamples:]
	}
	if c.histPath != "" {
		if f, err := os.OpenFile(c.histPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o640); err == nil {
			line, _ := json.Marshal(s)
			f.Write(append(line, '\n'))
			f.Close()
		}
	}
}

// Run samples the balance until ctx ends.
func (c *Client) Run(ctx context.Context) {
	c.Refresh(ctx)
	t := time.NewTicker(sampleEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			c.Refresh(ctx)
		}
	}
}

func dayKey(t time.Time) string { return t.Format("2006-01-02") }

// Snapshot returns the current balance and the spend derived from recorded changes.
func (c *Client) Snapshot() Snapshot {
	c.mu.RLock()
	defer c.mu.RUnlock()
	out := Snapshot{Balance: c.bal, BalanceError: c.balErr, Samples: len(c.samples)}
	now := time.Now()

	spend := map[string]float64{}
	topup := map[string]float64{}
	for i := 1; i < len(c.samples); i++ {
		d := c.samples[i].CNY - c.samples[i-1].CNY
		k := dayKey(time.Unix(c.samples[i].T, 0))
		if d < -0.00005 {
			spend[k] += -d
		} else if d > 0.00005 {
			topup[k] += d
		}
	}
	for i := 13; i >= 0; i-- {
		k := dayKey(now.AddDate(0, 0, -i))
		out.Daily = append(out.Daily, Day{Date: k, Spend: round4(spend[k]), Topup: round4(topup[k])})
	}
	var first time.Time
	if len(c.samples) > 0 {
		first = time.Unix(c.samples[0].T, 0)
		out.Since = first.Format(time.RFC3339)
		out.Spend.TrackedFor = math.Round(now.Sub(first).Hours()/24*10) / 10
	}
	for i := 0; i < 30; i++ {
		k := dayKey(now.AddDate(0, 0, -i))
		out.Spend.Last30 += spend[k]
		if i < 7 {
			out.Spend.Last7 += spend[k]
			out.Spend.Topups7 += topup[k]
		}
	}
	out.Spend.Today = spend[dayKey(now)]
	// Averages only mean something once a few days are recorded: use the real span (at least 1 day,
	// at most 7) and give no runway forecast before 2 days of data.
	span := math.Min(math.Max(out.Spend.TrackedFor, 1), 7)
	out.Spend.AvgPerDay = out.Spend.Last7 / span
	if out.Spend.TrackedFor >= 2 && out.Spend.AvgPerDay > 0.0001 && c.bal != nil {
		d := c.bal.Total / out.Spend.AvgPerDay
		out.Spend.DaysLeft = &d
	}
	out.Spend.Today, out.Spend.Last7, out.Spend.Last30 = round4(out.Spend.Today), round4(out.Spend.Last7), round4(out.Spend.Last30)
	out.Spend.AvgPerDay, out.Spend.Topups7 = round4(out.Spend.AvgPerDay), round4(out.Spend.Topups7)
	return out
}

func round4(f float64) float64 { return math.Round(f*10000) / 10000 }
