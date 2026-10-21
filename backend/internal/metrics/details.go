package metrics

import (
	"bufio"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type Disk struct {
	Mount  string `json:"mount"`
	Device string `json:"device"`
	FS     string `json:"fs"`
	Used   uint64 `json:"used"`
	Total  uint64 `json:"total"`
}

type Iface struct {
	Name   string   `json:"name"`
	IPv4   []string `json:"ipv4"`
	Up     bool     `json:"up"`
	RxRate float64  `json:"rxRate"`
	TxRate float64  `json:"txRate"`
}

type Sensor struct {
	Name  string  `json:"name"`
	Label string  `json:"label"`
	Value float64 `json:"value"`
}

type Proc struct {
	PID  int     `json:"pid"`
	Name string  `json:"name"`
	CPU  float64 `json:"cpu"`
	Mem  uint64  `json:"mem"`
}

type Battery struct {
	Percent  int    `json:"percent"`
	Status   string `json:"status"`
	ACOnline bool   `json:"acOnline"`
}

type Details struct {
	CPUModel   string    `json:"cpuModel"`
	CPUMHz     float64   `json:"cpuMHz"`
	Cores      []float64 `json:"cores"`
	Swap       Usage     `json:"swap"`
	Battery    *Battery  `json:"battery"`
	Disks      []Disk    `json:"disks"`
	Interfaces []Iface   `json:"interfaces"`
	Sensors    []Sensor  `json:"sensors"`
	TopCPU     []Proc    `json:"topCpu"`
	TopMem     []Proc    `json:"topMem"`
}

// detailState keeps the previous samples needed to turn counters into rates.
type detailState struct {
	prevCores [][2]uint64
	prevIf    map[string][2]uint64
	prevProc  map[int]uint64
	prevAt    time.Time
	cur       Details
}

// Details returns the latest detail snapshot.
func (c *Collector) Details() Details {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.det.cur
}

func (c *Collector) sampleDetails(now time.Time) {
	d := &c.det
	var out Details

	out.CPUModel, out.CPUMHz = cpuInfo()
	out.Cores, d.prevCores = coreUsage(d.prevCores)

	mem := readMemInfo()
	out.Swap = Usage{Used: mem["SwapTotal"] - mem["SwapFree"], Total: mem["SwapTotal"]}
	out.Battery = readBattery()
	out.Disks = readDisks()
	out.Sensors = readSensors()

	sec := now.Sub(d.prevAt).Seconds()
	out.Interfaces = readIfaces(d, sec)
	out.TopCPU, out.TopMem = readProcs(d, sec)
	d.prevAt = now
	d.cur = out
}

func cpuInfo() (string, float64) {
	f, err := os.Open("/proc/cpuinfo")
	if err != nil {
		return "", 0
	}
	defer f.Close()
	var model string
	var mhz float64
	var n int
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), ":")
		if !ok {
			continue
		}
		switch strings.TrimSpace(k) {
		case "model name":
			if model == "" {
				model = strings.TrimSpace(v)
			}
		case "cpu MHz":
			x, _ := strconv.ParseFloat(strings.TrimSpace(v), 64)
			mhz += x
			n++
		}
	}
	if n > 0 {
		mhz /= float64(n)
	}
	return model, round1(mhz)
}

func coreUsage(prev [][2]uint64) ([]float64, [][2]uint64) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return nil, prev
	}
	defer f.Close()
	var cur [][2]uint64
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, "cpu") || strings.HasPrefix(line, "cpu ") {
			continue
		}
		fields := strings.Fields(line)[1:]
		var idle, total uint64
		for i, fl := range fields {
			if i >= 8 {
				break
			}
			v, _ := strconv.ParseUint(fl, 10, 64)
			total += v
			if i == 3 || i == 4 {
				idle += v
			}
		}
		cur = append(cur, [2]uint64{idle, total})
	}
	out := make([]float64, len(cur))
	for i := range cur {
		if i < len(prev) && cur[i][1] > prev[i][1] {
			out[i] = round1((1 - float64(cur[i][0]-prev[i][0])/float64(cur[i][1]-prev[i][1])) * 100)
		}
	}
	return out, cur
}

func readMemInfo() map[string]uint64 {
	m := map[string]uint64{}
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return m
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		p := strings.Fields(sc.Text())
		if len(p) >= 2 {
			v, _ := strconv.ParseUint(p[1], 10, 64)
			m[strings.TrimSuffix(p[0], ":")] = v * 1024
		}
	}
	return m
}

func readBattery() *Battery {
	bats, _ := filepath.Glob("/sys/class/power_supply/BAT*")
	if len(bats) == 0 {
		return nil
	}
	pct, _ := strconv.Atoi(readTrim(bats[0] + "/capacity"))
	b := &Battery{Percent: pct, Status: readTrim(bats[0] + "/status")}
	acs, _ := filepath.Glob("/sys/class/power_supply/*/online")
	for _, a := range acs {
		if readTrim(a) == "1" {
			b.ACOnline = true
		}
	}
	return b
}

var diskFS = map[string]bool{"ext4": true, "ext3": true, "xfs": true, "btrfs": true, "vfat": true, "ntfs3": true, "ntfs": true, "f2fs": true, "zfs": true, "exfat": true}

func readDisks() []Disk {
	f, err := os.Open("/proc/mounts")
	if err != nil {
		return nil
	}
	defer f.Close()
	seen := map[string]bool{}
	var out []Disk
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		p := strings.Fields(sc.Text())
		if len(p) < 3 || !strings.HasPrefix(p[0], "/dev/") || !diskFS[p[2]] || seen[p[0]] || strings.HasPrefix(p[1], "/snap") {
			continue
		}
		seen[p[0]] = true
		var st syscall.Statfs_t
		if syscall.Statfs(p[1], &st) != nil {
			continue
		}
		total := st.Blocks * uint64(st.Bsize)
		out = append(out, Disk{Mount: p[1], Device: p[0], FS: p[2], Used: total - st.Bavail*uint64(st.Bsize), Total: total})
	}
	return out
}

func readIfaces(d *detailState, sec float64) []Iface {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil
	}
	if d.prevIf == nil {
		d.prevIf = map[string][2]uint64{}
	}
	var out []Iface
	for _, i := range ifs {
		if i.Flags&net.FlagLoopback != 0 || strings.HasPrefix(i.Name, "veth") {
			continue
		}
		rx, tx := readNet(i.Name)
		it := Iface{Name: i.Name, Up: i.Flags&net.FlagUp != 0, IPv4: []string{}}
		if p, ok := d.prevIf[i.Name]; ok && sec > 0 && rx >= p[0] && tx >= p[1] {
			it.RxRate, it.TxRate = float64(rx-p[0])/sec, float64(tx-p[1])/sec
		}
		d.prevIf[i.Name] = [2]uint64{rx, tx}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil {
				it.IPv4 = append(it.IPv4, ipn.IP.String())
			}
		}
		out = append(out, it)
	}
	sort.Slice(out, func(a, b int) bool {
		if out[a].Up != out[b].Up {
			return out[a].Up
		}
		return out[a].Name < out[b].Name
	})
	return out
}

func readSensors() []Sensor {
	dirs, _ := filepath.Glob("/sys/class/hwmon/hwmon*")
	var out []Sensor
	for _, d := range dirs {
		name := readTrim(d + "/name")
		inputs, _ := filepath.Glob(d + "/temp*_input")
		sort.Strings(inputs)
		for _, in := range inputs {
			label := readTrim(strings.TrimSuffix(in, "_input") + "_label")
			if name == "coretemp" && !strings.HasPrefix(label, "Package") {
				continue
			}
			v, err := strconv.ParseFloat(readTrim(in), 64)
			if err != nil || v <= 0 {
				continue
			}
			if label == "" {
				label = strings.TrimSuffix(filepath.Base(in), "_input")
			}
			out = append(out, Sensor{Name: name, Label: label, Value: round1(v / 1000)})
		}
	}
	return out
}

func readProcs(d *detailState, sec float64) (topCPU, topMem []Proc) {
	const clk = 100.0 // USER_HZ on Linux
	entries, err := filepath.Glob("/proc/[0-9]*/stat")
	if err != nil {
		return nil, nil
	}
	cur := make(map[int]uint64, len(entries))
	var all []Proc
	page := uint64(os.Getpagesize())
	for _, e := range entries {
		b, err := os.ReadFile(e)
		if err != nil {
			continue
		}
		s := string(b)
		lp, rp := strings.IndexByte(s, '('), strings.LastIndexByte(s, ')')
		if lp < 0 || rp < lp {
			continue
		}
		pid, _ := strconv.Atoi(s[:lp-1])
		f := strings.Fields(s[rp+2:])
		if len(f) < 22 {
			continue
		}
		ut, _ := strconv.ParseUint(f[11], 10, 64)
		st, _ := strconv.ParseUint(f[12], 10, 64)
		rss, _ := strconv.ParseUint(f[21], 10, 64)
		ticks := ut + st
		cur[pid] = ticks
		p := Proc{PID: pid, Name: s[lp+1 : rp], Mem: rss * page}
		if prev, ok := d.prevProc[pid]; ok && sec > 0 && ticks >= prev {
			p.CPU = round1(float64(ticks-prev) / clk / sec * 100)
		}
		all = append(all, p)
	}
	d.prevProc = cur
	top := func(less func(a, b Proc) bool) []Proc {
		cp := append([]Proc(nil), all...)
		sort.Slice(cp, func(i, j int) bool { return less(cp[i], cp[j]) })
		if len(cp) > 6 {
			cp = cp[:6]
		}
		return cp
	}
	return top(func(a, b Proc) bool { return a.CPU > b.CPU }), top(func(a, b Proc) bool { return a.Mem > b.Mem })
}
