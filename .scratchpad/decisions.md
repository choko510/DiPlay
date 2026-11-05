# 設計判断 — 変更時に守る契約

詳細な旧判断・レビュー経緯は[整理前の決定集](https://github.com/choko510/DiPlay/blob/7198329afeb0c77fe57a7d861e26a2ba7b651d02/.scratchpad/decisions.md)。**このファイルは現行実装に重要な判断だけ**を保持し、確認していない実機動作を断定しない。

## 開発・証拠

- **固定ベース：** PR #45はfork mainを基準とし、上流差分を一括で取り込まない。EX-08の元 `source_review_state` は出典、現在のdispositionは審査結果。`NEED_EVIDENCE` は合格ではない。候補ごとに必要な挙動・テスト・権限を判断する。
- **品質ゲート：** docs/markdown/workflowのみの変更は静的チェックでよいが、製品/未知の変更は適切なunit/native/ビルド/JNI/instrumentationゲートを通す。スキップやキャッシュを実機試験と混同しない。CI結果はSHA単位で扱う。
- **プライバシー：** トレースは明示的opt-in・上限付き・ペイロードなし・端末内に限定。MAC/端末名/認証情報/音声・画面データを診断やLogcatに常時書かない。

## 有線接続

- **所有権：** USB permission、Controller、connection epoch、非同期startup/teardownを同じ有効世代で検証。古いretry・callback・closeが次セッションを破壊しない。閉鎖バリアを完了前に通過しない。
- **自動起動：** Apple USB attachはopt-in・初期OFF。現在の端末/descriptor/interface、USB permission、`MANAGE_USB`の入口制約を守る。VID/PIDで個体同一視せず、意図された再試行はdebounceで消さない。
- **USBMUX：** 永続IN requestは単一ownerに保ち、`requestWait(timeout)` は完了待ち継続とみなす。`null`・queue失敗・閉鎖とは区別し、bufferを勝手に再利用しない。
- **NCM：** CDC Unionからinterfaceを対応させ、送受信の証拠でreadyを認定。NTB16/NDP/MTU/paddingを境界検証し、不完全NTBの誤resyncを避け、処理量も有界に保つ。8秒観測/15秒hard timeoutを維持し、早いcontrol成功だけで画面準備完了にしない。
- **通信設定：** WIREDで不使用のhotspot資格情報を必須にしない。WIRELESSの検証・OS permissionは維持。BYD専用制御、root/appops書換え、無条件追加StartSessionは採用しない。

## 映像・Split・入力

- **LOCAL Split：** 分割はAndroid表示ペインだけの変更。AirPlay側のremote canvas/暗黙単一ViewArea wire互換を保つ。明示的ViewAreaではsafeAreaを全辺内包・偶数画素整列し、領域重複がなければ拒否。dynamic ViewAreaは別ゲート。
- **動画：** MAIN/ALTそれぞれのscreen ownerでcallback・keyframe要求・decoder停止・Surface解放を直列化。解放ACK前に新ownerを採用しない。Surface recoveryは実機計測までdefault-off。`receivedToCodecSurfaceReleaseMs` はパネル表示時刻ではない。
- **入力：** タッチのmoveはまとめても、down/up/cancel境界は黙って捨てない。上限に達した場合は全接触cancelで固着を防ぐ。
- **Host遷移：** hidden DSP paneはBackを奪わない。HostからのCarPlay/YouTube Split退出もSave/Discard/Continueを経由し、新しい退出要求が古い非同期遷移に上書きされない。ブラウザーの再利用/保存領域は検証済み電話identityに結ぶ。

## 音声とDSP

- **マイク：** uplink開始は明示的voice request policyで制御し、Siri/通話等の分類とdownlink MEDIA動作を分離する。実機AEC/AGC合格を主張しない。
- **音声キュー：** 192 packetのdrop-newestを同一実機A/Bまで保持。RTP受信・デコード・AudioTrack処理・A/V時刻の問題を混ぜて評価しない。
- **DSP OFF：** master off/MEDIA限定/zero latency/PCM16 byte-identical bypassを維持。DSP失敗時は安全な旧経路に戻し、不確かなnative graphを続行しない。
- **graph寿命：** renderer/format epoch/generationに属するprepared/fallback状態を一貫して扱い、retire/closeはaudio callbackから隔離。解放失敗はそのrendererでstickyとし、次graphを無条件に公開しない。Seat/Convolver/fadeの遅延はトラックのformatと時間軸に合わせる。
- **HOT更新：** bounded queue、世代とowner fenceを利用し、queue generationをslot indexより先にpublish。UI/ファイル保存・大きなgraph構築や解放を音声処理経路に置かない。
- **ゲイン安全性：** 保護attenuationは即時、返還は関連モジュールgainの収束後に20ms ramp。判定にはキャンセル前のrequested dBではなく**現在の有効linear gain**を使い、最終サンプルを正確なtargetへ。ACKは実際に収束した後。待機中の旧rampもowner/format/generationで止める。
- **性能：** エミュレーターのwall p99・thread CPU p99を区別し、Debug/Release最適化差や競合実行時の尾部を明示。p99閾値を安易に緩めず、TS10/K706のCPU/遅延/熱/音質は別途確認。

## DSP Studioの状態管理

- **タブ：** Studio（Macro/基本）、EQ（PEQ）、Spatial（Width/Mono Bass/Seat/M/S）、Dynamics（Smart Loudness/Dynamic Bass/Compressor系）、Advanced（Headroom/Cabin/Harmonic/Convolver/診断）。旧設定の編集到達性を保つ。
- **保存：** 編集中previewと確定Workspaceは別。runtime再生成は確定状態から復元し、未保存draftを永続化しない。presetは担当moduleだけ書き換える。
- **custom保存：** `custom1/custom2` は最後の確定Workspaceから対象profileだけ更新し、旧customファイルとジャーナル/rollback/recoveryで原子的な結果にする。FutureSchemaを破壊しない。
- **Cancel復元：** 開始時のrenderer ownerを保持し、存在したownerが消えたなら正当なownerから対象generation ACTIVEの証拠が得られるまで待つ。timeout時にSavedを偽装しない。
- **互換性：** schema v7・既存master設定・旧profile migration・Android AtomicFileを尊重。Windows Robolectric向けwriter差替えを製品実装へ持ち込まない。
