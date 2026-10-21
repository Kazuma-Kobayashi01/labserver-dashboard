// Package metrics samples host statistics from /proc, /sys and statfs.
package metrics

import (
	"bufio"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const historyLen = 300

type Usage struct {
	Used  uint64 `json:"used"`
	Total uint64 `json:"total"`
}

type Network struct {
	Interface string  `json:"interface"`
	RxRate    float64 `json:"rxRate"` // bytes/s
	TxRate    float64 `json:"txRate"`
	RxTotal   uint64  `json:"rxTotal"`
	TxTotal   uint64  `json:"txTotal"`
}

type System struct {
	Hostname    string    `json:"hostname"`
	OS          string    `json:"os"`
	Kernel      string    `json:"kernel"`
	Uptime      float64   `json:"uptime"`
	CPUUsage    float64   `json:"cpuUsage"`
	CPUCores    int       `json:"cpuCores"`
	Load        []float64 `json:"load"`
	Memory      Usage     `json:"memory"`
	Storage     Usage     `json:"storage"`
	Network     Network   `json:"network"`
	Temperature *float64  `json:"temperature"`
	Time        int64     `json:"time"`
}

type History struct {
	CPU    []float64 `json:"cpu"`
	Memory []float64 `json:"memory"`
	RxRate []float64 `json:"rxRate"`
	TxRate []float64 `json:"txRate"`
}

type Collector struct {
	mu      sync.RWMutex
	netIf   string
	cur     System
	hist    History
	prevCPU [2]uint64 // idle, total
	prevRx  uint64
	prevTx  uint64
	prevAt  time.Time
	storage Usage
	storAt  time.Time
	static  struct{ hostname, os, kernel string }
	det     detailState
	cores   int
}

// New creates a collector. netIf may be empty to auto-detect the default-route interface.
func New(netIf string) *Collector {
	c := &Collector{netIf: netIf}
	c.static.hostname, _ = os.Hostname()
	c.static.os = prettyOS()
	c.static.kernel = readTrim("/proc/sys/kernel/osrelease")
	c.cores = countCores()
	if c.netIf == "" {
		c.netIf = defaultIface()
	}
	c.sample()
	return c
}

func (c *Collector) Run(interval time.Duration) {
	t := time.NewTicker(interval)
	for range t.C {
		c.sample()
	}
}

func (c *Collector) Snapshot() System {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.cur
}

func (c *Collector) History() History {
	c.mu.RLock()
	defer c.mu.RUnlock()
	cp := func(s []float64) []float64 { return append([]float64(nil), s...) }
	return History{CPU: cp(c.hist.CPU), Memory: cp(c.hist.Memory), RxRate: cp(c.hist.RxRate), TxRate: cp(c.hist.TxRate)}
}

func push(s []float64, v float64) []float64 {
	s = append(s, v)
	if len(s) > historyLen {
		s = s[len(s)-historyLen:]
	}
	return s
}

func (c *Collector) sample() {
	now := time.Now()

	// CPU
	idle, total := readCPU()
	cpu := c.cur.CPUUsage
	if c.prevCPU[1] != 0 && total > c.prevCPU[1] {
		dt := float64(total - c.prevCPU[1])
		di := float64(idle - c.prevCPU[0])
		cpu = (1 - di/dt) * 100
	}
	c.prevCPU = [2]uint64{idle, total}

	// Memory
	mem := readMem()

	// Network
	rx, tx := readNet(c.netIf)
	var rxRate, txRate float64
	if !c.prevAt.IsZero() {
		sec := now.Sub(c.prevAt).Seconds()
		if sec > 0 && rx >= c.prevRx && tx >= c.prevTx {
			rxRate = float64(rx-c.prevRx) / sec
			txRate = float64(tx-c.prevTx) / sec
		}
	}
	c.prevRx, c.prevTx, c.prevAt = rx, tx, now

	// Storage (cheap, but no need every 2s)
	if now.Sub(c.storAt) > 30*time.Second {
		var st syscall.Statfs_t
		if err := syscall.Statfs("/", &st); err == nil {
			total := st.Blocks * uint64(st.Bsize)
			c.storage = Usage{Used: total - st.Bavail*uint64(st.Bsize), Total: total}
		}
		c.storAt = now
	}

	s := System{
		Hostname:    c.static.hostname,
		OS:          c.static.os,
		Kernel:      c.static.kernel,
		Uptime:      readUptime(),
		CPUUsage:    round1(cpu),
		CPUCores:    c.cores,
		Load:        readLoad(),
		Memory:      mem,
		Storage:     c.storage,
		Network:     Network{Interface: c.netIf, RxRate: rxRate, TxRate: txRate, RxTotal: rx, TxTotal: tx},
		Temperature: readTemp(),
		Time:        now.Unix(),
	}

	c.mu.Lock()
	c.sampleDetails(now)
	c.cur = s
	c.hist.CPU = push(c.hist.CPU, s.CPUUsage)
	if mem.Total > 0 {
		c.hist.Memory = push(c.hist.Memory, round1(float64(mem.Used)/float64(mem.Total)*100))
	}
	c.hist.RxRate = push(c.hist.RxRate, rxRate)
	c.hist.TxRate = push(c.hist.TxRate, txRate)
	c.mu.Unlock()
}

func round1(f float64) float64 { return float64(int(f*10+0.5)) / 10 }

func readTrim(p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func prettyOS() string {
	f, err := os.Open("/etc/os-release")
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if v, ok := strings.CutPrefix(sc.Text(), "PRETTY_NAME="); ok {
			return strings.Trim(v, `"`)
		}
	}
	return ""
}

func countCores() int {
	f, err := os.Open("/proc/cpuinfo")
	if err != nil {
		return 0
	}
	defer f.Close()
	n := 0
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if strings.HasPrefix(sc.Text(), "processor") {
			n++
		}
	}
	return n
}

func readCPU() (idle, total uint64) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	if sc.Scan() {
		fields := strings.Fields(sc.Text())[1:]
		for i, fl := range fields {
			v, _ := strconv.ParseUint(fl, 10, 64)
			if i >= 8 { // guest times are already counted in user/nice
				break
			}
			total += v
			if i == 3 || i == 4 { // idle + iowait
				idle += v
			}
		}
	}
	return
}

func readMem() Usage {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return Usage{}
	}
	defer f.Close()
	var total, avail uint64
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		parts := strings.Fields(sc.Text())
		if len(parts) < 2 {
			continue
		}
		v, _ := strconv.ParseUint(parts[1], 10, 64)
		switch parts[0] {
		case "MemTotal:":
			total = v * 1024
		case "MemAvailable:":
			avail = v * 1024
		}
	}
	return Usage{Used: total - avail, Total: total}
}

func readUptime() float64 {
	parts := strings.Fields(readTrim("/proc/uptime"))
	if len(parts) == 0 {
		return 0
	}
	v, _ := strconv.ParseFloat(parts[0], 64)
	return v
}

func readLoad() []float64 {
	parts := strings.Fields(readTrim("/proc/loadavg"))
	out := make([]float64, 0, 3)
	for i := 0; i < 3 && i < len(parts); i++ {
		v, _ := strconv.ParseFloat(parts[i], 64)
		out = append(out, v)
	}
	return out
}

func defaultIface() string {
	f, err := os.Open("/proc/net/route")
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Scan()
	best, bestMetric := "", uint64(1<<62)
	for sc.Scan() {
		p := strings.Fields(sc.Text())
		if len(p) < 7 || p[1] != "00000000" {
			continue
		}
		m, _ := strconv.ParseUint(p[6], 10, 64)
		if m < bestMetric {
			best, bestMetric = p[0], m
		}
	}
	return best
}

func readNet(iface string) (rx, tx uint64) {
	base := "/sys/class/net/" + iface + "/statistics/"
	rx, _ = strconv.ParseUint(readTrim(base+"rx_bytes"), 10, 64)
	tx, _ = strconv.ParseUint(readTrim(base+"tx_bytes"), 10, 64)
	return
}

// readTemp returns the CPU package temperature in °C if a known sensor is present.
func readTemp() *float64 {
	dirs, _ := filepath.Glob("/sys/class/hwmon/hwmon*")
	for _, d := range dirs {
		name := readTrim(d + "/name")
		if name != "coretemp" && name != "k10temp" && name != "zenpower" {
			continue
		}
		inputs, _ := filepath.Glob(d + "/temp*_input")
		var best float64
		found := false
		for _, in := range inputs {
			label := readTrim(strings.TrimSuffix(in, "_input") + "_label")
			if label != "" && !strings.HasPrefix(label, "Package") && !strings.HasPrefix(label, "Tctl") && !strings.HasPrefix(label, "Tdie") {
				continue
			}
			v, err := strconv.ParseFloat(readTrim(in), 64)
			if err == nil && (!found || v > best) {
				best, found = v/1000, true
			}
		}
		if found {
			r := round1(best)
			return &r
		}
	}
	return nil
}
