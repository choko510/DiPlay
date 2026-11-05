# PR #45 Working Memory

このディレクトリは、次の作業者がすぐに着手するための**現在有効な情報**だけを置く。コミット履歴やCI実行ログの代わりにはしない。

| ファイル | 用途 |
| --- | --- |
| [current.md](current.md) | 実装・検証の現状。最初に読む |
| [backlog.md](backlog.md) | 未完了の受入れ条件・追加調査 |
| [decisions.md](decisions.md) | 重要な設計判断と互換性の制約 |
| [quirks.md](quirks.md) | 再利用できる環境上の問題と回避策 |

- 完了した作業を日付順に追記しない。実装履歴はPR・コミット・Actionsにある。
- 状態が変わったときだけ `current.md` を更新し、解消した項目を `backlog.md` から除く。
- 技術判断は理由と再検討条件に絞り、重複を避ける。性能の未測定項目は `backlog.md` に記す。
- PASS / PARTIAL / NOT_RUN / BLOCKED_ENV を区別し、別SHA・別端末・省略されたジョブの結果を流用しない。
- 認証情報、端末識別情報、個人情報、通信ペイロードを記録しない。

削除した `completed.md`、`lessons.md`、`optimization.md` を含む**整理前の記録は**[元のコミット](https://github.com/choko510/DiPlay/tree/7198329afeb0c77fe57a7d861e26a2ba7b651d02/.scratchpad)から確認・復元できる。過去のPASSを現行HEADのPASSと解釈しない。
