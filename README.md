# Apache OFBiz Support

金融商品（ローン・リース）と契約に関する顧客の問い合わせを、案件（ケース）単位で管理するプラグイン。

- 要件：`REQUIREMENTS_SUPPORT.md`
- 仕様：`SPEC_SUPPORT.md`
- 状態：S0（基盤）・S1（ケース管理）・S2（メッセージ・スレッド・自動起票）・S3（自動割当・SLA・エスカレーション）・S4（顧客 API）・S5（レポート・分析）を実装済み。S6 以降は仕様書の STEP を参照。
- 依存：`support → finance`、core（party / workeffort / content / product）。`finance` は `support` を参照しない。
- 画面：`/support`（スタッフ向け）。顧客向けの画面は持たず、API は efinance から利用する。
