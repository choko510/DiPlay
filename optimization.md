# DiPlay Optimization Ledger

DiPlay の性能最適化について、候補、実施内容、測定結果、採否だけを記録する。

機能追加、通常のバグ修正、一般的な設計判断、作業日誌はここには記録しない。それらは既存のドキュメントや `.scratchpad/` で管理する。

## Status

| Status | Meaning |
|---|---|
| `PLANNED` | 最適化候補。まだ十分な測定をしていない |
| `MEASURING` | ベースラインまたはボトルネックを測定中 |
| `IN_PROGRESS` | 最適化を実装中 |
| `DONE` | 実装済みで効果も測定済み |
| `DONE_UNMEASURED` | 実装済みだが対象実機で改善量を未測定 |
| `NO_GAIN` | 試したが有意な改善を確認できなかった |
| `DEFERRED` | リスク、複雑さ、費用に対して効果が低く保留 |

## Rules

- 最適化前に可能な限りベースラインを取る。
- 実測していない変更を「高速化済み」「軽量化済み」と断定しない。
- 実測前の完了項目は `DONE_UNMEASURED` とする。
- 測定条件が結果へ大きく影響する場合は、端末、Android、iPhone、接続方式、再生状態などを併記する。
- CPU、メモリ、遅延、GC、フレーム落ち、音声underrun、APKサイズなど、変更対象に対応する指標で比較する。
- 性能改善と同時に安定性、音質、画質、接続成功率を悪化させない。
- 効果がなかった案も削除せず `NO_GAIN` として残し、同じ検証の繰り返しを防ぐ。
- PR または commit が存在する場合は必ず参照を残す。

## Baseline

同一条件で Before / After を比較する。

| Metric | Baseline | Latest | Notes |
|---|---:|---:|---|
| Wired USB attach -> first rendered frame | TBD | TBD | Connection Trace を基準に測定 |
| Wired reconnect -> first rendered frame | TBD | TBD | 同一 iPhone / cable 条件 |
| Wireless start -> first rendered frame | TBD | TBD | Bluetooth bootstrap を含む |
| CarPlay steady-state CPU | TBD | TBD | 5分以上の安定区間 |
| CarPlay + YouTube CPU | TBD | TBD | YouTube再生条件も記録 |
| CarPlay steady-state memory | TBD | TBD | PSS/RSSを記録 |
| CarPlay + YouTube memory | TBD | TBD | Geckoを含む |
| Video dropped frames | TBD | TBD | 一定時間あたり |
| Audio underruns | TBD | TBD | 一定時間あたり |
| GC frequency / pause | TBD | TBD | steady state |
| APK size per ABI | TBD | TBD | 同一 build type |

## Optimization Queue

| ID | Area | Target | Priority | Status |
|---|---|---|---|---|
| OPT-CON-001 | Startup | USB attach から first rendered frame までの短縮 | P0 | `PLANNED` |
| OPT-VID-001 | Video | packet -> MediaCodec hot path の allocation / copy 削減 | P0 | `PLANNED` |
| OPT-WEB-001 | GeckoView | CarPlay + YouTube の CPU / RAM / GC 削減 | P0 | `PLANNED` |
| OPT-MEM-001 | Memory | 長時間利用時の memory growth / GC 抑制 | P1 | `PLANNED` |
| OPT-AUD-001 | Audio | RTP -> AudioTrack hot path の allocation / copy 削減 | P1 | `PLANNED` |
| OPT-THR-001 | Threading | Main Thread の blocking work 削減 | P1 | `PLANNED` |
| OPT-NET-001 | Network | wired / wireless data path の I/O overhead 削減 | P2 | `PLANNED` |
| OPT-BLD-001 | Build | APK size と build performance の改善 | P2 | `PLANNED` |

## Planned Optimizations

### OPT-CON-001 — CarPlay startup critical path

**Status:** `PLANNED`  
**Priority:** P0

**Target:** USB接続検出から最初のCarPlay映像フレーム表示まで。

既存の Connection Trace を利用して、少なくとも次の区間を分離して測定する。

`USB detected -> permission -> USB open -> iAP2 -> Lockdown -> NCM -> AirPlay -> RECORD -> screen stream -> decoder -> first rendered frame`

主な確認対象:

- 固定待機時間
- polling interval
- retry開始までの待機
- 独立処理の不要な直列化
- Main Thread 上のI/O
- USB/NCM readiness判定
- AirPlay開始前後の同期
- MediaCodec初期化タイミング
- first frame前に不要な処理

安全性を落として並列化するのではなく、実測でクリティカルパスになっている箇所から変更する。

**Result:** TBD

### OPT-VID-001 — Video hot path

**Status:** `PLANNED`  
**Priority:** P0

**Target:** CarPlay映像受信からMediaCodec投入までのCPU、allocation、copy回数。

主な確認対象:

- packetごとの `ByteArray` 生成
- `ByteBuffer` の複製
- payloadの再コピー
- SPS/PPS処理
- decoder input bufferへのコピー
- temporary object / collection
- per-frame logging
- lock contention

**Result:** TBD

### OPT-WEB-001 — GeckoView split mode

**Status:** `PLANNED`  
**Priority:** P0

**Target:** CarPlay + YouTube利用中のCPU、RAM、GC、描画負荷。

主な確認対象:

- GeckoRuntime / GeckoSession lifecycle
- web content process memory
- split layout resize
- GeckoView attach / detach
- fullscreen / popup切替
- Activity recreation
- background / foreground
- YouTube動画再生中のCPU/GPU負荷
- CarPlay TextureView resizeに伴う不要な再ネゴシエーション

**Result:** TBD

### OPT-MEM-001 — Long-running memory and GC

**Status:** `PLANNED`  
**Priority:** P1

**Target:** 30〜60分以上の連続利用時にメモリが継続増加しない状態。

CarPlayのみとCarPlay + YouTubeの両方で確認する。

主な確認対象:

- retained Activity
- GeckoSession
- TextureView / Surface
- MediaCodec
- AudioTrack
- packet buffer
- coroutine / thread
- callback / listener
- `ByteArray` / `ByteBuffer`

**Result:** TBD

### OPT-AUD-001 — Audio hot path

**Status:** `PLANNED`  
**Priority:** P1

**Target:** RTP受信からAudioTrack書き込みまでのCPU、allocation、copy回数。

主な経路:

`AudioStream -> RtpReorderBuffer -> decoder -> MediaAudioBuffer -> AndroidMediaSink -> AudioTrack`

PR #10 ですでに大規模なaudio pipeline変更が入っているため、互換性を変える再設計より先に hot path の allocation、copy、lock、logging を測定する。

**Result:** TBD

### OPT-THR-001 — Main Thread blocking audit

**Status:** `PLANNED`  
**Priority:** P1

Perfetto等でMain Thread stallを確認し、USB、Wi-Fi、teardown、Gecko、MediaCodec、file I/O、preferences、profile loadingを中心に調査する。

**Result:** TBD

### OPT-NET-001 — Network and transport I/O

**Status:** `PLANNED`  
**Priority:** P2

wired USBMUX/NCM と wireless control/tunnel の steady-state I/Oについて、buffer allocation、copy、read/write粒度、不要なwake-up、過剰なdiagnostic処理を測定する。

**Result:** TBD

### OPT-BLD-001 — Build and package

**Status:** `PLANNED`  
**Priority:** P2

APKサイズ、Gradle configuration/execution時間、不要なpackagingを測定し、runtime性能と独立して改善可能な項目を扱う。

**Result:** TBD

## Completed Optimizations

### OPT-AUD-D001 — Sparse AudioTimestamp polling

**Status:** `DONE_UNMEASURED`  
**Source:** PR #10

AudioTrack timestamp取得を常時高頻度で行わず、取得状況に応じてprobe頻度を下げる。timestampが安定しない場合はplayback-head timingを維持する。

**Expected effect:** Audio HAL / frameworkへの不要なquery削減。  
**Measured result:** Target hardwareでは未測定。

### OPT-AUD-D002 — Bounded AudioTrack retry/backoff

**Status:** `DONE_UNMEASURED`  
**Source:** PR #10

AudioTrackの構築・操作失敗時にtight retry loopを作らず、250 msから最大5 sまでのbounded backoffを使用する。

**Expected effect:** HAL異常時のCPU spinと再試行負荷を抑制。  
**Measured result:** Target hardwareでは未測定。

### OPT-AUD-D003 — Bounded AAC startup cache

**Status:** `DONE_UNMEASURED`  
**Source:** PR #10

AAC startup fallback cacheを100 AU、512 KiB、3 secondsに制限する。

**Expected effect:** decoder出力が得られない場合のメモリ増加を上限内に抑える。  
**Measured result:** 上限は実装で保証。実機での差分は未測定。

### OPT-AUD-D004 — Skip unnecessary PCM processing

**Status:** `DONE_UNMEASURED`  
**Source:** PR #10

初期化済みAudioTrackが存在しない場合、不要なPCM copy / normalizationを行わない。

**Expected effect:** Audio output unavailable時のCPU処理とmemory copy削減。  
**Measured result:** 未測定。

### OPT-LOG-D001 — USBMUX diagnostic rate limiting

**Status:** `DONE_UNMEASURED`  
**Source:** PR #1

高頻度USBMUX loggingを制限し、追加USB diagnosticsをdebug設定時に限定する。

**Expected effect:** hot pathの文字列生成、logging I/O、diagnostic trafficを削減。  
**Measured result:** 未測定。

### OPT-THR-D001 — Wireless teardown off Main Thread

**Status:** `DONE_UNMEASURED`  
**Source:** PR #11

blocking teardownをMain Thread外へdispatchする。

**Expected effect:** wireless teardown時のUI stall回避。  
**Measured result:** 未測定。

### OPT-VID-D001 — Decoder attempt deduplication

**Status:** `DONE_UNMEASURED`  
**Source:** PR #11

同一codec/formatのdecoder configuration attemptを重複実行せず、rejectされたdecoder candidateをreleaseする。

**Expected effect:** recovery時の不要なMediaCodec構築とresource保持を削減。  
**Measured result:** 未測定。

### OPT-WEB-D001 — Process-scoped GeckoRuntime

**Status:** `DONE_UNMEASURED`  
**Source:** PR #12

GeckoRuntimeをprocess-scopedで再利用し、Activityごとのruntime再生成を避ける。

**Expected effect:** Split Mode再入場時のruntime初期化コスト削減。  
**Measured result:** Target hardwareでは未測定。

### OPT-WEB-D002 — Deferred Gecko warm-up

**Status:** `DONE_UNMEASURED`  
**Source:** PR #12

GeckoRuntime warm-upを最初のsplit frame描画後へ遅延する。

**Expected effect:** 初期CarPlay UI描画とGecko初期化のCPU競合を軽減。  
**Measured result:** 未測定。

### OPT-UI-D001 — Resize event coalescing

**Status:** `DONE_UNMEASURED`  
**Source:** PR #12

連続するpane resizeをcoalesceし、settled sizeをcontrollerへ適用する。

**Expected effect:** 不要なdisplay resize処理とCarPlay restartを削減。  
**Measured result:** restart抑制のregression coverageあり。性能差分は未測定。

### OPT-PKG-D001 — ABI-specific APKs

**Status:** `DONE_UNMEASURED`  
**Source:** PR #12

arm64-v8a、armeabi-v7a、x86_64ごとのAPKを生成し、universal APKを生成しない。

**Expected effect:** 端末に不要なnative libraryを含めないことで配布APKを小さくする。  
**Measured result:** APK size comparisonは未記録。

## No-gain / Rejected Optimizations

現在なし。

採用しなかった最適化も、次の情報を短く残す。

- Optimization ID
- 試した内容
- Before / After
- 測定条件
- 採用しなかった理由
- 関連PR / commit

## Entry Template

```md
### OPT-AREA-NNN — Title

**Status:** `PLANNED | MEASURING | IN_PROGRESS | DONE | DONE_UNMEASURED | NO_GAIN | DEFERRED`
**Source:** PR #N / commit SHA
**Environment:** device / Android / iPhone / transport / workload

**Target:** 何を改善するか。

**Change:** 実施内容。

**Before:** 数値または未測定。
**After:** 数値または未測定。
**Result:** 改善率、差分、または採用しなかった理由。

**Regression checks:** 性能以外に維持を確認した項目。
```
