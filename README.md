# GPS到着アラーム

GPSで目的地付近への到着を検知し、バイブレーションと通知で知らせるAndroidアプリです。

## 主な機能

- 目的地の登録・変更・削除
- お気に入り登録と絞り込み
- 現在地を目的地として入力
- Googleマップをタップして目的地を選択
- 駅名・住所・施設名を検索して目的地を選択
- 到着判定距離の設定
- Geofencingによるバックグラウンド到着監視
- 到着時のバイブレーションと通知
- 監視開始・停止

## スマホだけでAPKを作る方法（GitHub Actions）

このプロジェクトには `.github/workflows/build-apk.yml` が入っています。
GitHubへアップロードすると、PCなしでもGitHub ActionsでAPKを作成できます。

### 1. GitHubに新しいリポジトリを作る

GitHubアプリまたはブラウザで新規リポジトリを作成します。公開・非公開はどちらでも構いません。

### 2. このプロジェクトの中身をアップロードする

ZIPそのものではなく、ZIPを展開した `GpsArrivalAlarm` フォルダ内のファイルをリポジトリ直下へアップロードしてください。
`.github` フォルダも必要です。

### 3. Google Maps APIキーをSecretへ登録する

GitHubリポジトリで次を開きます。

`Settings` → `Secrets and variables` → `Actions` → `New repository secret`

Name:

`MAPS_API_KEY`

Secret:

Google Maps Platformで発行したAndroid用APIキー

APIキーをソースコードへ直接書き込まないでください。

### 4. APKをビルドする

リポジトリの `Actions` → `Build Android APK` → `Run workflow` を押します。

main/masterへpushした場合にも自動的にビルドされます。

### 5. APKをスマホへダウンロード

ビルドが成功したら、そのWorkflow実行画面の `Artifacts` に

`GpsArrivalAlarm-debug-apk`

が表示されます。ダウンロードして展開すると `app-debug.apk` が入っています。

APKをタップし、Androidの「この提供元からのアプリを許可」を必要に応じて有効にしてインストールしてください。

## Google Maps APIキーをPCのAndroid Studioで使う場合

プロジェクト直下の `local.properties` に以下を追加します。

```properties
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

`local.properties` はGitへコミットしないでください。

## 位置情報権限

到着検知には位置情報権限が必要です。Android 10以降でバックグラウンド監視を利用する場合は「常に許可」も必要です。Android 11以降では端末設定画面からバックグラウンド位置情報を許可する場合があります。
