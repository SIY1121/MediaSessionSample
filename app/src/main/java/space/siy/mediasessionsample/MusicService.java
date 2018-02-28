package space.siy.mediasessionsample;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by sota on 2018/02/24.
 */

public class MusicService extends MediaBrowserServiceCompat {
    final String TAG = MusicService.class.getSimpleName();//ログ用タグ
    final String ROOT_ID = "root";//クライアントに返すID onGetRoot / onLoadChildrenで使用

    Handler handler;//定期的に処理を回すためのHandler

    MediaSessionCompat mSession;//主役のMediaSession
    AudioManager am;//AudioFoucsを扱うためのManager

    int index = 0;//再生中のインデックス

    ExoPlayer exoPlayer;//音楽プレイヤーの実体

    List<MediaSessionCompat.QueueItem> queueItems = new ArrayList<>();//キューに使用するリスト

    @Override
    public void onCreate() {
        super.onCreate();
        //AudioManagerを取得
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        //MediaSessionを初期化
        mSession = new MediaSessionCompat(getApplicationContext(), TAG);
        //このMediaSessionが提供する機能を設定
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | //ヘッドフォン等のボタンを扱う
                MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS | //キュー系のコマンドの使用をサポート
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS); //再生、停止、スキップ等のコントロールを提供

        //クライアントからの操作に応じるコールバックを設定
        mSession.setCallback(callback);

        //MediaBrowserServiceにSessionTokenを設定
        setSessionToken(mSession.getSessionToken());

        //Media Sessionのメタデータや、プレイヤーのステータスが更新されたタイミングで
        //通知の作成/更新をする
        mSession.getController().registerCallback(new MediaControllerCompat.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackStateCompat state) {
                CreateNotification();
            }

            @Override
            public void onMetadataChanged(MediaMetadataCompat metadata) {
                CreateNotification();
            }
        });


        //キューにアイテムを追加
        int i = 0;
        for (MediaBrowserCompat.MediaItem media : MusicLibrary.getMediaItems()) {
            queueItems.add(new MediaSessionCompat.QueueItem(media.getDescription(), i));
            i++;
        }
        mSession.setQueue(queueItems);//WearやAutoにキューが表示される


        //exoPlayerの初期化
        exoPlayer = ExoPlayerFactory.newSimpleInstance(getApplicationContext(), new DefaultTrackSelector());
        //プレイヤーのイベントリスナーを設定
        exoPlayer.addListener(eventListener);

        handler = new Handler();
        //500msごとに再生情報を更新
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                //再生中にアップデート
                if (exoPlayer.getPlaybackState() == Player.STATE_READY && exoPlayer.getPlayWhenReady())
                    UpdatePlaybackState();

                //再度実行
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    //クライアント接続時に呼び出される
    //パッケージ名などから接続するかどうかを決定する
    //任意の文字列を返すと接続許可
    //nullで接続拒否
    //今回は全ての接続を許可
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                 int clientUid,
                                 Bundle rootHints) {
        Log.d(TAG, "Connected from pkg:" + clientPackageName + " uid:" + clientUid);
        return new BrowserRoot(ROOT_ID, null);
    }

    //クライアント側がsubscribeを呼び出すと呼び出される
    //音楽ライブラリの内容を返す
    //WearやAutoで表示される曲のリストにも使われる
    //デフォルトでonGetRootで返した文字列がparentMediaIdに渡される
    //ブラウザ画面で子要素を持っているMediaItemを選択した際にもそのIdが渡される
    @Override
    public void onLoadChildren(
            @NonNull final String parentMediaId,
            @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {

        if (parentMediaId.equals(ROOT_ID))
            //曲のリストをクライアントに送信
            result.sendResult(MusicLibrary.getMediaItems());
        else//今回はROOT_ID以外は無効
            result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mSession.setActive(false);
        mSession.release();
        exoPlayer.stop();
        exoPlayer.release();
    }


    //MediaSession用コールバック
    private MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {

        //曲のIDから再生する
        //WearやAutoのブラウジング画面から曲が選択された場合もここが呼ばれる
        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            //今回はAssetsフォルダに含まれる音声ファイルを再生
            //Uriから再生する
            DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(getApplicationContext(), Util.getUserAgent(getApplicationContext(), "AppName"));
            MediaSource mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse("file:///android_asset/" + MusicLibrary.getMusicFilename(mediaId)));

            //今回は簡易的にmediaIdからインデックスを割り出す。
            for (MediaSessionCompat.QueueItem item : queueItems)
                if (item.getDescription().getMediaId().equals(mediaId))
                    index = (int) item.getQueueId();

            exoPlayer.prepare(mediaSource);

            mSession.setActive(true);

            onPlay();

            //MediaSessionが配信する、再生中の曲の情報を設定
            mSession.setMetadata(MusicLibrary.getMetadata(getApplicationContext(), mediaId));
        }

        //再生をリクエストされたとき
        @Override
        public void onPlay() {
            //オーディオフォーカスを要求
            if (am.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                //取得できたら再生を始める
                mSession.setActive(true);
                exoPlayer.setPlayWhenReady(true);
            }
        }

        //一時停止をリクエストされたとき
        @Override
        public void onPause() {
            exoPlayer.setPlayWhenReady(false);
            //オーディオフォーカスを開放
            am.abandonAudioFocus(afChangeListener);
        }

        //停止をリクエストされたとき
        @Override
        public void onStop() {
            onPause();
            mSession.setActive(false);
            //オーディオフォーカスを開放
            am.abandonAudioFocus(afChangeListener);
        }

        //シークをリクエストされたとき
        @Override
        public void onSeekTo(long pos) {
            exoPlayer.seekTo(pos);
        }

        //次の曲をリクエストされたとき
        @Override
        public void onSkipToNext() {
            index++;
            if (index >= queueItems.size())//ライブラリの最後まで再生したら
                index = 0;//最初に戻す

            onPlayFromMediaId(queueItems.get(index).getDescription().getMediaId(), null);
        }

        //前の曲をリクエストされたとき
        @Override
        public void onSkipToPrevious() {
            index--;
            if (index < 0)//インデックスが0以下になったら
                index = queueItems.size() - 1;//最後の曲に移動する

            onPlayFromMediaId(queueItems.get(index).getDescription().getMediaId(), null);
        }

        //WearやAutoでキュー内のアイテムを選択された際にも呼び出される
        @Override
        public void onSkipToQueueItem(long i) {
            onPlayFromMediaId(queueItems.get((int)i).getDescription().getMediaId(), null);
        }

        //Media Button Intentが飛んできた時に呼び出される
        //オーバーライド不要（今回はログを吐くだけ）
        //MediaSessionのplaybackStateのActionフラグに応じてできる操作が変わる
        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
            KeyEvent key = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            Log.d(TAG, String.valueOf(key.getKeyCode()));
            return super.onMediaButtonEvent(mediaButtonEvent);
        }
    };

    //プレイヤーのコールバック
    private Player.EventListener eventListener = new Player.DefaultEventListener() {
        //プレイヤーのステータスが変化した時に呼ばれる
        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            UpdatePlaybackState();
        }
    };

    //MediaSessionが配信する、現在のプレイヤーの状態を設定する
    //ここには再生位置の情報も含まれるので定期的に更新する
    private void UpdatePlaybackState() {
        int state = PlaybackStateCompat.STATE_NONE;
        //プレイヤーの状態からふさわしいMediaSessionのステータスを設定する
        switch (exoPlayer.getPlaybackState()) {
            case Player.STATE_IDLE:
                state = PlaybackStateCompat.STATE_NONE;
                break;
            case Player.STATE_BUFFERING:
                state = PlaybackStateCompat.STATE_BUFFERING;
                break;
            case Player.STATE_READY:
                if (exoPlayer.getPlayWhenReady())
                    state = PlaybackStateCompat.STATE_PLAYING;
                else
                    state = PlaybackStateCompat.STATE_PAUSED;
                break;
            case Player.STATE_ENDED:
                state = PlaybackStateCompat.STATE_STOPPED;
                break;
        }

        //プレイヤーの情報、現在の再生位置などを設定する
        //また、MeidaButtonIntentでできる操作を設定する
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_STOP)
                .setState(state, exoPlayer.getCurrentPosition(), exoPlayer.getPlaybackParameters().speed)
                .build());
    }

    //通知を作成、サービスをForegroundにする
    private void CreateNotification() {
        MediaControllerCompat controller = mSession.getController();
        MediaMetadataCompat mediaMetadata = controller.getMetadata();

        if (mediaMetadata == null && !mSession.isActive()) return;

        MediaDescriptionCompat description = mediaMetadata.getDescription();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());

        builder
                //現在の曲の情報を設定
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setSubText(description.getDescription())
                .setLargeIcon(description.getIconBitmap())

                // 通知をクリックしたときのインテントを設定
                .setContentIntent(createContentIntent())

                // 通知がスワイプして消された際のインテントを設定
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_STOP))

                // 通知の範囲をpublicにしてロック画面に表示されるようにする
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                .setSmallIcon(R.drawable.exo_controls_play)
                //通知の領域に使う色を設定
                //Androidのバージョンによってスタイルが変わり、色が適用されない場合も多い
                .setColor(ContextCompat.getColor(this, R.color.colorAccent))

                // Media Styleを利用する
                .setStyle(new android.support.v4.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mSession.getSessionToken())
                        //通知を小さくたたんだ時に表示されるコントロールのインデックスを設定
                        .setShowActionsInCompactView(1));

        // Android4.4以前は通知をスワイプで消せないので
        //キャンセルボタンを表示することで対処
        //今回はminSDKが21なので必要ない
        //.setShowCancelButton(true)
        //.setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this,
        //        PlaybackStateCompat.ACTION_STOP)));

        //通知のコントロールの設定
        builder.addAction(new NotificationCompat.Action(
                R.drawable.exo_controls_previous, "prev",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));

        //プレイヤーの状態で再生、一時停止のボタンを設定
        if (controller.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING) {
            builder.addAction(new NotificationCompat.Action(
                    R.drawable.exo_controls_pause, "pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                            PlaybackStateCompat.ACTION_PAUSE)));
        } else {
            builder.addAction(new NotificationCompat.Action(
                    R.drawable.exo_controls_play, "play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                            PlaybackStateCompat.ACTION_PLAY)));
        }


        builder.addAction(new NotificationCompat.Action(
                R.drawable.exo_controls_next, "next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));

        startForeground(1, builder.build());

        //再生中以外ではスワイプで通知を消せるようにする
        if (controller.getPlaybackState().getState() != PlaybackStateCompat.STATE_PLAYING)
            stopForeground(false);
    }

    //通知をクリックしてActivityを開くインテントを作成
    private PendingIntent createContentIntent() {
        Intent openUI = new Intent(this, MainActivity.class);
        openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this, 1, openUI, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    //オーディオフォーカスのコールバック
    AudioManager.OnAudioFocusChangeListener afChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int focusChange) {
                    //フォーカスを完全に失ったら
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        //止める
                        mSession.getController().getTransportControls().pause();
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {//一時的なフォーカスロスト
                        //止める
                        mSession.getController().getTransportControls().pause();
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {//通知音とかによるフォーカスロスト（ボリュームを下げて再生し続けるべき）
                        //本来なら音量を一時的に下げるべきだが何もしない
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {//フォーカスを再度得た場合
                        //再生
                        mSession.getController().getTransportControls().play();
                    }
                }
            };
}
