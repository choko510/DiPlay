# 未完了・根拠待ち — PR #45

## P0 — 受入れを止めているもの

- [ ] TS10/K706 + iPhoneで有線NCM/IPv6→TUN、AirPlay映像/タッチ/音声、Siri・通話・AEC/AGC、ルーティング・音量を実機確認。SH M20の結果で代替しない。
- [ ] MAIN/ALT、Surfaceの停止/再生成、rotation/safeArea/HID、Full/LOCAL Splitの同一Controller/Session維持を20サイクルで検証。
- [ ] DSPのバイパス、THD/ヘッドルーム/レイテンシ、A/V同期、CPU/RSS/FD/温度/アンダーラン、30〜60分soakを対象端末で計測。
- [ ] Android 10 TS10/K706でApple USB自動起動（初期OFF）の `MANAGE_USB` 制約、chooser/permission拒否・再試行、20回抜差し、ナビ再起動後の既接続端末、wireless→wired・Split保持を確認。VID/PIDのみで同一端末と判定しない。
- [ ] F8-01 MAIN/ALT RECORD前の切断復旧、F8-02 4MiB RTSP body互換、F8-03 4セッション上限・解放を実トラフィックで検証。
- [ ] SH-M27でDSP Splitのinset、スクロール境界、ComposeView実測を確認。既に解消したC-00は再オープンしない。
- [ ] API 35でP2 ComposeのSave/Discard/Continue操作テストを実行。API 36.1のEspresso初期化失敗はテスト結果ではない。
- [ ] 公式リリース署名で更新・既存設定の引継ぎ・インストール済みのロールバックを検証。debug署名を代用しない。
- [ ] 修正後の二回目独立レビューはユーザー指示により `NOT_RUN`。G8は `NO_GO_THIS_RUN` のまま扱う。

## P1 — 計測してから判断

- [ ] 音声192-packet drop-newest方針の変更は同一端末A/Bで遅延・欠落・再接続を比較してから。
- [ ] 動画の `receivedToCodecSurfaceReleaseMs` と**実表示遅延**を分け、MAIN/ALT、復旧、CPU/GC/FD/soakを記録。Surface recoveryは証拠が揃うまでdefault-off。ログ処理の最適化も実測後。
- [ ] NCM `AUTO` / `NO_STATUS_POLLING` のfirst-frame・`SCREEN_STREAM_OPENED`・timeoutを同端末で比較。
- [ ] USB接続中に `queue()==false` / `requestWait()==null` を再現。単なるtimeoutで永続requestを破棄しない。
- [ ] Stage3は時刻計測のみ。追加StartSessionやステータス制御要求は実際の因果が示されるまで無効。

## P2 — 条件付き候補

- [ ] EX-08 `NEED_EVIDENCE` とPass15 delta 4/24/28/44を個別の実機証拠で再審査。古い判定台帳はGit履歴を参照。
- [ ] 動的ViewArea、`ROTATION_SQUARE_CANVAS`、vendor codec override、無条件StartSession再試行は `NO_GO_THIS_RUN`。
- [ ] upstream一括取り込み・BYD OEM固有機能・root/appops権限書換え・複数ゲート混合コミットは採用しない。必要な場合も個別候補として独立審査。
