// sshsocks: klien SSH kecil yang membuka SOCKS5 lokal (setara "ssh -D").
// Lapisan sambungan (opsional, bisa digabung):
//   -connect  : alamat TCP yang benar-benar dituju (mis. 127.0.0.1:10899 untuk SlowDNS)
//   -proxy    : HTTP proxy jarak jauh [user:pass@]host:port
//   -tls/-sni : bungkus dengan TLS memakai SNI tertentu
//   -payload-file : kirim payload HTTP kustom sebelum SSH dimulai
// Password dibaca dari environment SSH_PASS (tidak lewat argumen proses).
package main

import (
	"bufio"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
)

var fwdErrs int32

type bufConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufConn) Read(p []byte) (int, error) { return c.r.Read(p) }

var (
	fHost      = flag.String("host", "", "alamat SSH host:port")
	fUser      = flag.String("user", "", "nama pengguna SSH")
	fListen    = flag.String("listen", "127.0.0.1:10898", "alamat SOCKS5 lokal")
	fConnect   = flag.String("connect", "", "timpa alamat TCP tujuan (mis. 127.0.0.1:10899)")
	fProxy     = flag.String("proxy", "", "HTTP proxy [user:pass@]host:port")
	fTLS       = flag.Bool("tls", false, "bungkus sambungan dengan TLS")
	fSNI       = flag.String("sni", "", "SNI untuk TLS")
	fPayload   = flag.String("payload-file", "", "berkas payload HTTP")
	fKnownHost = flag.String("known-hosts", "", "berkas sidik jari host SSH")
)

func main() {
	log.SetFlags(0)
	log.SetPrefix("[sshsocks] ")
	flag.Parse()
	pass := os.Getenv("SSH_PASS")
	if *fHost == "" || *fUser == "" {
		log.Fatal("butuh -host dan -user")
	}

	conn, err := dialChain()
	if err != nil {
		log.Fatalf("gagal membuat sambungan: %v", err)
	}
	cfg := &ssh.ClientConfig{
		User:            *fUser,
		Auth:            []ssh.AuthMethod{ssh.Password(pass), keyboardInteractive(pass)},
		HostKeyCallback: tofu(*fKnownHost, *fHost),
		BannerCallback:  showBanner,
		Timeout:         30 * time.Second,
	}
	// NewClientConn tidak memakai cfg.Timeout; tanpa batas ini helper menggantung bila server diam.
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	cc, chans, reqs, err := ssh.NewClientConn(conn, *fHost, cfg)
	if err != nil {
		log.Fatalf("SSH gagal: %v", err)
	}
	_ = conn.SetDeadline(time.Time{})
	client := ssh.NewClient(cc, chans, reqs)
	log.Printf("SSH tersambung ke %s sebagai %s", *fHost, *fUser)

	go keepAlive(client)

	ln, err := net.Listen("tcp", *fListen)
	if err != nil {
		log.Fatalf("gagal listen %s: %v", *fListen, err)
	}
	log.Printf("SOCKS5 siap di %s", *fListen)
	t0 := time.Now()
	go func() {
		err := client.Wait()
		log.Printf("sambungan SSH terputus setelah %s: %v", time.Since(t0).Round(time.Second), err)
		os.Exit(2)
	}()
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Fatalf("accept: %v", err)
		}
		go handleSocks(c, client)
	}
}

var ansiRe = regexp.MustCompile("\x1b\\[[0-9;?]*[ -/]*[@-~]")

// showBanner: banner SSH dari pemilik server (dikirim sebelum login), dicetak per baris.
func showBanner(msg string) error {
	msg = ansiRe.ReplaceAllString(msg, "")
	for _, line := range strings.Split(strings.ReplaceAll(msg, "\r", ""), "\n") {
		if t := strings.TrimSpace(line); t != "" {
			log.Printf("banner: %s", t)
		}
	}
	return nil
}

func keyboardInteractive(pass string) ssh.AuthMethod {
	return ssh.KeyboardInteractive(func(_, _ string, qs []string, _ []bool) ([]string, error) {
		a := make([]string, len(qs))
		for i := range a {
			a[i] = pass
		}
		return a, nil
	})
}

// tofu: simpan sidik jari host saat pertama kali, tolak jika berubah.
func tofu(file, host string) ssh.HostKeyCallback {
	return func(_ string, _ net.Addr, key ssh.PublicKey) error {
		fp := ssh.FingerprintSHA256(key)
		if file == "" {
			return nil
		}
		b, err := os.ReadFile(file)
		if err == nil && len(strings.TrimSpace(string(b))) > 0 {
			if strings.TrimSpace(string(b)) != fp {
				return fmt.Errorf("SIDIK JARI HOST BERUBAH (tersimpan %s, diterima %s)", strings.TrimSpace(string(b)), fp)
			}
			return nil
		}
		log.Printf("sidik jari host %s disimpan: %s", host, fp)
		return os.WriteFile(file, []byte(fp+"\n"), 0o600)
	}
}

func keepAlive(c *ssh.Client) {
	for {
		time.Sleep(15 * time.Second)
		if _, _, err := c.SendRequest("keepalive@openssh.com", true, nil); err != nil {
			log.Printf("keepalive gagal: %v", err)
			c.Close()
			return
		}
	}
}

func dialChain() (net.Conn, error) {
	target := *fHost
	dest := target
	if *fConnect != "" {
		dest = *fConnect
	} else if *fProxy != "" {
		_, hp := splitProxyAuth(*fProxy)
		dest = hp
	}
	raw, err := dialAny(dest)
	if err != nil {
		return nil, err
	}
	var conn net.Conn = raw

	if *fTLS {
		sni := *fSNI
		if sni == "" {
			h, _, _ := net.SplitHostPort(target)
			sni = h
		}
		// Verifikasi sertifikat sengaja dimatikan (SNI bug host tidak cocok dengan sertifikat).
		// Keamanan dijaga oleh pemeriksaan sidik jari host SSH.
		tc := tls.Client(conn, &tls.Config{ServerName: sni, InsecureSkipVerify: true})
		_ = tc.SetDeadline(time.Now().Add(20 * time.Second))
		if err := tc.Handshake(); err != nil {
			raw.Close()
			return nil, fmt.Errorf("TLS: %w", err)
		}
		_ = tc.SetDeadline(time.Time{})
		conn = tc
		log.Printf("TLS ok: sni=%s versi=0x%x", sni, tc.ConnectionState().Version)
	} else {
		log.Printf("TLS: tidak dipakai")
	}

	bc := &bufConn{Conn: conn, r: bufio.NewReader(conn)}

	if *fPayload != "" {
		pl, err := os.ReadFile(*fPayload)
		if err != nil {
			raw.Close()
			return nil, err
		}
		h, p, _ := net.SplitHostPort(target)
		if err := sendPayload(bc, string(pl), h, p); err != nil {
			raw.Close()
			return nil, err
		}
		if err := readHTTPReply(bc); err != nil {
			raw.Close()
			return nil, err
		}
	} else if *fProxy != "" {
		auth, _ := splitProxyAuth(*fProxy)
		req := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
		if auth != "" {
			req += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(auth)) + "\r\n"
		}
		req += "\r\n"
		if _, err := bc.Write([]byte(req)); err != nil {
			raw.Close()
			return nil, err
		}
		if err := readHTTPReply(bc); err != nil {
			raw.Close()
			return nil, err
		}
	}
	return bc, nil
}

// dialAny: coba setiap alamat (dipisah koma) berurutan; kembalikan sambungan pertama yang berhasil.
func dialAny(list string) (net.Conn, error) {
	var lastErr error
	for _, a := range strings.Split(list, ",") {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		c, err := net.DialTimeout("tcp", a, 8*time.Second)
		if err == nil {
			log.Printf("tersambung ke %s", a)
			return c, nil
		}
		log.Printf("gagal dial %s: %v", a, err)
		lastErr = err
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("tidak ada alamat tujuan")
	}
	return nil, lastErr
}

func splitProxyAuth(s string) (auth, hostport string) {
	if i := strings.LastIndex(s, "@"); i >= 0 {
		return s[:i], s[i+1:]
	}
	return "", s
}

func expandPayload(s, host, port string) string {
	r := strings.NewReplacer(
		"[crlf]", "\r\n", "[lf]", "\n", "[cr]", "\r",
		"[host_port]", net.JoinHostPort(host, port),
		"[host]", host, "[port]", port,
		"[protocol]", "HTTP/1.1",
		"\\r", "\r", "\\n", "\n",
	)
	return r.Replace(s)
}

func sendPayload(c net.Conn, raw, host, port string) error {
	exp := expandPayload(raw, host, port)
	shown := exp
	if len(shown) > 200 {
		shown = shown[:200] + "..."
	}
	log.Printf("payload dikirim (%d byte): %q", len(exp), shown)
	parts := strings.Split(exp, "[split]")
	for i, p := range parts {
		if p != "" {
			if _, err := c.Write([]byte(p)); err != nil {
				return err
			}
		}
		if i < len(parts)-1 {
			time.Sleep(300 * time.Millisecond)
		}
	}
	return nil
}

// readHTTPReply: baca header balasan HTTP; terima 1xx/2xx. Jika yang datang
// langsung banner "SSH-", tidak ada header yang perlu dibaca.
func readHTTPReply(bc *bufConn) error {
	_ = bc.Conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	defer bc.Conn.SetReadDeadline(time.Time{})
	if head, err := bc.r.Peek(4); err == nil && string(head) == "SSH-" {
		return nil
	}
	status, err := bc.r.ReadString('\n')
	if err != nil {
		return fmt.Errorf("tidak ada balasan HTTP (server menutup koneksi, TLS=%v): %w", *fTLS, err)
	}
	status = strings.TrimSpace(status)
	f := strings.Fields(status)
	if len(f) < 2 || !strings.HasPrefix(f[0], "HTTP/") {
		return fmt.Errorf("balasan bukan HTTP: %q", status)
	}
	code, _ := strconv.Atoi(f[1])
	var extra []string
	for {
		line, err := bc.r.ReadString('\n')
		if err != nil {
			return err
		}
		t := strings.TrimSpace(line)
		if t == "" {
			break
		}
		lt := strings.ToLower(t)
		if strings.HasPrefix(lt, "location:") || strings.HasPrefix(lt, "server:") {
			extra = append(extra, t)
		}
	}
	log.Printf("balasan payload/proxy: %s", status)
	if code < 100 || code >= 300 {
		if len(extra) > 0 {
			return fmt.Errorf("ditolak: %s [%s]", status, strings.Join(extra, " | "))
		}
		return fmt.Errorf("ditolak: %s", status)
	}
	return nil
}

// ---------- SOCKS5 (tanpa autentikasi, hanya CONNECT) ----------

func handleSocks(c net.Conn, cl *ssh.Client) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	buf := make([]byte, 262)
	if _, err := io.ReadFull(c, buf[:2]); err != nil || buf[0] != 5 {
		return
	}
	if _, err := io.ReadFull(c, buf[:int(buf[1])]); err != nil {
		return
	}
	c.Write([]byte{5, 0})
	if _, err := io.ReadFull(c, buf[:4]); err != nil || buf[0] != 5 {
		return
	}
	if buf[1] != 1 {
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	var host string
	switch buf[3] {
	case 1:
		if _, err := io.ReadFull(c, buf[:4]); err != nil {
			return
		}
		host = net.IP(buf[:4]).String()
	case 3:
		if _, err := io.ReadFull(c, buf[:1]); err != nil {
			return
		}
		n := int(buf[0])
		if _, err := io.ReadFull(c, buf[:n]); err != nil {
			return
		}
		host = string(buf[:n])
	case 4:
		if _, err := io.ReadFull(c, buf[:16]); err != nil {
			return
		}
		host = net.IP(buf[:16]).String()
	default:
		return
	}
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(buf[:2])
	remote, err := cl.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		if atomic.AddInt32(&fwdErrs, 1) <= 10 {
			log.Printf("forward %s:%d gagal: %v", host, port, err)
		}
		c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	_ = c.SetDeadline(time.Time{})

	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst io.Writer, src io.Reader) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}
	go cp(remote, c)
	go cp(c, remote)
	wg.Wait()
}

