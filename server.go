package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	_ "github.com/mattn/go-sqlite3"
	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

const (
	roleGuest        = "guest"
	roleModerator    = "moderator"
	adminTokenHeader = "X-Relay-Key"
	serverVersion    = "v0.3.2"

	maxWSMessageBytes    = 1024 * 1024
	maxSignalTypeLen     = 64
	maxRoomNameLen       = 128
	maxUsernameLen       = 128
	maxClientIDLen       = 128
	maxModKeyLen         = 256
	maxReconnectTokenLen = 128
	maxPeerIDLen         = 128
	maxReasonLen         = 512
	maxSDPSizeBytes      = 1024 * 1024
	maxICECandidateLen   = 4096
	maxSDPMidLen         = 64
)

type signalMsg struct {
	Type string `json:"type"`

	Room           string `json:"room,omitempty"`
	PeerID         string `json:"peerId,omitempty"`
	Username       string `json:"username,omitempty"`
	ClientID       string `json:"clientId,omitempty"`
	ReconnectToken string `json:"reconnectToken,omitempty"`

	ModKey       string            `json:"modKey,omitempty"`
	Role         string            `json:"role,omitempty"`
	TargetPeerID string            `json:"targetPeerId,omitempty"`
	Reason       string            `json:"reason,omitempty"`
	MuteAll      bool              `json:"muteAll,omitempty"`
	Muted        bool              `json:"muted,omitempty"`
	CanSpeak     bool              `json:"canSpeak,omitempty"`
	Pending      []pendingSnapshot `json:"pending,omitempty"`

	HandRaised bool `json:"handRaised,omitempty"`

	AudioEnabled *bool `json:"audioEnabled,omitempty"`
	VideoEnabled *bool `json:"videoEnabled,omitempty"`

	Target     string                     `json:"target,omitempty"`
	Generation uint64                     `json:"generation,omitempty"`
	Revision   uint64                     `json:"revision,omitempty"`
	SDP        *webrtc.SessionDescription `json:"sdp,omitempty"`
	Candidate  *webrtc.ICECandidateInit   `json:"candidate,omitempty"`

	TrackKey string          `json:"trackKey,omitempty"`
	TrackID  string          `json:"trackId,omitempty"`
	StreamID string          `json:"streamId,omitempty"`
	OwnerID  string          `json:"ownerId,omitempty"`
	Kind     string          `json:"kind,omitempty"`
	Tracks   []trackSnapshot `json:"tracks,omitempty"`

	Peers  []peerSnapshot `json:"peers,omitempty"`
	RTT    int64          `json:"rtt,omitempty"`
	Seq    int64          `json:"seq,omitempty"`
	SentAt int64          `json:"sentAt,omitempty"`

	Error string `json:"error,omitempty"`
}

type peerSnapshot struct {
	PeerID       string `json:"peerId"`
	Username     string `json:"username"`
	Role         string `json:"role,omitempty"`
	Muted        bool   `json:"muted,omitempty"`
	CanSpeak     bool   `json:"canSpeak,omitempty"`
	HandRaised   bool   `json:"handRaised,omitempty"`
	AudioEnabled bool   `json:"audioEnabled"`
	VideoEnabled bool   `json:"videoEnabled"`
	RTT          int64  `json:"rtt,omitempty"`
}

type pendingSnapshot struct {
	PeerID   string `json:"peerId"`
	Username string `json:"username"`
	JoinedAt int64  `json:"joinedAt,omitempty"`
}

type trackSnapshot struct {
	TrackKey string `json:"trackKey"`
	TrackID  string `json:"trackId"`
	StreamID string `json:"streamId"`
	OwnerID  string `json:"ownerId"`
	Kind     string `json:"kind"`
}

type openRoomResponse struct {
	Room          string           `json:"room"`
	ModeratorKey  string           `json:"moderator_key"`
	GuestLinkData linkDataSnapshot `json:"guestLinkData"`
	ModLinkData   linkDataSnapshot `json:"moderatorLinkData"`
}

type versionResponse struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

type linkDataSnapshot struct {
	Room         string `json:"room"`
	ModeratorKey string `json:"moderator_key,omitempty"`
	HTTPRedirect string `json:"httpRedirect,omitempty"`
	DeepLink     string `json:"deepLink,omitempty"`
}

type server struct {
	api    *webrtc.API
	config webrtc.Configuration
	db     *sql.DB

	adminToken    string
	publicBaseURL string
	instanceName  string
	dbTable       string
	dbRoomColumn  string
	dbKeyColumn   string

	mu        sync.RWMutex
	rooms     map[string]*room
	openRooms map[string]string
}

type room struct {
	name         string
	moderatorKey string

	mu      sync.RWMutex
	peers   map[string]*peer
	pending map[string]*peer
	pubs    map[string]*publication

	muteAll   bool
	muteAllow map[string]bool
	muted     map[string]bool
}

func (r *room) snapshotPeersLocked() []peerSnapshot {
	peers := make([]peerSnapshot, 0, len(r.peers))
	for _, p := range r.peers {
		if p == nil || p.closed.Load() {
			continue
		}
		muted := r.isPeerMutedLocked(p)
		peers = append(peers, peerSnapshot{
			PeerID:       p.id,
			Username:     p.username,
			Role:         p.getRole(),
			Muted:        muted,
			CanSpeak:     !muted,
			HandRaised:   p.handRaised.Load(),
			AudioEnabled: p.audioEnabled.Load(),
			VideoEnabled: p.videoEnabled.Load(),
			RTT:          p.pingMs.Load(),
		})
	}
	return peers
}

func (r *room) snapshotPendingLocked() []pendingSnapshot {
	pending := make([]pendingSnapshot, 0, len(r.pending))
	for _, p := range r.pending {
		if p == nil || p.closed.Load() {
			continue
		}
		pending = append(pending, pendingSnapshot{
			PeerID:   p.id,
			Username: p.username,
			JoinedAt: p.joinedAt.UnixMilli(),
		})
	}
	return pending
}

func (r *room) snapshotTracksLocked() []trackSnapshot {
	tracks := make([]trackSnapshot, 0, len(r.pubs))
	for _, pub := range r.pubs {
		if pub == nil {
			continue
		}
		tracks = append(tracks, pub.snapshot())
	}
	return tracks
}

func (r *room) isPeerMutedLocked(p *peer) bool {
	if r == nil || p == nil || p.getRole() == roleModerator {
		return false
	}
	if r.muted[p.id] {
		return true
	}
	if r.muteAll && !r.muteAllow[p.id] {
		return true
	}
	return false
}

func (r *room) muteStateForPeerLocked(p *peer) (muted bool, canSpeak bool) {
	muted = r.isPeerMutedLocked(p)
	return muted, !muted
}

func (r *room) canForwardAudioFrom(p *peer) bool {
	if r == nil || p == nil || p.closed.Load() {
		return false
	}

	r.mu.RLock()
	defer r.mu.RUnlock()

	if r.peers[p.id] != p {
		return false
	}

	return !r.isPeerMutedLocked(p)
}

type peer struct {
	mu sync.RWMutex

	id             string
	username       string
	clientID       string
	reconnectToken string

	role     string
	pending  bool
	joinedAt time.Time

	ws   *websocket.Conn
	wsMu sync.Mutex

	room      *room
	signalCtx context.Context

	pubPC *webrtc.PeerConnection
	subPC *webrtc.PeerConnection

	pubReady atomic.Bool
	subReady atomic.Bool
	closed   atomic.Bool

	handRaised atomic.Bool

	audioEnabled atomic.Bool
	videoEnabled atomic.Bool

	subsMu sync.RWMutex
	subs   map[string]*subscription

	candMu               sync.Mutex
	pendingPubCandidates []pendingCandidate
	pendingSubCandidates []pendingCandidate

	subGeneration atomic.Uint64

	subNegMu       sync.Mutex
	subNegPending  bool
	subNegTimer    *time.Timer
	subMakingOffer bool
	subRevision    uint64

	pingMs atomic.Int64

	discoMu     sync.Mutex
	discoTimers map[string]*time.Timer

	detachOnce sync.Once
}

type pendingCandidate struct {
	generation uint64
	candidate  webrtc.ICECandidateInit
}

func (p *peer) getRole() string {
	if p == nil {
		return ""
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.role
}

func (p *peer) setRole(role string) {
	if p == nil {
		return
	}
	p.mu.Lock()
	p.role = role
	p.mu.Unlock()
}

func (p *peer) getRoom() *room {
	if p == nil {
		return nil
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.room
}

func (p *peer) activeRoom() (*room, bool) {
	if p == nil || p.closed.Load() {
		return nil, false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.room == nil || p.pending {
		return nil, false
	}
	return p.room, true
}

func (p *peer) activeRoomRole() (*room, string, bool) {
	if p == nil || p.closed.Load() {
		return nil, "", false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.room == nil || p.pending {
		return nil, p.role, false
	}
	return p.room, p.role, true
}

func (p *peer) setRoomPendingRole(r *room, pending bool, role string) {
	if p == nil {
		return
	}
	p.mu.Lock()
	p.room = r
	p.pending = pending
	if role != "" {
		p.role = role
	}
	p.mu.Unlock()
}

func (p *peer) clearRoom() {
	if p == nil {
		return
	}
	p.mu.Lock()
	p.room = nil
	p.pending = false
	p.mu.Unlock()
}

func (p *peer) getPubPC() *webrtc.PeerConnection {
	if p == nil {
		return nil
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.pubPC
}

func (p *peer) swapPubPC(pc *webrtc.PeerConnection) *webrtc.PeerConnection {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	old := p.pubPC
	p.pubPC = pc
	p.mu.Unlock()
	return old
}

func (p *peer) isPubPC(pc *webrtc.PeerConnection) bool {
	if p == nil || pc == nil {
		return false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.pubPC == pc
}

func (p *peer) getSubPC() *webrtc.PeerConnection {
	if p == nil {
		return nil
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.subPC
}

func (p *peer) swapSubPC(pc *webrtc.PeerConnection) *webrtc.PeerConnection {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	old := p.subPC
	p.subPC = pc
	p.mu.Unlock()
	return old
}

func (p *peer) isSubPC(pc *webrtc.PeerConnection) bool {
	if p == nil || pc == nil {
		return false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.subPC == pc
}

type subscription struct {
	track  *webrtc.TrackLocalStaticRTP
	sender *webrtc.RTPSender
	down   *downTrack
}

type publication struct {
	key      string
	kind     webrtc.RTPCodecType
	owner    *peer
	ownerID  string
	streamID string
	trackID  string
	remote   *webrtc.TrackRemote
	codec    webrtc.RTPCodecCapability
	router   *rtpRouter
}

func (p *publication) snapshot() trackSnapshot {
	if p == nil {
		return trackSnapshot{}
	}
	return trackSnapshot{
		TrackKey: p.key,
		TrackID:  p.trackID,
		StreamID: p.streamID,
		OwnerID:  p.ownerID,
		Kind:     p.kind.String(),
	}
}

func (p *peer) startDisconnectTimer(target string, d time.Duration, onTimeout func()) {
	if p == nil || target == "" {
		return
	}

	p.discoMu.Lock()
	if p.discoTimers == nil {
		p.discoTimers = make(map[string]*time.Timer)
	}
	if p.discoTimers[target] != nil {
		p.discoMu.Unlock()
		return
	}

	p.discoTimers[target] = time.AfterFunc(d, func() {
		onTimeout()

		p.discoMu.Lock()
		if t := p.discoTimers[target]; t != nil {
			t.Stop()
			delete(p.discoTimers, target)
		}
		p.discoMu.Unlock()
	})
	p.discoMu.Unlock()
}

func (p *peer) stopDisconnectTimer(target string) {
	if p == nil || target == "" {
		return
	}

	p.discoMu.Lock()
	if p.discoTimers != nil {
		if t := p.discoTimers[target]; t != nil {
			t.Stop()
			delete(p.discoTimers, target)
		}
	}
	p.discoMu.Unlock()
}

func (p *peer) stopAllDisconnectTimers() {
	if p == nil {
		return
	}

	p.discoMu.Lock()
	for k, t := range p.discoTimers {
		if t != nil {
			t.Stop()
		}
		delete(p.discoTimers, k)
	}
	p.discoMu.Unlock()
}

func (p *peer) stopSubNegotiationTimer() {
	p.subNegMu.Lock()
	if p.subNegTimer != nil {
		p.subNegTimer.Stop()
		p.subNegTimer = nil
	}
	p.subNegPending = false
	p.subMakingOffer = false
	p.subNegMu.Unlock()
}

type rtpRouter struct {
	remote *webrtc.TrackRemote

	allowForward func() bool

	mu   sync.RWMutex
	subs map[string]*downTrack

	done      chan struct{}
	closeOnce sync.Once
}

func newRTPRouter(remote *webrtc.TrackRemote, allowForward func() bool) *rtpRouter {
	if allowForward == nil {
		allowForward = func() bool { return true }
	}

	r := &rtpRouter{
		remote:       remote,
		allowForward: allowForward,
		subs:         make(map[string]*downTrack),
		done:         make(chan struct{}),
	}
	go r.loop()
	return r
}

func (r *rtpRouter) add(id string, track *webrtc.TrackLocalStaticRTP) *downTrack {
	if r == nil || track == nil || id == "" {
		return nil
	}

	kind := webrtc.RTPCodecTypeVideo
	if r.remote != nil {
		kind = r.remote.Kind()
	}

	d := newDownTrack(id, track, kind)

	r.mu.Lock()
	if old := r.subs[id]; old != nil {
		old.close()
	}
	r.subs[id] = d
	r.mu.Unlock()

	return d
}

func (r *rtpRouter) remove(id string) {
	if r == nil || id == "" {
		return
	}
	r.mu.Lock()
	old := r.subs[id]
	delete(r.subs, id)
	r.mu.Unlock()
	if old != nil {
		old.close()
	}
}

func (r *rtpRouter) close() {
	if r == nil {
		return
	}
	r.closeOnce.Do(func() {
		close(r.done)

		r.mu.Lock()
		for id, sub := range r.subs {
			delete(r.subs, id)
			if sub != nil {
				sub.close()
			}
		}
		r.mu.Unlock()
	})
}

func (r *rtpRouter) loop() {
	for {
		pkt, _, err := r.remote.ReadRTP()
		if err != nil {
			r.close()
			return
		}

		if r.allowForward != nil && !r.allowForward() {
			continue
		}

		r.mu.RLock()
		subscribers := make([]*downTrack, 0, len(r.subs))
		for _, sub := range r.subs {
			if sub != nil {
				subscribers = append(subscribers, sub)
			}
		}
		r.mu.RUnlock()

		for _, sub := range subscribers {
			sub.push(pkt)
		}
	}
}

type downTrack struct {
	id    string
	track *webrtc.TrackLocalStaticRTP
	kind  webrtc.RTPCodecType
	ch    chan *rtp.Packet
	done  chan struct{}
	once  sync.Once
}

func newDownTrack(id string, track *webrtc.TrackLocalStaticRTP, kind webrtc.RTPCodecType) *downTrack {
	queueSize := 64

	if kind == webrtc.RTPCodecTypeVideo {
		queueSize = 24
	}

	if kind == webrtc.RTPCodecTypeAudio {
		queueSize = 120
	}

	d := &downTrack{
		id:    id,
		track: track,
		kind:  kind,
		ch:    make(chan *rtp.Packet, queueSize),
		done:  make(chan struct{}),
	}

	go d.writeLoop()
	return d
}

func (d *downTrack) close() {
	if d == nil {
		return
	}
	d.once.Do(func() {
		close(d.done)
	})
}

func (d *downTrack) push(pkt *rtp.Packet) {
	if d == nil || pkt == nil {
		return
	}

	clone := cloneRTPPacket(pkt)
	if clone == nil {
		return
	}

	select {
	case d.ch <- clone:
		return
	default:
	}

	if d.kind == webrtc.RTPCodecTypeVideo {
		dropped := 0

		for dropped < cap(d.ch) {
			select {
			case <-d.ch:
				dropped++
			default:
				goto drained
			}
		}

	drained:
		select {
		case d.ch <- clone:
		default:
		}
		return
	}

	select {
	case <-d.ch:
	default:
	}

	select {
	case d.ch <- clone:
	default:
	}
}

func (d *downTrack) writeLoop() {
	for {
		select {
		case pkt := <-d.ch:
			if pkt != nil {
				_ = d.track.WriteRTP(pkt)
			}
		case <-d.done:
			return
		}
	}
}

func cloneRTPPacket(pkt *rtp.Packet) *rtp.Packet {
	if pkt == nil {
		return nil
	}
	raw, err := pkt.Marshal()
	if err != nil {
		return nil
	}
	var out rtp.Packet
	if err := out.Unmarshal(raw); err != nil {
		return nil
	}
	return &out
}

func newServer(iceURLs []string, includeLoopback bool, nat1to1IPs []string, publicIP string, dbPath string, dbTable string, dbRoomColumn string, dbKeyColumn string, adminToken string, publicBaseURL string, instanceName string, iceUDPPortMin uint16, iceUDPPortMax uint16) *server {
	adminToken = strings.TrimSpace(adminToken)
	if adminToken == "" {
		log.Fatalf("admin token is required")
	}

	publicBaseURL = strings.TrimRight(strings.TrimSpace(publicBaseURL), "/")
	instanceName = strings.TrimSpace(instanceName)
	if instanceName == "" {
		log.Fatalf("instance name is required")
	}

	var m webrtc.MediaEngine
	if err := m.RegisterDefaultCodecs(); err != nil {
		log.Fatalf("RegisterDefaultCodecs: %v", err)
	}

	ir := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(&m, ir); err != nil {
		log.Fatalf("RegisterDefaultInterceptors: %v", err)
	}

	se := webrtc.SettingEngine{}

	se.SetInterfaceFilter(func(interfaceName string) bool {
		name := strings.ToLower(strings.TrimSpace(interfaceName))
		if strings.HasPrefix(name, "docker") {
			return false
		}
		if strings.HasPrefix(name, "br-") {
			return false
		}
		if strings.HasPrefix(name, "veth") {
			return false
		}
		if strings.HasPrefix(name, "virbr") {
			return false
		}
		return true
	})

	if includeLoopback {
		se.SetIncludeLoopbackCandidate(true)
	}
	if len(nat1to1IPs) > 0 {
		se.SetNAT1To1IPs(nat1to1IPs, webrtc.ICECandidateTypeHost)
	}
	if iceUDPPortMin == 0 || iceUDPPortMax == 0 || iceUDPPortMax < iceUDPPortMin {
		log.Fatalf("invalid ICE UDP port range: %d-%d", iceUDPPortMin, iceUDPPortMax)
	}
	if err := se.SetEphemeralUDPPortRange(iceUDPPortMin, iceUDPPortMax); err != nil {
		log.Fatalf("set ICE UDP port range %d-%d: %v", iceUDPPortMin, iceUDPPortMax, err)
	}

	api := webrtc.NewAPI(
		webrtc.WithMediaEngine(&m),
		webrtc.WithInterceptorRegistry(ir),
		webrtc.WithSettingEngine(se),
	)

	cfg := webrtc.Configuration{
		ICEServers:         nil,
		ICETransportPolicy: webrtc.ICETransportPolicyAll,
		BundlePolicy:       webrtc.BundlePolicyMaxBundle,
		RTCPMuxPolicy:      webrtc.RTCPMuxPolicyRequire,
	}

	if len(iceURLs) > 0 {
		filtered := normalizeUDPOnlyICEURLs(iceURLs)
		if len(filtered) > 0 {
			cfg.ICEServers = []webrtc.ICEServer{{
				URLs: filtered,
			}}
		}
	}

	dbTable = mustSQLIdentifier("db table", dbTable)
	dbRoomColumn = mustSQLIdentifier("db room column", dbRoomColumn)
	dbKeyColumn = mustSQLIdentifier("db key column", dbKeyColumn)
	if dbRoomColumn == dbKeyColumn {
		log.Fatalf("db room and key columns must be different")
	}

	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		log.Fatalf("open sqlite: %v", err)
	}
	if err := ensureRoomSchema(db, dbTable, dbRoomColumn, dbKeyColumn); err != nil {
		log.Fatalf("init sqlite schema: %v", err)
	}

	openRooms := make(map[string]string)
	rows, err := db.Query(fmt.Sprintf("SELECT %s, %s FROM %s", quoteSQLIdentifier(dbRoomColumn), quoteSQLIdentifier(dbKeyColumn), quoteSQLIdentifier(dbTable)))
	if err != nil {
		log.Fatalf("load open rooms: %v", err)
	}
	defer rows.Close()

	for rows.Next() {
		var name string
		var moderatorKey string
		if err := rows.Scan(&name, &moderatorKey); err != nil {
			continue
		}
		name = strings.TrimSpace(name)
		moderatorKey = strings.TrimSpace(moderatorKey)
		if name == "" {
			continue
		}
		if moderatorKey == "" {
			moderatorKey = mustGenerateModeratorKey()
			query := fmt.Sprintf("UPDATE %s SET %s = ? WHERE %s = ?", quoteSQLIdentifier(dbTable), quoteSQLIdentifier(dbKeyColumn), quoteSQLIdentifier(dbRoomColumn))
			if _, err := db.Exec(query, moderatorKey, name); err != nil {
				log.Fatalf("backfill moderator key for room %q: %v", name, err)
			}
		}
		openRooms[name] = moderatorKey
	}

	return &server{
		api:           api,
		config:        cfg,
		db:            db,
		adminToken:    adminToken,
		publicBaseURL: publicBaseURL,
		instanceName:  instanceName,
		dbTable:       dbTable,
		dbRoomColumn:  dbRoomColumn,
		dbKeyColumn:   dbKeyColumn,
		rooms:         make(map[string]*room),
		openRooms:     openRooms,
	}
}

func mustSQLIdentifier(label string, value string) string {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 63 {
		log.Fatalf("invalid %s", label)
	}
	for i, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '_' || (i > 0 && r >= '0' && r <= '9') {
			continue
		}
		log.Fatalf("invalid %s", label)
	}
	return value
}

func quoteSQLIdentifier(value string) string {
	return `"` + strings.ReplaceAll(value, `"`, `""`) + `"`
}

func ensureRoomSchema(db *sql.DB, table string, roomColumn string, keyColumn string) error {
	query := fmt.Sprintf(
		"CREATE TABLE IF NOT EXISTS %s (%s TEXT PRIMARY KEY, %s TEXT NOT NULL DEFAULT '')",
		quoteSQLIdentifier(table),
		quoteSQLIdentifier(roomColumn),
		quoteSQLIdentifier(keyColumn),
	)
	_, err := db.Exec(query)
	return err
}

func (s *server) roomModeratorKey(name string) (string, bool) {
	s.mu.RLock()
	key, ok := s.openRooms[name]
	s.mu.RUnlock()
	return key, ok
}

func (s *server) openRoom(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", fmt.Errorf("room name is required")
	}

	moderatorKey, ok := s.roomModeratorKey(name)
	if !ok || strings.TrimSpace(moderatorKey) == "" {
		moderatorKey = mustGenerateModeratorKey()
	}

	insertQuery := fmt.Sprintf("INSERT OR IGNORE INTO %s(%s, %s) VALUES(?, ?)", quoteSQLIdentifier(s.dbTable), quoteSQLIdentifier(s.dbRoomColumn), quoteSQLIdentifier(s.dbKeyColumn))
	if _, err := s.db.Exec(insertQuery, name, moderatorKey); err != nil {
		return "", err
	}
	updateIfEmptyQuery := fmt.Sprintf("UPDATE %s SET %s = CASE WHEN %s = '' THEN ? ELSE %s END WHERE %s = ?", quoteSQLIdentifier(s.dbTable), quoteSQLIdentifier(s.dbKeyColumn), quoteSQLIdentifier(s.dbKeyColumn), quoteSQLIdentifier(s.dbKeyColumn), quoteSQLIdentifier(s.dbRoomColumn))
	if _, err := s.db.Exec(updateIfEmptyQuery, moderatorKey, name); err != nil {
		return "", err
	}

	var storedKey string
	selectQuery := fmt.Sprintf("SELECT %s FROM %s WHERE %s = ?", quoteSQLIdentifier(s.dbKeyColumn), quoteSQLIdentifier(s.dbTable), quoteSQLIdentifier(s.dbRoomColumn))
	if err := s.db.QueryRow(selectQuery, name).Scan(&storedKey); err != nil {
		return "", err
	}
	storedKey = strings.TrimSpace(storedKey)
	if storedKey == "" {
		storedKey = moderatorKey
		updateQuery := fmt.Sprintf("UPDATE %s SET %s = ? WHERE %s = ?", quoteSQLIdentifier(s.dbTable), quoteSQLIdentifier(s.dbKeyColumn), quoteSQLIdentifier(s.dbRoomColumn))
		if _, err := s.db.Exec(updateQuery, storedKey, name); err != nil {
			return "", err
		}
	}

	s.mu.Lock()
	s.openRooms[name] = storedKey
	if r := s.rooms[name]; r != nil {
		r.moderatorKey = storedKey
	}
	s.mu.Unlock()

	return storedKey, nil
}

func (s *server) closeRoom(name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return fmt.Errorf("room name is required")
	}

	deleteQuery := fmt.Sprintf("DELETE FROM %s WHERE %s = ?", quoteSQLIdentifier(s.dbTable), quoteSQLIdentifier(s.dbRoomColumn))
	if _, err := s.db.Exec(deleteQuery, name); err != nil {
		return err
	}

	var r *room

	s.mu.Lock()
	delete(s.openRooms, name)
	r = s.rooms[name]
	delete(s.rooms, name)
	s.mu.Unlock()

	if r == nil {
		return nil
	}

	var peers []*peer

	r.mu.RLock()
	for _, p := range r.peers {
		if p != nil && !p.closed.Load() {
			peers = append(peers, p)
		}
	}
	for _, p := range r.pending {
		if p != nil && !p.closed.Load() {
			peers = append(peers, p)
		}
	}
	r.mu.RUnlock()

	for _, p := range peers {
		p.write(safeContext(p), signalMsg{
			Type:   "room-closed",
			Room:   name,
			PeerID: p.id,
			Reason: "room closed by admin",
		})

		s.detachPeer(p, "room closed by admin")
	}

	log.Printf("room closed: room=%s detachedPeers=%d", name, len(peers))
	return nil
}

func (s *server) listOpenRooms() []openRoomResponse {
	s.mu.RLock()
	rooms := make([]openRoomResponse, 0, len(s.openRooms))
	for roomName, moderatorKey := range s.openRooms {
		rooms = append(rooms, openRoomResponse{
			Room:         roomName,
			ModeratorKey: moderatorKey,
			GuestLinkData: linkDataSnapshot{
				Room:     roomName,
				DeepLink: buildClientDeepLink(url.Values{"room": []string{roomName}}),
			},
			ModLinkData: linkDataSnapshot{
				Room:         roomName,
				ModeratorKey: moderatorKey,
				DeepLink:     buildClientDeepLink(url.Values{"room": []string{roomName}, "modKey": []string{moderatorKey}}),
			},
		})
	}
	s.mu.RUnlock()
	return rooms
}

func (s *server) requireAdminToken(w http.ResponseWriter, r *http.Request) bool {
	expected := strings.TrimSpace(s.adminToken)
	if expected == "" {
		http.Error(w, "admin token is not configured", http.StatusServiceUnavailable)
		return false
	}

	got := strings.TrimSpace(r.Header.Get(adminTokenHeader))
	if got == "" {
		auth := strings.TrimSpace(r.Header.Get("Authorization"))
		const bearer = "Bearer "
		if strings.HasPrefix(auth, bearer) {
			got = strings.TrimSpace(strings.TrimPrefix(auth, bearer))
		}
	}

	if got == "" || subtle.ConstantTimeCompare([]byte(got), []byte(expected)) != 1 {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return false
	}

	return true
}

func (s *server) adminOpenRoomHandler(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminToken(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	moderatorKey, err := s.openRoom(name)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	base := s.publicBaseURL
	if base == "" {
		base = requestBaseURL(r)
	}
	guestQuery := url.Values{"room": []string{name}}
	modQuery := url.Values{"room": []string{name}, "modKey": []string{moderatorKey}}

	resp := openRoomResponse{
		Room:         name,
		ModeratorKey: moderatorKey,
		GuestLinkData: linkDataSnapshot{
			Room:         name,
			HTTPRedirect: base + "/connect?" + guestQuery.Encode(),
			DeepLink:     buildClientDeepLink(guestQuery),
		},
		ModLinkData: linkDataSnapshot{
			Room:         name,
			ModeratorKey: moderatorKey,
			DeepLink:     buildClientDeepLink(modQuery),
		},
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func (s *server) adminCloseRoomHandler(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminToken(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	name := r.URL.Query().Get("name")
	if err := s.closeRoom(name); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) adminOpenRoomsHandler(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminToken(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"openRooms": s.listOpenRooms()})
}

func (s *server) adminVersionHandler(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminToken(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(versionResponse{
		Name:    s.instanceName,
		Version: serverVersion,
	})
}

func (s *server) connectRedirectHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	q, err := validateConnectParams(r.URL.Query())
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	http.Redirect(w, r, buildClientDeepLink(q), http.StatusFound)
}

func validateConnectParams(in url.Values) (url.Values, error) {
	room := strings.TrimSpace(in.Get("room"))
	if room == "" {
		return nil, fmt.Errorf("room is required")
	}
	if !isSafeShortValue(room, 128) {
		return nil, fmt.Errorf("invalid room")
	}

	out := url.Values{}
	copyParam := func(name string, maxLen int) error {
		v := strings.TrimSpace(in.Get(name))
		if v == "" {
			return nil
		}
		if !isSafeShortValue(v, maxLen) {
			return fmt.Errorf("invalid %s", name)
		}
		out.Set(name, v)
		return nil
	}

	out.Set("room", room)

	if err := copyParam("ip", 255); err != nil {
		return nil, err
	}
	if err := copyParam("username", 128); err != nil {
		return nil, err
	}
	if strings.TrimSpace(in.Get("modKey")) != "" {
		return nil, fmt.Errorf("modKey is not allowed in HTTP redirect links")
	}

	tlsPin := strings.TrimSpace(in.Get("tlsPin"))
	if tlsPin != "" {
		if !strings.HasPrefix(tlsPin, "sha256/") {
			return nil, fmt.Errorf("invalid tlsPin")
		}
		raw := strings.TrimPrefix(tlsPin, "sha256/")
		if _, err := base64.StdEncoding.DecodeString(raw); err != nil {
			return nil, fmt.Errorf("invalid tlsPin")
		}
		out.Set("tlsPin", tlsPin)
	}

	return out, nil
}

func buildClientDeepLink(q url.Values) string {
	u := url.URL{
		Scheme:   "symposium",
		Host:     "connect",
		RawQuery: q.Encode(),
	}
	return u.String()
}

func requestBaseURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if xf := strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")); xf == "http" || xf == "https" {
		scheme = xf
	}
	return strings.TrimRight(scheme+"://"+r.Host, "/")
}

func isSafeShortValue(v string, maxLen int) bool {
	if v == "" || len(v) > maxLen {
		return false
	}
	for _, r := range v {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	return true
}

func validateRequiredSignalValue(name string, value string, maxLen int) (string, error) {
	v := strings.TrimSpace(value)
	if v == "" {
		return "", fmt.Errorf("%s is required", name)
	}
	if !isSafeShortValue(v, maxLen) {
		return "", fmt.Errorf("invalid %s", name)
	}
	return v, nil
}

func validateOptionalSignalValue(name string, value string, maxLen int) (string, error) {
	v := strings.TrimSpace(value)
	if v == "" {
		return "", nil
	}
	if !isSafeShortValue(v, maxLen) {
		return "", fmt.Errorf("invalid %s", name)
	}
	return v, nil
}

func targetPeerIDFromMsg(msg signalMsg) (string, error) {
	targetID := strings.TrimSpace(msg.TargetPeerID)
	if targetID == "" {
		targetID = strings.TrimSpace(msg.PeerID)
	}
	if targetID == "" {
		return "", fmt.Errorf("targetPeerId is required")
	}
	if !isSafeShortValue(targetID, maxPeerIDLen) {
		return "", fmt.Errorf("invalid targetPeerId")
	}
	return targetID, nil
}

func validatedReason(value string, fallback string) (string, error) {
	reason := strings.TrimSpace(value)
	if reason == "" {
		return fallback, nil
	}
	if !isSafeShortValue(reason, maxReasonLen) {
		return "", fmt.Errorf("invalid reason")
	}
	return reason, nil
}

func validateICECandidateInit(c *webrtc.ICECandidateInit) error {
	if c == nil {
		return nil
	}
	if len(strings.TrimSpace(c.Candidate)) > maxICECandidateLen {
		return fmt.Errorf("ICE candidate is too large")
	}
	if c.SDPMid != nil {
		mid := strings.TrimSpace(*c.SDPMid)
		if mid != "" && !isSafeShortValue(mid, maxSDPMidLen) {
			return fmt.Errorf("invalid candidate sdpMid")
		}
	}
	return nil
}

func (s *server) getOrCreateRoom(name string, moderatorKey string) *room {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r, ok := s.rooms[name]; ok {
		if r.moderatorKey == "" && moderatorKey != "" {
			r.moderatorKey = moderatorKey
		}
		return r
	}
	r := &room{
		name:         name,
		moderatorKey: moderatorKey,
		peers:        make(map[string]*peer),
		pending:      make(map[string]*peer),
		pubs:         make(map[string]*publication),
		muteAllow:    make(map[string]bool),
		muted:        make(map[string]bool),
	}
	s.rooms[name] = r
	return r
}

func (s *server) wsHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: true,
	})
	if err != nil {
		log.Printf("websocket accept: %v", err)
		return
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	conn.SetReadLimit(maxWSMessageBytes)

	remoteIP := r.RemoteAddr
	ua := r.UserAgent()
	log.Printf("WS connected ip=%s ua=%q", remoteIP, ua)

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	p := &peer{
		id:          newID(),
		ws:          conn,
		subs:        make(map[string]*subscription),
		signalCtx:   ctx,
		role:        roleGuest,
		discoTimers: make(map[string]*time.Timer),
	}

	for {
		var msg signalMsg
		if err := wsjson.Read(ctx, conn, &msg); err != nil {
			s.detachPeer(p, "ws closed")
			log.Printf("WS closed peer=%s err=%v", p.id, err)
			return
		}

		s.handleSignal(ctx, p, msg)
	}
}

func (s *server) handleRestartSubscribe(ctx context.Context, p *peer, msg signalMsg) {
	r, ok := p.activeRoom()
	if !ok {
		if p != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "join and wait for lobby approval first"})
		}
		return
	}

	reason, err := validatedReason(msg.Reason, "client requested subscribe restart")
	if err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	log.Printf("restart subscribe requested: peer=%s room=%s reason=%s", p.id, r.name, reason)

	if err := s.recreateSubscribePC(ctx, p, reason); err != nil {
		log.Printf("restart subscribe failed peer=%s: %v", p.id, err)
		p.write(ctx, signalMsg{Type: "error", Error: "restart subscribe failed: " + err.Error()})
		return
	}
}

func (s *server) recreateSubscribePC(ctx context.Context, p *peer, reason string) error {
	r, ok := p.activeRoom()
	if !ok {
		return fmt.Errorf("peer is not active")
	}

	p.stopDisconnectTimer("subscribe")
	p.stopSubNegotiationTimer()
	p.subReady.Store(false)

	p.candMu.Lock()
	p.pendingSubCandidates = nil
	p.candMu.Unlock()

	oldPC := p.swapSubPC(nil)

	p.subsMu.Lock()
	oldSubs := p.subs
	p.subs = make(map[string]*subscription)
	p.subsMu.Unlock()

	if oldSubs != nil {
		r.mu.RLock()
		for pubKey, sub := range oldSubs {
			pub := r.pubs[pubKey]
			if pub != nil && pub.router != nil {
				pub.router.remove(p.id + "|" + pub.key)
			}
			if sub != nil && sub.down != nil {
				sub.down.close()
			}
		}
		r.mu.RUnlock()
	}

	if oldPC != nil {
		_ = oldPC.Close()
	}

	if err := s.ensureSubscribePC(ctx, p); err != nil {
		return err
	}

	s.subscribeExisting(p)

	log.Printf("subscribe PC recreated: peer=%s generation=%d reason=%s", p.id, p.subGeneration.Load(), reason)
	return nil
}

func (s *server) handleSignal(ctx context.Context, p *peer, msg signalMsg) {
	msg.Type = strings.TrimSpace(msg.Type)
	if !isSafeShortValue(msg.Type, maxSignalTypeLen) {
		p.write(ctx, signalMsg{Type: "error", Error: "invalid message type"})
		return
	}

	switch msg.Type {
	case "join":
		s.handleJoin(ctx, p, msg)
	case "media-state":
		s.handleMediaState(ctx, p, msg)
	case "publishOffer":
		s.handlePublishOffer(ctx, p, msg)
	case "restartSubscribe":
		s.handleRestartSubscribe(ctx, p, msg)
	case "subscribeAnswer":
		s.handleSubscribeAnswer(ctx, p, msg)
	case "trickle":
		s.handleTrickle(ctx, p, msg)
	case "ping":
		p.write(ctx, signalMsg{Type: "pong", Seq: msg.Seq, SentAt: msg.SentAt})
	case "pingReport":
		if msg.RTT >= 0 {
			p.pingMs.Store(msg.RTT)
			s.broadcastPeerPing(p)
		}
	case "raise-hand":
		s.handleHandRaised(ctx, p, msg, true)
	case "lower-hand":
		s.handleHandRaised(ctx, p, msg, false)
	case "set-hand-raised":
		s.handleHandRaised(ctx, p, msg, msg.HandRaised)
	case "lower-peer-hand":
		s.handlePeerHandRaised(ctx, p, msg, false)
	case "set-peer-hand-raised":
		s.handlePeerHandRaised(ctx, p, msg, msg.HandRaised)
	case "lobby-approve":
		s.handleLobbyApprove(ctx, p, msg)
	case "lobby-reject":
		s.handleLobbyReject(ctx, p, msg)
	case "kick":
		s.handleKick(ctx, p, msg)
	case "mute":
		s.handleMutePeer(ctx, p, msg, true)
	case "unmute":
		s.handleMutePeer(ctx, p, msg, false)
	case "mute-all":
		s.handleMuteAll(ctx, p, msg, true)
	case "unmute-all":
		s.handleMuteAll(ctx, p, msg, false)
	case "set-mute-all":
		s.handleMuteAll(ctx, p, msg, msg.MuteAll)
	default:
		p.write(ctx, signalMsg{Type: "error", Error: "unknown type: " + msg.Type})
	}
}

func (s *server) handleJoin(ctx context.Context, p *peer, msg signalMsg) {
	roomName, err := validateRequiredSignalValue("room", msg.Room, maxRoomNameLen)
	if err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	moderatorKey, ok := s.roomModeratorKey(roomName)
	if !ok {
		p.write(ctx, signalMsg{Type: "error", Error: "room is closed"})
		return
	}
	if p.getRoom() != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "already joined"})
		return
	}

	username, err := validateOptionalSignalValue("username", msg.Username, maxUsernameLen)
	if err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}
	if username == "" {
		suffixLen := 4
		if len(p.id) < suffixLen {
			suffixLen = len(p.id)
		}
		username = "user-" + p.id[len(p.id)-suffixLen:]
	}
	p.username = username

	clientID, err := validateOptionalSignalValue("clientId", msg.ClientID, maxClientIDLen)
	if err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}
	if clientID == "" {
		clientID = "ws-" + p.id
	}
	p.clientID = clientID

	reconnectToken, err := validateOptionalSignalValue("reconnectToken", msg.ReconnectToken, maxReconnectTokenLen)
	if err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}
	p.reconnectToken = reconnectToken

	if _, err := validateOptionalSignalValue("modKey", msg.ModKey, maxModKeyLen); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	r := s.getOrCreateRoom(roomName, moderatorKey)
	role := roleGuest
	if isModeratorKeyValid(msg.ModKey, moderatorKey) {
		role = roleModerator
	}
	p.setRole(role)
	p.joinedAt = time.Now()

	stalePeers := s.findStalePeersByReconnectToken(r, p)
	for _, old := range stalePeers {
		log.Printf("replace stale peer: room=%s old=%s new=%s username=%s", r.name, old.id, p.id, p.username)
		s.detachPeer(old, "replaced by reconnect")
	}

	if p.reconnectToken == "" {
		p.reconnectToken = mustGenerateReconnectToken()
	}

	if role != roleModerator {
		r.mu.Lock()
		p.setRoomPendingRole(r, true, roleGuest)
		r.pending[p.id] = p
		pendingSnapshot := r.snapshotPendingLocked()
		muteAll := r.muteAll
		r.mu.Unlock()

		log.Printf("lobby wait: room=%s peer=%s", r.name, p.id)
		p.write(ctx, signalMsg{
			Type:           "lobby-wait",
			Room:           r.name,
			PeerID:         p.id,
			Username:       p.username,
			ReconnectToken: p.reconnectToken,
			Role:           roleGuest,
			MuteAll:        muteAll,
			Pending:        pendingSnapshot,
		})
		s.broadcastLobbyState(r)
		return
	}

	if err := s.ensureSubscribePC(ctx, p); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "subscribe pc create failed: " + err.Error()})
		return
	}

	r.mu.Lock()
	r.peers[p.id] = p
	p.setRoomPendingRole(r, false, roleModerator)
	peersSnapshot := r.snapshotPeersLocked()
	tracksSnapshot := r.snapshotTracksLocked()
	pendingSnapshot := r.snapshotPendingLocked()
	muteAll := r.muteAll
	muted, canSpeak := r.muteStateForPeerLocked(p)
	handRaised := p.handRaised.Load()
	audioEnabled := p.audioEnabled.Load()
	videoEnabled := p.videoEnabled.Load()
	r.mu.Unlock()

	log.Printf("join moderator: room=%s peer=%s", r.name, p.id)
	p.write(ctx, signalMsg{
		Type:           "join",
		PeerID:         p.id,
		Room:           r.name,
		Username:       p.username,
		ReconnectToken: p.reconnectToken,
		Role:           roleModerator,
		Peers:          peersSnapshot,
		Tracks:         tracksSnapshot,
		Pending:        pendingSnapshot,
		MuteAll:        muteAll,
		Muted:          muted,
		CanSpeak:       canSpeak,
		HandRaised:     handRaised,
		AudioEnabled:   &audioEnabled,
		VideoEnabled:   &videoEnabled,
	})

	s.subscribeExisting(p)

	s.broadcastRoom(r, signalMsg{
		Type:         "peer-joined",
		PeerID:       p.id,
		Username:     p.username,
		Role:         roleModerator,
		Muted:        muted,
		CanSpeak:     canSpeak,
		HandRaised:   handRaised,
		AudioEnabled: &audioEnabled,
		VideoEnabled: &videoEnabled,
		RTT:          p.pingMs.Load(),
	}, p)

	s.broadcastLobbyState(r)
}

func isModeratorKeyValid(got string, expected string) bool {
	got = strings.TrimSpace(got)
	expected = strings.TrimSpace(expected)
	if got == "" || expected == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(expected)) == 1
}

func (s *server) findStalePeersByReconnectToken(r *room, incoming *peer) []*peer {
	if r == nil || incoming == nil || incoming.clientID == "" || incoming.reconnectToken == "" || strings.HasPrefix(incoming.clientID, "ws-") {
		return nil
	}

	r.mu.RLock()
	defer r.mu.RUnlock()

	var stale []*peer
	matches := func(old *peer) bool {
		if old == nil || old == incoming || old.closed.Load() {
			return false
		}
		if old.clientID == "" || old.reconnectToken == "" {
			return false
		}
		return old.clientID == incoming.clientID && subtle.ConstantTimeCompare([]byte(old.reconnectToken), []byte(incoming.reconnectToken)) == 1
	}
	for _, old := range r.peers {
		if matches(old) {
			stale = append(stale, old)
		}
	}
	for _, old := range r.pending {
		if matches(old) {
			stale = append(stale, old)
		}
	}
	return stale
}

func (s *server) handleMediaState(ctx context.Context, p *peer, msg signalMsg) {
	r, ok := p.activeRoom()
	if !ok {
		if p != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "join and wait for lobby approval first"})
		}
		return
	}

	if msg.AudioEnabled != nil {
		p.audioEnabled.Store(*msg.AudioEnabled)
	}

	if msg.VideoEnabled != nil {
		p.videoEnabled.Store(*msg.VideoEnabled)
	}

	r.mu.RLock()
	muted, canSpeak := r.muteStateForPeerLocked(p)
	r.mu.RUnlock()

	audioEnabled := p.audioEnabled.Load()
	videoEnabled := p.videoEnabled.Load()
	handRaised := p.handRaised.Load()
	role := p.getRole()

	log.Printf("media state: room=%s peer=%s audioEnabled=%t videoEnabled=%t muted=%t canSpeak=%t", r.name, p.id, audioEnabled, videoEnabled, muted, canSpeak)

	s.broadcastRoom(r, signalMsg{
		Type:         "peer-media-state",
		Room:         r.name,
		PeerID:       p.id,
		TargetPeerID: p.id,
		Username:     p.username,
		Role:         role,
		Muted:        muted,
		CanSpeak:     canSpeak,
		HandRaised:   handRaised,
		AudioEnabled: &audioEnabled,
		VideoEnabled: &videoEnabled,
	}, nil)
}

func (s *server) handleLobbyApprove(ctx context.Context, moderator *peer, msg signalMsg) {
	if !s.requireModeratorCommand(ctx, moderator, msg) {
		return
	}
	targetID, err := targetPeerIDFromMsg(msg)
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	r := moderator.getRoom()
	if r == nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		return
	}

	r.mu.Lock()
	target := r.pending[targetID]
	if target == nil || target.closed.Load() {
		r.mu.Unlock()
		moderator.write(ctx, signalMsg{Type: "error", Error: "pending peer not found"})
		return
	}
	delete(r.pending, targetID)
	target.setRoomPendingRole(r, false, roleGuest)
	r.peers[target.id] = target
	muted, canSpeak := r.muteStateForPeerLocked(target)
	peersSnapshot := r.snapshotPeersLocked()
	tracksSnapshot := r.snapshotTracksLocked()
	muteAll := r.muteAll
	handRaised := target.handRaised.Load()
	audioEnabled := target.audioEnabled.Load()
	videoEnabled := target.videoEnabled.Load()
	r.mu.Unlock()

	if err := s.ensureSubscribePC(safeContext(target), target); err != nil {
		s.detachPeer(target, "subscribe pc create failed after lobby approval")
		moderator.write(ctx, signalMsg{Type: "error", Error: "approve failed: " + err.Error()})
		return
	}

	log.Printf("lobby approve: room=%s moderator=%s target=%s", r.name, moderator.id, target.id)
	target.write(safeContext(target), signalMsg{
		Type:           "lobby-approve",
		PeerID:         target.id,
		Room:           r.name,
		Username:       target.username,
		ReconnectToken: target.reconnectToken,
		Role:           roleGuest,
		MuteAll:        muteAll,
		Muted:          muted,
		CanSpeak:       canSpeak,
		HandRaised:     handRaised,
		AudioEnabled:   &audioEnabled,
		VideoEnabled:   &videoEnabled,
	})

	target.write(safeContext(target), signalMsg{
		Type:           "join",
		PeerID:         target.id,
		Room:           r.name,
		Username:       target.username,
		ReconnectToken: target.reconnectToken,
		Role:           roleGuest,
		Peers:          peersSnapshot,
		Tracks:         tracksSnapshot,
		MuteAll:        muteAll,
		Muted:          muted,
		CanSpeak:       canSpeak,
		HandRaised:     handRaised,
		AudioEnabled:   &audioEnabled,
		VideoEnabled:   &videoEnabled,
	})

	s.subscribeExisting(target)

	s.broadcastRoom(r, signalMsg{
		Type:         "peer-joined",
		PeerID:       target.id,
		Username:     target.username,
		Role:         roleGuest,
		Muted:        muted,
		CanSpeak:     canSpeak,
		HandRaised:   handRaised,
		AudioEnabled: &audioEnabled,
		VideoEnabled: &videoEnabled,
		RTT:          target.pingMs.Load(),
	}, target)

	s.broadcastLobbyState(r)
}

func (s *server) handleLobbyReject(ctx context.Context, moderator *peer, msg signalMsg) {
	if !s.requireModeratorCommand(ctx, moderator, msg) {
		return
	}
	targetID, err := targetPeerIDFromMsg(msg)
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	r := moderator.getRoom()
	if r == nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		return
	}

	r.mu.RLock()
	target := r.pending[targetID]
	r.mu.RUnlock()
	if target == nil || target.closed.Load() {
		moderator.write(ctx, signalMsg{Type: "error", Error: "pending peer not found"})
		return
	}

	reason, err := validatedReason(msg.Reason, "rejected by moderator")
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	log.Printf("lobby reject: room=%s moderator=%s target=%s", r.name, moderator.id, target.id)
	target.write(safeContext(target), signalMsg{Type: "lobby-reject", Room: r.name, PeerID: target.id, Reason: reason})
	s.detachPeer(target, reason)
	s.broadcastLobbyState(r)
}

func (s *server) handleKick(ctx context.Context, moderator *peer, msg signalMsg) {
	if !s.requireModeratorCommand(ctx, moderator, msg) {
		return
	}
	targetID, err := targetPeerIDFromMsg(msg)
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}
	if targetID == moderator.id {
		moderator.write(ctx, signalMsg{Type: "error", Error: "moderator cannot kick self"})
		return
	}

	r := moderator.getRoom()
	if r == nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		return
	}

	r.mu.RLock()
	target := r.peers[targetID]
	r.mu.RUnlock()
	if target == nil || target.closed.Load() {
		moderator.write(ctx, signalMsg{Type: "error", Error: "peer not found"})
		return
	}

	reason, err := validatedReason(msg.Reason, "kicked by moderator")
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	log.Printf("kick: room=%s moderator=%s target=%s", r.name, moderator.id, target.id)
	target.write(safeContext(target), signalMsg{Type: "kick", Room: r.name, PeerID: target.id, Reason: reason})
	s.broadcastRoom(r, signalMsg{Type: "peer-kicked", PeerID: target.id, TargetPeerID: target.id, Reason: reason}, target)
	s.detachPeer(target, reason)
}

func (s *server) handleMutePeer(ctx context.Context, moderator *peer, msg signalMsg, muted bool) {
	if !s.requireModeratorCommand(ctx, moderator, msg) {
		return
	}
	targetID, err := targetPeerIDFromMsg(msg)
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	r := moderator.getRoom()
	if r == nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		return
	}

	r.mu.Lock()
	target := r.peers[targetID]
	if target == nil || target.closed.Load() {
		r.mu.Unlock()
		moderator.write(ctx, signalMsg{Type: "error", Error: "peer not found"})
		return
	}
	if target.getRole() == roleModerator {
		r.mu.Unlock()
		moderator.write(ctx, signalMsg{Type: "error", Error: "cannot mute moderator"})
		return
	}

	if muted {
		r.muted[target.id] = true
		delete(r.muteAllow, target.id)
	} else {
		delete(r.muted, target.id)
		if r.muteAll {
			r.muteAllow[target.id] = true
		}
	}
	nowMuted, canSpeak := r.muteStateForPeerLocked(target)
	muteAll := r.muteAll
	audioEnabled := target.audioEnabled.Load()
	r.mu.Unlock()

	if nowMuted {
		target.write(safeContext(target), signalMsg{Type: "force-mute", Room: r.name, PeerID: target.id, Muted: true, CanSpeak: false, MuteAll: muteAll, AudioEnabled: &audioEnabled})
	} else {
		target.write(safeContext(target), signalMsg{Type: "force-unmute", Room: r.name, PeerID: target.id, Muted: false, CanSpeak: canSpeak, MuteAll: muteAll, AudioEnabled: &audioEnabled})
	}

	s.broadcastRoom(r, signalMsg{
		Type:         "mute-state",
		PeerID:       target.id,
		TargetPeerID: target.id,
		Muted:        nowMuted,
		CanSpeak:     canSpeak,
		MuteAll:      muteAll,
		AudioEnabled: &audioEnabled,
	}, target)
}

func (s *server) handleMuteAll(ctx context.Context, moderator *peer, msg signalMsg, enabled bool) {
	if !s.requireModeratorCommand(ctx, moderator, msg) {
		return
	}

	r := moderator.getRoom()
	if r == nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		return
	}

	var targets []*peer

	r.mu.Lock()
	r.muteAll = enabled
	if !enabled {
		r.muteAllow = make(map[string]bool)
	}
	for _, p := range r.peers {
		if p == nil || p.closed.Load() || p.getRole() == roleModerator {
			continue
		}
		targets = append(targets, p)
	}
	muteAll := r.muteAll
	r.mu.Unlock()

	for _, target := range targets {
		r.mu.RLock()
		muted, canSpeak := r.muteStateForPeerLocked(target)
		audioEnabled := target.audioEnabled.Load()
		r.mu.RUnlock()
		if muted {
			target.write(safeContext(target), signalMsg{Type: "force-mute", Room: r.name, PeerID: target.id, Muted: true, CanSpeak: false, MuteAll: muteAll, AudioEnabled: &audioEnabled})
		} else {
			target.write(safeContext(target), signalMsg{Type: "force-unmute", Room: r.name, PeerID: target.id, Muted: false, CanSpeak: canSpeak, MuteAll: muteAll, AudioEnabled: &audioEnabled})
		}
	}

	s.broadcastRoom(r, signalMsg{Type: "mute-all-state", Room: r.name, MuteAll: muteAll}, nil)
	s.broadcastLobbyState(r)
}

func (s *server) handleHandRaised(ctx context.Context, p *peer, msg signalMsg, raised bool) {
	r, ok := p.activeRoom()
	if !ok {
		if p != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "join and wait for lobby approval first"})
		}
		return
	}

	targetID := strings.TrimSpace(msg.TargetPeerID)
	if targetID == "" {
		targetID = strings.TrimSpace(msg.PeerID)
	}
	if targetID != "" && targetID != p.id {
		s.handlePeerHandRaised(ctx, p, msg, raised)
		return
	}

	p.handRaised.Store(raised)
	audioEnabled := p.audioEnabled.Load()
	role := p.getRole()

	log.Printf("hand state: room=%s peer=%s raised=%t", r.name, p.id, raised)
	s.broadcastRoom(r, signalMsg{
		Type:         "hand-state",
		Room:         r.name,
		PeerID:       p.id,
		TargetPeerID: p.id,
		Username:     p.username,
		Role:         role,
		HandRaised:   raised,
		AudioEnabled: &audioEnabled,
	}, nil)
}

func (s *server) handlePeerHandRaised(ctx context.Context, moderator *peer, msg signalMsg, raised bool) {
	if !s.requireModeratorCommand(ctx, moderator, msg) {
		return
	}

	targetID, err := targetPeerIDFromMsg(msg)
	if err != nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	r := moderator.getRoom()
	if r == nil {
		moderator.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		return
	}

	r.mu.RLock()
	target := r.peers[targetID]
	r.mu.RUnlock()
	if target == nil || target.closed.Load() {
		moderator.write(ctx, signalMsg{Type: "error", Error: "peer not found"})
		return
	}

	target.handRaised.Store(raised)
	audioEnabled := target.audioEnabled.Load()
	role := target.getRole()

	log.Printf("hand state by moderator: room=%s moderator=%s target=%s raised=%t", r.name, moderator.id, target.id, raised)
	s.broadcastRoom(r, signalMsg{
		Type:         "hand-state",
		Room:         r.name,
		PeerID:       target.id,
		TargetPeerID: target.id,
		Username:     target.username,
		Role:         role,
		HandRaised:   raised,
		AudioEnabled: &audioEnabled,
	}, nil)
}

func (s *server) requireModerator(ctx context.Context, p *peer) bool {
	_, role, ok := p.activeRoomRole()
	if !ok {
		if p != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		}
		return false
	}
	if role != roleModerator {
		p.write(ctx, signalMsg{Type: "error", Error: "moderator role required"})
		return false
	}
	return true
}

func (s *server) requireModeratorCommand(ctx context.Context, p *peer, msg signalMsg) bool {
	r, role, ok := p.activeRoomRole()
	if !ok {
		if p != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "join as moderator first"})
		}
		return false
	}
	if role != roleModerator {
		p.write(ctx, signalMsg{Type: "error", Error: "moderator role required"})
		return false
	}
	if !isModeratorKeyValid(msg.ModKey, r.moderatorKey) {
		p.write(ctx, signalMsg{Type: "error", Error: "valid modKey required"})
		return false
	}
	return true
}

func (s *server) broadcastLobbyState(r *room) {
	if r == nil {
		return
	}

	r.mu.RLock()
	pending := r.snapshotPendingLocked()
	muteAll := r.muteAll
	targets := make([]*peer, 0, len(r.peers))
	for _, p := range r.peers {
		if p == nil || p.closed.Load() || p.getRole() != roleModerator {
			continue
		}
		targets = append(targets, p)
	}
	r.mu.RUnlock()

	msg := signalMsg{Type: "lobby-state", Room: r.name, Pending: pending, MuteAll: muteAll}
	for _, p := range targets {
		p.write(safeContext(p), msg)
	}
}

func (s *server) handlePublishOffer(ctx context.Context, p *peer, msg signalMsg) {
	if _, ok := p.activeRoom(); !ok {
		p.write(ctx, signalMsg{Type: "error", Error: "join and wait for lobby approval first"})
		return
	}
	if msg.SDP == nil {
		p.write(ctx, signalMsg{Type: "error", Error: "missing SDP"})
		return
	}
	if len(msg.SDP.SDP) > maxSDPSizeBytes {
		p.write(ctx, signalMsg{Type: "error", Error: "SDP is too large"})
		return
	}
	if msg.SDP.Type != webrtc.SDPTypeOffer {
		p.write(ctx, signalMsg{Type: "error", Error: "publishOffer must contain SDP offer"})
		return
	}

	if err := s.ensurePublishPC(ctx, p); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "publish pc create failed: " + err.Error()})
		return
	}

	pubPC := p.getPubPC()
	if pubPC == nil {
		p.write(ctx, signalMsg{Type: "error", Error: "publish pc does not exist"})
		return
	}

	if pubPC.SignalingState() != webrtc.SignalingStateStable {
		p.write(ctx, signalMsg{Type: "error", Error: "publish pc is not stable: " + pubPC.SignalingState().String()})
		return
	}

	if err := pubPC.SetRemoteDescription(*msg.SDP); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "publish set remote: " + err.Error()})
		return
	}
	if !p.isPubPC(pubPC) {
		log.Printf("publish offer applied to stale PC peer=%s", p.id)
		return
	}
	s.flushPendingCandidates(p, "publish")

	answer, err := pubPC.CreateAnswer(nil)
	if err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "publish create answer: " + err.Error()})
		return
	}
	if err := pubPC.SetLocalDescription(answer); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "publish set local: " + err.Error()})
		return
	}
	if !p.isPubPC(pubPC) {
		log.Printf("publish answer created on stale PC peer=%s", p.id)
		return
	}

	local := pubPC.LocalDescription()
	if local == nil {
		local = &answer
	}

	sendLocal := sanitizedDescription(local)

	p.pubReady.Store(true)
	p.write(ctx, signalMsg{
		Type:   "publishAnswer",
		Target: "publish",
		SDP:    sendLocal,
	})
	log.Printf("publish answered: peer=%s", p.id)

	s.subscribeExisting(p)
}

func (s *server) handleSubscribeAnswer(ctx context.Context, p *peer, msg signalMsg) {
	if _, ok := p.activeRoom(); !ok {
		p.write(ctx, signalMsg{Type: "error", Error: "join and wait for lobby approval first"})
		return
	}

	currentGeneration := p.subGeneration.Load()
	if msg.Generation == 0 || msg.Generation != currentGeneration {
		log.Printf("drop stale subscribeAnswer peer=%s msgGen=%d currentGen=%d revision=%d", p.id, msg.Generation, currentGeneration, msg.Revision)
		return
	}

	if msg.SDP == nil {
		p.write(ctx, signalMsg{Type: "error", Error: "missing SDP"})
		return
	}
	if len(msg.SDP.SDP) > maxSDPSizeBytes {
		p.write(ctx, signalMsg{Type: "error", Error: "SDP is too large"})
		return
	}
	if msg.SDP.Type != webrtc.SDPTypeAnswer {
		p.write(ctx, signalMsg{Type: "error", Error: "subscribeAnswer must contain SDP answer"})
		return
	}

	subPC := p.getSubPC()
	if subPC == nil {
		p.write(ctx, signalMsg{Type: "error", Error: "subscribe pc does not exist"})
		return
	}
	if !p.isSubPC(subPC) || p.subGeneration.Load() != currentGeneration {
		log.Printf("drop subscribeAnswer for stale PC peer=%s msgGen=%d currentGen=%d", p.id, msg.Generation, p.subGeneration.Load())
		return
	}
	if subPC.SignalingState() != webrtc.SignalingStateHaveLocalOffer {
		p.write(ctx, signalMsg{Type: "error", Error: "subscribe pc is not waiting for answer: " + subPC.SignalingState().String()})
		return
	}

	if err := subPC.SetRemoteDescription(*msg.SDP); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: "subscribe set remote answer: " + err.Error()})
		return
	}
	if !p.isSubPC(subPC) || p.subGeneration.Load() != currentGeneration {
		log.Printf("subscribe answer applied to stale PC peer=%s generation=%d revision=%d", p.id, msg.Generation, msg.Revision)
		return
	}

	p.subReady.Store(true)
	s.flushPendingCandidates(p, "subscribe")
	log.Printf("subscribe answer applied: peer=%s generation=%d revision=%d", p.id, msg.Generation, msg.Revision)

	p.subNegMu.Lock()
	pending := p.subNegPending
	p.subNegMu.Unlock()
	if pending {
		s.scheduleSubNegotiation(p)
	}
}

func (s *server) handleTrickle(ctx context.Context, p *peer, msg signalMsg) {
	if _, ok := p.activeRoom(); !ok {
		p.write(ctx, signalMsg{Type: "error", Error: "join and wait for lobby approval first"})
		return
	}
	if msg.Candidate == nil || strings.TrimSpace(msg.Candidate.Candidate) == "" {
		return
	}
	if err := validateICECandidateInit(msg.Candidate); err != nil {
		p.write(ctx, signalMsg{Type: "error", Error: err.Error()})
		return
	}

	target := strings.ToLower(strings.TrimSpace(msg.Target))
	log.Printf("client ICE candidate peer=%s target=%s generation=%d summary=%s", p.id, target, msg.Generation, iceCandidateSummary(msg.Candidate.Candidate))

	switch target {
	case "publish":
		pubPC := p.getPubPC()
		if pubPC == nil || pubPC.RemoteDescription() == nil || !p.isPubPC(pubPC) {
			s.queueCandidate(p, "publish", 0, *msg.Candidate)
			return
		}
		if err := pubPC.AddICECandidate(*msg.Candidate); err != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "publish add candidate: " + err.Error()})
		}
	case "subscribe":
		currentGeneration := p.subGeneration.Load()
		if msg.Generation == 0 || msg.Generation != currentGeneration {
			log.Printf("drop stale client subscribe ICE peer=%s msgGen=%d currentGen=%d", p.id, msg.Generation, currentGeneration)
			return
		}

		subPC := p.getSubPC()
		if subPC == nil || subPC.RemoteDescription() == nil || !p.isSubPC(subPC) || p.subGeneration.Load() != currentGeneration {
			s.queueCandidate(p, "subscribe", msg.Generation, *msg.Candidate)
			return
		}
		if err := subPC.AddICECandidate(*msg.Candidate); err != nil {
			p.write(ctx, signalMsg{Type: "error", Error: "subscribe add candidate: " + err.Error()})
		}
	default:
		p.write(ctx, signalMsg{Type: "error", Error: "trickle target must be publish or subscribe"})
	}
}

func (s *server) queueCandidate(p *peer, target string, generation uint64, c webrtc.ICECandidateInit) {
	if strings.TrimSpace(c.Candidate) == "" {
		return
	}

	item := pendingCandidate{
		generation: generation,
		candidate:  c,
	}

	p.candMu.Lock()
	defer p.candMu.Unlock()

	switch target {
	case "publish":
		p.pendingPubCandidates = append(p.pendingPubCandidates, item)
	case "subscribe":
		p.pendingSubCandidates = append(p.pendingSubCandidates, item)
	}
}

func (s *server) flushPendingCandidates(p *peer, target string) {
	p.candMu.Lock()
	var pending []pendingCandidate
	switch target {
	case "publish":
		pending = append(pending, p.pendingPubCandidates...)
		p.pendingPubCandidates = nil
	case "subscribe":
		pending = append(pending, p.pendingSubCandidates...)
		p.pendingSubCandidates = nil
	}
	p.candMu.Unlock()

	if len(pending) == 0 {
		return
	}

	var pc *webrtc.PeerConnection
	var currentGeneration uint64
	if target == "publish" {
		pc = p.getPubPC()
	} else {
		pc = p.getSubPC()
		currentGeneration = p.subGeneration.Load()
	}

	if pc == nil || pc.RemoteDescription() == nil {
		p.candMu.Lock()
		if target == "publish" {
			p.pendingPubCandidates = append(pending, p.pendingPubCandidates...)
		} else {
			p.pendingSubCandidates = append(pending, p.pendingSubCandidates...)
		}
		p.candMu.Unlock()
		return
	}

	for _, item := range pending {
		if strings.TrimSpace(item.candidate.Candidate) == "" {
			continue
		}

		if target == "publish" && !p.isPubPC(pc) {
			s.queueCandidate(p, target, item.generation, item.candidate)
			continue
		}

		if target == "subscribe" {
			if !p.isSubPC(pc) {
				s.queueCandidate(p, target, item.generation, item.candidate)
				continue
			}
			if item.generation == 0 || item.generation != currentGeneration {
				log.Printf("drop stale queued subscribe ICE peer=%s itemGen=%d currentGen=%d", p.id, item.generation, currentGeneration)
				continue
			}
		}

		if err := pc.AddICECandidate(item.candidate); err != nil {
			log.Printf("flush candidate failed peer=%s target=%s generation=%d: %v", p.id, target, item.generation, err)
		}
	}
}

func (s *server) ensurePublishPC(ctx context.Context, p *peer) error {
	if p == nil || p.closed.Load() {
		return fmt.Errorf("peer is closed")
	}
	if p.getPubPC() != nil {
		return nil
	}

	pc, err := s.api.NewPeerConnection(s.config)
	if err != nil {
		return err
	}

	p.mu.Lock()
	if p.pubPC != nil {
		p.mu.Unlock()
		_ = pc.Close()
		return nil
	}
	if p.closed.Load() {
		p.mu.Unlock()
		_ = pc.Close()
		return fmt.Errorf("peer is closed")
	}
	p.pubPC = pc
	p.mu.Unlock()

	s.bindPublishHandlers(ctx, p, pc)
	return nil
}

func (s *server) ensureSubscribePC(ctx context.Context, p *peer) error {
	if p == nil || p.closed.Load() {
		return fmt.Errorf("peer is closed")
	}
	if p.getSubPC() != nil {
		return nil
	}

	pc, err := s.api.NewPeerConnection(s.config)
	if err != nil {
		return err
	}

	var generation uint64
	p.mu.Lock()
	if p.subPC != nil {
		p.mu.Unlock()
		_ = pc.Close()
		return nil
	}
	if p.closed.Load() {
		p.mu.Unlock()
		_ = pc.Close()
		return fmt.Errorf("peer is closed")
	}
	generation = p.subGeneration.Add(1)
	p.subPC = pc
	p.mu.Unlock()

	log.Printf("subscribe PC created: peer=%s generation=%d", p.id, generation)
	s.bindSubscribeHandlers(ctx, p, pc, generation)
	return nil
}

func (s *server) bindPublishHandlers(ctx context.Context, p *peer, pc *webrtc.PeerConnection) {
	pc.OnTrack(func(remote *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		_ = receiver

		if remote.Kind() != webrtc.RTPCodecTypeAudio && remote.Kind() != webrtc.RTPCodecTypeVideo {
			log.Printf("ignore unsupported published track kind=%s id=%s", remote.Kind().String(), remote.ID())
			return
		}
		if !p.isPubPC(pc) {
			log.Printf("ignore track from stale publish PC peer=%s", p.id)
			return
		}

		r, ok := p.activeRoom()
		if !ok {
			log.Printf("ignore track from peer=%s without active room or already closed", p.id)
			return
		}

		pubKey := "pub-" + newID()
		allowForward := func() bool { return true }
		if remote.Kind() == webrtc.RTPCodecTypeAudio {
			roomAtPublish := r
			owner := p
			allowForward = func() bool {
				return roomAtPublish.canForwardAudioFrom(owner)
			}
		}

		pub := &publication{
			key:      pubKey,
			kind:     remote.Kind(),
			owner:    p,
			ownerID:  p.id,
			streamID: remote.StreamID(),
			trackID:  remote.ID(),
			remote:   remote,
			codec:    remote.Codec().RTPCodecCapability,
			router:   newRTPRouter(remote, allowForward),
		}

		log.Printf("track published: room=%s owner=%s pub=%s stream=%s track=%s codec=%s kind=%s", r.name, p.id, pub.key, remote.StreamID(), remote.ID(), remote.Codec().MimeType, remote.Kind().String())

		var subscribers []*peer

		r.mu.Lock()
		r.pubs[pub.key] = pub
		for _, other := range r.peers {
			if other == nil || other.closed.Load() || other.id == p.id || other.getSubPC() == nil {
				continue
			}
			subscribers = append(subscribers, other)
		}
		r.mu.Unlock()

		s.broadcastRoom(r, signalMsg{
			Type:     "track-published",
			PeerID:   p.id,
			OwnerID:  p.id,
			TrackKey: pub.key,
			TrackID:  pub.trackID,
			StreamID: pub.streamID,
			Kind:     pub.kind.String(),
		}, nil)

		for _, other := range subscribers {
			s.subscribe(other, pub)
		}

		if pub.kind == webrtc.RTPCodecTypeVideo {
			s.requestKeyFrameBurst(pub, "new-publication")
		}

		go func() {
			<-pub.router.done
			s.removePublication(p, pub.key, "rtp ended")
		}()
	})

	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if !p.isPubPC(pc) {
			return
		}
		if c == nil {
			log.Printf("peer %s publish ICE gathering complete", p.id)
			p.write(ctx, signalMsg{Type: "iceComplete", Target: "publish"})
			return
		}
		json := c.ToJSON()
		if strings.TrimSpace(json.Candidate) == "" {
			return
		}
		if !shouldSendICECandidate(json.Candidate) {
			return
		}
		log.Printf("peer %s publish ICE candidate: mid=%v summary=%s", p.id, json.SDPMid, iceCandidateSummary(json.Candidate))
		p.write(ctx, signalMsg{Type: "trickle", Target: "publish", Candidate: &json})
	})

	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("peer %s publish ICE state: %s", p.id, state.String())
	})

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		s.onPeerConnectionState(p, "publish", pc, state)
	})
}

func (s *server) bindSubscribeHandlers(ctx context.Context, p *peer, pc *webrtc.PeerConnection, generation uint64) {
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if !p.isSubPC(pc) || p.subGeneration.Load() != generation {
			log.Printf("drop stale subscribe ICE callback peer=%s callbackGen=%d currentGen=%d", p.id, generation, p.subGeneration.Load())
			return
		}
		if c == nil {
			log.Printf("peer %s subscribe ICE gathering complete generation=%d", p.id, generation)
			p.write(ctx, signalMsg{Type: "iceComplete", Target: "subscribe", Generation: generation})
			return
		}
		json := c.ToJSON()
		if strings.TrimSpace(json.Candidate) == "" {
			return
		}
		if !shouldSendICECandidate(json.Candidate) {
			return
		}
		log.Printf("peer %s subscribe ICE candidate: generation=%d mid=%v summary=%s", p.id, generation, json.SDPMid, iceCandidateSummary(json.Candidate))
		p.write(ctx, signalMsg{Type: "trickle", Target: "subscribe", Generation: generation, Candidate: &json})
	})

	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("peer %s subscribe ICE state: %s", p.id, state.String())
	})

	pc.OnNegotiationNeeded(func() {
		if !p.isSubPC(pc) || p.subGeneration.Load() != generation {
			return
		}
		log.Printf("peer %s subscribe OnNegotiationNeeded generation=%d", p.id, generation)
		s.scheduleSubNegotiation(p)
	})

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		s.onPeerConnectionState(p, "subscribe", pc, state)
	})
}

func (s *server) onPeerConnectionState(p *peer, target string, pc *webrtc.PeerConnection, state webrtc.PeerConnectionState) {
	if p == nil || pc == nil {
		return
	}

	if target == "publish" && !p.isPubPC(pc) {
		log.Printf("ignore stale publish PC state: peer=%s state=%s", p.id, state.String())
		return
	}

	if target == "subscribe" && !p.isSubPC(pc) {
		log.Printf("ignore stale subscribe PC state: peer=%s state=%s", p.id, state.String())
		return
	}

	log.Printf("peer %s %s PC state: %s", p.id, target, state.String())

	switch state {
	case webrtc.PeerConnectionStateConnected, webrtc.PeerConnectionStateConnecting, webrtc.PeerConnectionStateNew:
		p.stopDisconnectTimer(target)

	case webrtc.PeerConnectionStateDisconnected:
		p.startDisconnectTimer(target, 10*time.Second, func() {
			if p.closed.Load() {
				return
			}
			if target == "publish" && !p.isPubPC(pc) {
				return
			}
			if target == "subscribe" && !p.isSubPC(pc) {
				return
			}

			current := pc.ConnectionState()
			if current == webrtc.PeerConnectionStateConnected || current == webrtc.PeerConnectionStateConnecting || current == webrtc.PeerConnectionStateNew {
				log.Printf("peer %s %s recovered before disconnect timeout: %s", p.id, target, current.String())
				return
			}

			log.Printf("peer %s %s remained disconnected -> detach", p.id, target)
			s.detachPeer(p, target+" disconnected")
		})

	case webrtc.PeerConnectionStateFailed:
		p.startDisconnectTimer(target, 5*time.Second, func() {
			if p.closed.Load() {
				return
			}
			if target == "publish" && !p.isPubPC(pc) {
				return
			}
			if target == "subscribe" && !p.isSubPC(pc) {
				return
			}

			current := pc.ConnectionState()
			if current == webrtc.PeerConnectionStateConnected || current == webrtc.PeerConnectionStateConnecting || current == webrtc.PeerConnectionStateNew {
				log.Printf("peer %s %s recovered from failed before timeout: %s", p.id, target, current.String())
				return
			}

			log.Printf("peer %s %s remained failed -> detach", p.id, target)
			_ = pc.Close()
			s.detachPeer(p, target+" failed")
		})

	case webrtc.PeerConnectionStateClosed:
		p.stopDisconnectTimer(target)
	}
}

func (s *server) subscribeExisting(p *peer) {
	r, ok := p.activeRoom()
	if !ok || p.getSubPC() == nil {
		return
	}

	var pubs []*publication

	r.mu.RLock()
	for _, pub := range r.pubs {
		if pub == nil || pub.ownerID == p.id {
			continue
		}
		pubs = append(pubs, pub)
	}
	r.mu.RUnlock()

	for _, pub := range pubs {
		s.subscribe(p, pub)
	}

	if len(pubs) > 0 {
		log.Printf("subscribed peer %s to %d existing pubs", p.id, len(pubs))
	}
}

func (s *server) subscribe(p *peer, pub *publication) {
	if p == nil || pub == nil || pub.router == nil || p.closed.Load() {
		return
	}
	if p.id == pub.ownerID {
		return
	}
	if _, ok := p.activeRoom(); !ok {
		return
	}
	subPC := p.getSubPC()
	if subPC == nil {
		return
	}

	p.subsMu.RLock()
	if existing, ok := p.subs[pub.key]; ok && existing != nil && existing.track != nil {
		p.subsMu.RUnlock()
		log.Printf("skip existing subscription: room=%s sub=%s <- pub=%s kind=%s", safeRoomName(p), p.id, pub.key, pub.kind.String())
		return
	}
	p.subsMu.RUnlock()

	outTrackID := pub.key
	outStreamID := pub.ownerID
	out, err := webrtc.NewTrackLocalStaticRTP(pub.codec, outTrackID, outStreamID)
	if err != nil {
		log.Printf("NewTrackLocalStaticRTP failed sub=%s pub=%s: %v", p.id, pub.key, err)
		return
	}

	if !p.isSubPC(subPC) {
		return
	}
	sender, err := subPC.AddTrack(out)
	if err != nil {
		log.Printf("sub AddTrack failed sub=%s pub=%s kind=%s: %v", p.id, pub.key, pub.kind.String(), err)
		return
	}
	if !p.isSubPC(subPC) {
		_ = subPC.RemoveTrack(sender)
		return
	}

	downID := p.id + "|" + pub.key
	down := pub.router.add(downID, out)

	p.subsMu.Lock()
	p.subs[pub.key] = &subscription{
		track:  out,
		sender: sender,
		down:   down,
	}
	p.subsMu.Unlock()

	log.Printf("subscribe: room=%s sub=%s <- pub=%s owner=%s kind=%s", safeRoomName(p), p.id, pub.key, pub.ownerID, pub.kind.String())

	if pub.kind == webrtc.RTPCodecTypeVideo {
		s.requestKeyFrameBurst(pub, "new-subscription")
	}

	go s.drainSenderRTCP(p, pub, sender)
	s.scheduleSubNegotiation(p)
}

func (s *server) removeSubscription(target *peer, pub *publication) bool {
	if target == nil || pub == nil {
		return false
	}
	subPC := target.getSubPC()
	if subPC == nil {
		return false
	}

	target.subsMu.Lock()
	sub, ok := target.subs[pub.key]
	if ok {
		delete(target.subs, pub.key)
	}
	target.subsMu.Unlock()

	if !ok || sub == nil {
		return false
	}

	if pub.router != nil {
		pub.router.remove(target.id + "|" + pub.key)
	}
	if sub.down != nil {
		sub.down.close()
	}
	if sub.sender != nil && subPC.ConnectionState() != webrtc.PeerConnectionStateClosed && target.isSubPC(subPC) {
		if err := subPC.RemoveTrack(sub.sender); err != nil {
			log.Printf("RemoveTrack failed peer=%s pub=%s: %v", target.id, pub.key, err)
		}
	}

	return true
}

func (s *server) removePublication(owner *peer, pubKey string, reason string) {
	if owner == nil || pubKey == "" {
		return
	}

	room := owner.getRoom()
	if room == nil {
		return
	}
	var pub *publication
	var remaining []*peer

	room.mu.Lock()
	pub = room.pubs[pubKey]
	if pub == nil || pub.owner != owner {
		room.mu.Unlock()
		return
	}
	delete(room.pubs, pubKey)
	for _, other := range room.peers {
		if other != nil && !other.closed.Load() && other.id != owner.id {
			remaining = append(remaining, other)
		}
	}
	room.mu.Unlock()

	log.Printf("track unpublished: room=%s owner=%s pub=%s reason=%s", room.name, owner.id, pub.key, reason)

	for _, other := range remaining {
		if s.removeSubscription(other, pub) {
			s.scheduleSubNegotiation(other)
		}
	}

	if pub.router != nil {
		pub.router.close()
	}

	s.broadcastRoom(room, signalMsg{
		Type:     "track-unpublished",
		PeerID:   owner.id,
		OwnerID:  owner.id,
		TrackKey: pub.key,
		TrackID:  pub.trackID,
		StreamID: pub.streamID,
		Kind:     pub.kind.String(),
	}, nil)
}

func (s *server) drainSenderRTCP(subscriber *peer, pub *publication, sender *webrtc.RTPSender) {
	if sender == nil || pub == nil {
		return
	}

	buf := make([]byte, 1500)
	for {
		n, _, err := sender.Read(buf)
		if err != nil {
			return
		}
		if pub.kind != webrtc.RTPCodecTypeVideo || pub.owner == nil || pub.owner.getPubPC() == nil {
			continue
		}

		pkts, err := rtcp.Unmarshal(buf[:n])
		if err != nil {
			continue
		}

		for _, pkt := range pkts {
			switch pkt.(type) {
			case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
				log.Printf("forward keyframe request from sub=%s to owner=%s pub=%s", subscriber.id, pub.ownerID, pub.key)
				s.requestKeyFrame(pub)
			}
		}
	}
}

func (s *server) requestKeyFrame(pub *publication) {
	if pub == nil || pub.kind != webrtc.RTPCodecTypeVideo || pub.owner == nil || pub.remote == nil {
		return
	}
	pubPC := pub.owner.getPubPC()
	if pubPC == nil {
		return
	}

	err := pubPC.WriteRTCP([]rtcp.Packet{
		&rtcp.PictureLossIndication{MediaSSRC: uint32(pub.remote.SSRC())},
	})
	if err != nil {
		log.Printf("requestKeyFrame failed pub=%s: %v", pub.key, err)
	}
}

func (s *server) requestKeyFrameBurst(pub *publication, reason string) {
	if pub == nil || pub.kind != webrtc.RTPCodecTypeVideo {
		return
	}

	delays := []time.Duration{
		0,
		200 * time.Millisecond,
		500 * time.Millisecond,
		1 * time.Second,
		2 * time.Second,
		3 * time.Second,
	}

	for _, delay := range delays {
		d := delay
		time.AfterFunc(d, func() {
			if pub.owner == nil || pub.owner.closed.Load() {
				return
			}
			log.Printf("request keyframe burst: pub=%s owner=%s reason=%s delay=%s", pub.key, pub.ownerID, reason, d)
			s.requestKeyFrame(pub)
		})
	}
}

func (s *server) scheduleSubNegotiation(p *peer) {
	if p == nil || p.getSubPC() == nil || p.closed.Load() {
		return
	}
	if _, ok := p.activeRoom(); !ok {
		return
	}

	p.subNegMu.Lock()
	p.subNegPending = true
	if p.subNegTimer != nil {
		p.subNegMu.Unlock()
		return
	}
	p.subNegTimer = time.AfterFunc(120*time.Millisecond, func() {
		s.flushSubNegotiation(p)
	})
	p.subNegMu.Unlock()
}

func (s *server) flushSubNegotiation(p *peer) {
	if p == nil || p.closed.Load() {
		return
	}
	if _, ok := p.activeRoom(); !ok {
		return
	}
	subPC := p.getSubPC()
	if subPC == nil {
		return
	}
	generation := p.subGeneration.Load()
	if generation == 0 {
		log.Printf("skip subscribe negotiation peer=%s: generation is zero", p.id)
		return
	}

	p.subNegMu.Lock()
	p.subNegTimer = nil
	if !p.subNegPending || p.subMakingOffer {
		p.subNegMu.Unlock()
		return
	}
	p.subNegPending = false
	p.subMakingOffer = true
	p.subRevision++
	revision := p.subRevision
	p.subNegMu.Unlock()

	defer func() {
		p.subNegMu.Lock()
		p.subMakingOffer = false
		pending := p.subNegPending
		p.subNegMu.Unlock()
		if pending && !p.closed.Load() {
			s.scheduleSubNegotiation(p)
		}
	}()

	if subPC.SignalingState() != webrtc.SignalingStateStable {
		log.Printf("delay subscribe negotiation peer=%s: state=%s", p.id, subPC.SignalingState())
		p.subNegMu.Lock()
		p.subNegPending = true
		p.subNegMu.Unlock()
		return
	}

	offer, err := subPC.CreateOffer(nil)
	if err != nil {
		log.Printf("subscribe CreateOffer peer=%s: %v", p.id, err)
		return
	}
	if err := subPC.SetLocalDescription(offer); err != nil {
		log.Printf("subscribe SetLocalDescription peer=%s: %v", p.id, err)
		return
	}
	if !p.isSubPC(subPC) || p.subGeneration.Load() != generation {
		log.Printf("subscribe offer created on stale PC peer=%s generation=%d revision=%d currentGen=%d", p.id, generation, revision, p.subGeneration.Load())
		return
	}

	local := subPC.LocalDescription()
	if local == nil {
		local = &offer
	}

	sendLocal := sanitizedDescription(local)

	log.Printf("send subscribeOffer peer=%s generation=%d revision=%d", p.id, generation, revision)
	p.write(safeContext(p), signalMsg{
		Type:       "subscribeOffer",
		Target:     "subscribe",
		Generation: generation,
		Revision:   revision,
		SDP:        sendLocal,
	})
}

func (s *server) detachPeer(p *peer, reason string) {
	if p == nil {
		return
	}

	p.detachOnce.Do(func() {
		p.closed.Store(true)
		p.stopAllDisconnectTimers()
		p.stopSubNegotiationTimer()
		p.handRaised.Store(false)
		p.audioEnabled.Store(false)
		p.videoEnabled.Store(false)

		if p.ws != nil {
			_ = p.ws.Close(websocket.StatusNormalClosure, reason)
		}

		oldPubPC := p.swapPubPC(nil)
		oldSubPC := p.swapSubPC(nil)

		if oldPubPC != nil {
			_ = oldPubPC.Close()
		}
		if oldSubPC != nil {
			_ = oldSubPC.Close()
		}

		room := p.getRoom()
		if room == nil {
			return
		}

		var ownerPubs []*publication
		var remainingPeers []*peer
		wasPending := false

		room.mu.Lock()

		if _, ok := room.pending[p.id]; ok {
			delete(room.pending, p.id)
			wasPending = true
		}

		p.subsMu.Lock()
		for key, sub := range p.subs {
			if pub, ok := room.pubs[key]; ok && pub != nil && pub.router != nil {
				pub.router.remove(p.id + "|" + pub.key)
			}
			if sub != nil && sub.down != nil {
				sub.down.close()
			}
			delete(p.subs, key)
		}
		p.subsMu.Unlock()

		for key, pub := range room.pubs {
			if pub != nil && pub.owner == p {
				ownerPubs = append(ownerPubs, pub)
				delete(room.pubs, key)
			}
		}

		delete(room.peers, p.id)
		delete(room.muted, p.id)
		delete(room.muteAllow, p.id)

		for _, other := range room.peers {
			if other != nil && !other.closed.Load() {
				remainingPeers = append(remainingPeers, other)
			}
		}

		room.mu.Unlock()

		for _, pub := range ownerPubs {
			if pub.router != nil {
				pub.router.close()
			}
			for _, other := range remainingPeers {
				if s.removeSubscription(other, pub) {
					s.scheduleSubNegotiation(other)
				}
			}
			s.broadcastRoom(room, signalMsg{
				Type:     "track-unpublished",
				PeerID:   p.id,
				OwnerID:  p.id,
				TrackKey: pub.key,
				TrackID:  pub.trackID,
				StreamID: pub.streamID,
				Kind:     pub.kind.String(),
			}, nil)
		}

		role := p.getRole()
		log.Printf("peer detached: peer=%s room=%s pending=%t reason=%s", p.id, room.name, wasPending, reason)
		if !wasPending {
			s.broadcastRoom(room, signalMsg{Type: "peer-left", PeerID: p.id, Username: p.username, Role: role}, nil)
		}
		s.broadcastLobbyState(room)
		p.clearRoom()
	})
}

func (s *server) broadcastRoom(r *room, msg signalMsg, skip *peer) {
	if r == nil {
		return
	}

	r.mu.RLock()
	targets := make([]*peer, 0, len(r.peers))
	for _, other := range r.peers {
		if other == nil || other == skip || other.closed.Load() {
			continue
		}
		targets = append(targets, other)
	}
	r.mu.RUnlock()

	for _, other := range targets {
		other.write(safeContext(other), msg)
	}
}

func (s *server) broadcastPeerPing(p *peer) {
	r, ok := p.activeRoom()
	if !ok {
		return
	}
	msg := signalMsg{Type: "peer-ping", PeerID: p.id, RTT: p.pingMs.Load()}
	s.broadcastRoom(r, msg, nil)
}

func (p *peer) write(ctx context.Context, v any) {
	if p == nil || p.ws == nil || p.closed.Load() {
		return
	}
	if ctx == nil {
		ctx = context.Background()
	}

	wctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	p.wsMu.Lock()
	defer p.wsMu.Unlock()
	if err := wsjson.Write(wctx, p.ws, v); err != nil {
		log.Printf("ws write failed peer=%s: %v", p.id, err)
	}
}

var globalID atomic.Uint64

func newID() string {
	return fmt.Sprintf("%d-%d", time.Now().UnixNano(), globalID.Add(1))
}

func mustGenerateModeratorKey() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(fmt.Sprintf("generate moderator key: %v", err))
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func mustGenerateReconnectToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(fmt.Sprintf("generate reconnect token: %v", err))
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func parseCSV(s string) []string {
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func normalizeUDPOnlyICEURLs(in []string) []string {
	out := make([]string, 0, len(in))
	for _, raw := range in {
		u := strings.TrimSpace(raw)
		if u == "" {
			continue
		}
		lower := strings.ToLower(u)

		if strings.Contains(lower, "transport=tcp") {
			log.Printf("drop TCP ICE URL: %s", u)
			continue
		}
		if strings.HasPrefix(lower, "turn:tcp:") || strings.HasPrefix(lower, "turns:tcp:") {
			log.Printf("drop TCP ICE URL: %s", u)
			continue
		}

		out = append(out, u)
	}
	return out
}

func iceCandidateSummary(candidate string) string {
	fields := strings.Fields(strings.TrimSpace(candidate))
	if len(fields) < 8 {
		return fmt.Sprintf("malformed len=%d", len(candidate))
	}
	component := fields[1]
	protocol := strings.ToLower(fields[2])
	typ := "unknown"
	for i := 0; i+1 < len(fields); i++ {
		if fields[i] == "typ" {
			typ = fields[i+1]
			break
		}
	}
	return fmt.Sprintf("component=%s protocol=%s type=%s len=%d", component, protocol, typ, len(candidate))
}

func shouldSendICECandidate(candidate string) bool {
	candidate = strings.TrimSpace(candidate)
	if candidate == "" {
		return false
	}

	fields := strings.Fields(candidate)
	if len(fields) < 8 {
		log.Printf("drop malformed ICE candidate summary=%s", iceCandidateSummary(candidate))
		return false
	}

	component := fields[1]
	if component != "1" {
		log.Printf("drop non-rtp ICE candidate component=%s summary=%s", component, iceCandidateSummary(candidate))
		return false
	}

	protocol := strings.ToLower(fields[2])
	if protocol != "udp" {
		log.Printf("drop non-udp ICE candidate protocol=%s summary=%s", protocol, iceCandidateSummary(candidate))
		return false
	}

	ip := net.ParseIP(fields[4])
	if ip == nil {
		log.Printf("drop ICE candidate with non-IP address summary=%s", iceCandidateSummary(candidate))
		return false
	}

	if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
		log.Printf("drop private/unusable ICE candidate summary=%s", iceCandidateSummary(candidate))
		return false
	}

	return true
}

func sanitizeSDPICECandidates(sdp string) string {
	normalized := strings.ReplaceAll(sdp, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")

	out := make([]string, 0, len(lines))

	for _, line := range lines {
		line = strings.TrimRight(line, "\r")

		if strings.HasPrefix(line, "a=candidate:") {
			candidate := strings.TrimPrefix(line, "a=")

			if !shouldSendICECandidate(candidate) {
				log.Printf("drop SDP ICE candidate summary=%s", iceCandidateSummary(candidate))
				continue
			}
		}

		out = append(out, line)
	}

	return strings.Join(out, "\r\n")
}

func sanitizedDescription(desc *webrtc.SessionDescription) *webrtc.SessionDescription {
	if desc == nil {
		return nil
	}

	cp := *desc
	cp.SDP = sanitizeSDPICECandidates(cp.SDP)
	return &cp
}

func safeContext(p *peer) context.Context {
	if p != nil && p.signalCtx != nil {
		return p.signalCtx
	}
	return context.Background()
}

func safeRoomName(p *peer) string {
	r := p.getRoom()
	if r != nil {
		return r.name
	}
	return ""
}

func hWithCORS(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, "+adminTokenHeader)
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		h.ServeHTTP(w, r)
	})
}

func readAdminTokenFromConfig(argToken string, argTokenFile string) string {
	adminToken := strings.TrimSpace(argToken)
	if adminToken == "" {
		adminToken = strings.TrimSpace(os.Getenv("RELAY_ADMIN_TOKEN"))
	}

	adminTokenFile := strings.TrimSpace(argTokenFile)
	if adminTokenFile == "" {
		adminTokenFile = strings.TrimSpace(os.Getenv("RELAY_ADMIN_TOKEN_FILE"))
	}

	if adminToken == "" && adminTokenFile != "" {
		b, err := os.ReadFile(adminTokenFile)
		if err != nil {
			log.Fatalf("read admin token file: %v", err)
		}
		adminToken = strings.TrimSpace(string(b))
	}

	if adminToken == "" {
		log.Fatalf("admin token is required: use --admin-token-file, --admin-token, RELAY_ADMIN_TOKEN_FILE, or RELAY_ADMIN_TOKEN")
	}

	return adminToken
}

func envOrDefault(name string, fallback string) string {
	v := strings.TrimSpace(os.Getenv(name))
	if v != "" {
		return v
	}
	return fallback
}

func envUint16OrDefault(name string, fallback uint16) uint16 {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	n, err := strconv.ParseUint(raw, 10, 16)
	if err != nil || n == 0 {
		log.Fatalf("invalid %s: %q", name, raw)
	}
	return uint16(n)
}

func main() {
	var (
		addr              = flag.String("addr", ":3001", "public HTTP listen address, e.g. :3001")
		instanceNameArg   = flag.String("instance-name", envOrDefault("RELAY_INSTANCE_NAME", "relay"), "instance name returned by the admin version endpoint")
		adminAddr         = flag.String("admin-addr", envOrDefault("RELAY_ADMIN_ADDR", "127.0.0.1:3002"), "admin HTTP listen address for /admin/* endpoints")
		adminOnPublic     = flag.Bool("admin-on-public", false, "also expose /admin/* on the public listener; not recommended unless protected by a reverse proxy")
		publicBaseURLArg  = flag.String("public-base-url", "", "public base URL used in admin link responses, e.g. https://example.com")
		wsPath            = flag.String("ws", "/ws", "WebSocket path")
		publicIPArg       = flag.String("public-ip", "", "public server IP used for NAT 1:1 host candidates")
		dbPathArg         = flag.String("rooms-db", "", "path to sqlite database for open rooms")
		dbTableArg        = flag.String("db-table", envOrDefault("RELAY_DB_TABLE", "records"), "sqlite table name")
		dbRoomColumnArg   = flag.String("db-room-column", envOrDefault("RELAY_DB_ROOM_COLUMN", "entry_id"), "sqlite room column name")
		dbKeyColumnArg    = flag.String("db-key-column", envOrDefault("RELAY_DB_KEY_COLUMN", "entry_value"), "sqlite moderator key column name")
		adminTokenArg     = flag.String("admin-token", "", "admin API token for /admin/* endpoints")
		adminTokenFileArg = flag.String("admin-token-file", "", "path to file containing admin API token for /admin/* endpoints")
		tlsCertArg        = flag.String("tls-cert", "", "TLS certificate path for the public listener")
		tlsKeyArg         = flag.String("tls-key", "", "TLS private key path for the public listener")
		iceUDPPortMinArg  = flag.Uint("ice-udp-port-min", uint(envUint16OrDefault("RELAY_ICE_UDP_PORT_MIN", 32768)), "minimum UDP port for WebRTC ICE media")
		iceUDPPortMaxArg  = flag.Uint("ice-udp-port-max", uint(envUint16OrDefault("RELAY_ICE_UDP_PORT_MAX", 60999)), "maximum UDP port for WebRTC ICE media")
		includeLoop       = flag.Bool("loopback", false, "include loopback host candidates")
		nat1to1CSV        = flag.String("nat1to1", "", "comma-separated external IPs for 1:1 NAT mapping")
	)
	flag.Parse()

	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)

	if *iceUDPPortMinArg == 0 || *iceUDPPortMinArg > 65535 || *iceUDPPortMaxArg == 0 || *iceUDPPortMaxArg > 65535 || *iceUDPPortMaxArg < *iceUDPPortMinArg {
		log.Fatalf("invalid ICE UDP port range: %d-%d", *iceUDPPortMinArg, *iceUDPPortMaxArg)
	}
	iceUDPPortMin := uint16(*iceUDPPortMinArg)
	iceUDPPortMax := uint16(*iceUDPPortMaxArg)

	publicIP := strings.TrimSpace(*publicIPArg)
	if publicIP == "" {
		publicIP = strings.TrimSpace(os.Getenv("RELAY_PUBLIC_IP"))
	}

	iceURLs := []string(nil)

	natIPs := parseCSV(*nat1to1CSV)
	if len(natIPs) == 0 && publicIP != "" {
		natIPs = []string{publicIP}
	}

	dbPath := strings.TrimSpace(*dbPathArg)
	if dbPath == "" {
		dbPath = strings.TrimSpace(os.Getenv("RELAY_STORAGE_PATH"))
	}
	if dbPath == "" {
		dbPath = "records.db"
	}

	adminToken := readAdminTokenFromConfig(*adminTokenArg, *adminTokenFileArg)

	publicBaseURL := strings.TrimSpace(*publicBaseURLArg)
	if publicBaseURL == "" {
		publicBaseURL = strings.TrimSpace(os.Getenv("RELAY_PUBLIC_BASE_URL"))
	}
	publicBaseURL = strings.TrimRight(publicBaseURL, "/")

	if len(iceURLs) == 0 {
		log.Printf("Using host-only SFU ICE config. No default SFU-side TURN. Clients may still enforce relay-only.")
	} else {
		log.Printf("ICE servers override, UDP-only after filtering: %v (SFU policy=all)", iceURLs)
	}
	if len(natIPs) > 0 {
		log.Printf("NAT 1:1 IPs: %v", natIPs)
	}
	if publicIP != "" {
		log.Printf("Public IP: %s", publicIP)
	}
	if publicBaseURL != "" {
		log.Printf("Public base URL: %s", publicBaseURL)
	}
	log.Printf("ICE UDP port range: %d-%d/udp", iceUDPPortMin, iceUDPPortMax)
	log.Printf("Admin API token configured: yes")

	s := newServer(iceURLs, *includeLoop, natIPs, publicIP, dbPath, *dbTableArg, *dbRoomColumnArg, *dbKeyColumnArg, adminToken, publicBaseURL, *instanceNameArg, iceUDPPortMin, iceUDPPortMax)

	publicMux := http.NewServeMux()
	publicMux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprintf(w, "Service is running. WebSocket endpoint: %s\n", *wsPath)
	})
	publicMux.HandleFunc(*wsPath, s.wsHandler)
	publicMux.HandleFunc("/connect", s.connectRedirectHandler)
	if *adminOnPublic {
		log.Printf("WARNING: /admin/* is exposed on the public listener because --admin-on-public=true")
		publicMux.HandleFunc("/admin/version", s.adminVersionHandler)
		publicMux.HandleFunc("/admin/open-room", s.adminOpenRoomHandler)
		publicMux.HandleFunc("/admin/close-room", s.adminCloseRoomHandler)
		publicMux.HandleFunc("/admin/open-rooms", s.adminOpenRoomsHandler)
	}

	adminMux := http.NewServeMux()
	adminMux.HandleFunc("/admin/version", s.adminVersionHandler)
	adminMux.HandleFunc("/admin/open-room", s.adminOpenRoomHandler)
	adminMux.HandleFunc("/admin/close-room", s.adminCloseRoomHandler)
	adminMux.HandleFunc("/admin/open-rooms", s.adminOpenRoomsHandler)

	adminServer := &http.Server{
		Addr:              *adminAddr,
		Handler:           hWithCORS(adminMux),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		log.Printf("Admin API listening on http://%s", *adminAddr)
		if err := adminServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("admin listen: %v", err)
		}
	}()

	publicServer := &http.Server{
		Addr:              *addr,
		Handler:           hWithCORS(publicMux),
		ReadHeaderTimeout: 10 * time.Second,
	}

	tlsCert := strings.TrimSpace(*tlsCertArg)
	tlsKey := strings.TrimSpace(*tlsKeyArg)
	if (tlsCert == "") != (tlsKey == "") {
		log.Fatalf("both --tls-cert and --tls-key are required when TLS is enabled")
	}
	var serveErr error
	if tlsCert != "" {
		log.Printf("public TLS listener active on %s", *addr)
		serveErr = publicServer.ListenAndServeTLS(tlsCert, tlsKey)
	} else {
		log.Printf("public listener active on %s", *addr)
		serveErr = publicServer.ListenAndServe()
	}
	if serveErr != nil && serveErr != http.ErrServerClosed {
		log.Fatalf("listen: %v", serveErr)
	}
}
