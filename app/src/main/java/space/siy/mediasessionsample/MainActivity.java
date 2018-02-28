package space.siy.mediasessionsample;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    MediaBrowserCompat mBrowser;
    MediaControllerCompat mController;


    TextView textView_title;
    TextView textView_position;
    TextView textView_duration;
    ImageButton button_prev;
    ImageButton button_next;
    FloatingActionButton button_play;
    ImageView imageView;
    SeekBar seekBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //UI系のセットアップ

        textView_title = findViewById(R.id.textView_title);
        textView_position = findViewById(R.id.textView_position);
        textView_duration = findViewById(R.id.textView_duration);
        button_next = findViewById(R.id.button_next);
        button_prev = findViewById(R.id.button_prev);
        button_play = findViewById(R.id.button_play);
        imageView = findViewById(R.id.imageView);
        seekBar = findViewById(R.id.seekBar);

        button_prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.getTransportControls().skipToPrevious();
            }
        });

        button_next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.getTransportControls().skipToNext();
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                //シークする
                mController.getTransportControls().seekTo(seekBar.getProgress());
            }
        });


        //サービスは開始しておく
        //Activity破棄と同時にServiceも停止して良いならこれは不要
        startService(new Intent(this, MusicService.class));

        //MediaBrowserを初期化
        mBrowser = new MediaBrowserCompat(this, new ComponentName(this, MusicService.class), connectionCallback, null);
        //接続(サービスをバインド)
        mBrowser.connect();
    }

    //接続時に呼び出されるコールバック
    private MediaBrowserCompat.ConnectionCallback connectionCallback = new MediaBrowserCompat.ConnectionCallback() {
        @Override
        public void onConnected() {
            try {
                //接続が完了するとSessionTokenが取得できるので
                //それを利用してMediaControllerを作成
                mController = new MediaControllerCompat(MainActivity.this, mBrowser.getSessionToken());
                //サービスから送られてくるプレイヤーの状態や曲の情報が変更された際のコールバックを設定
                mController.registerCallback(controllerCallback);

                //既に再生中だった場合コールバックを自ら呼び出してUIを更新
                if (mController.getPlaybackState() != null && mController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING) {
                    controllerCallback.onMetadataChanged(mController.getMetadata());
                    controllerCallback.onPlaybackStateChanged(mController.getPlaybackState());
                }


            } catch (RemoteException ex) {
                ex.printStackTrace();
                Toast.makeText(MainActivity.this, ex.getMessage(), Toast.LENGTH_LONG).show();
            }
            //サービスから再生可能な曲のリストを取得
            mBrowser.subscribe(mBrowser.getRoot(), subscriptionCallback);
        }
    };

    //Subscribeした際に呼び出されるコールバック
    private MediaBrowserCompat.SubscriptionCallback subscriptionCallback = new MediaBrowserCompat.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            //既に再生中でなければ初めの曲を再生をリクエスト
            if (mController.getPlaybackState() == null)
                Play(children.get(0).getMediaId());
        }
    };

    //MediaControllerのコールバック
    private MediaControllerCompat.Callback controllerCallback = new MediaControllerCompat.Callback() {
        //再生中の曲の情報が変更された際に呼び出される
        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            textView_title.setText(metadata.getDescription().getTitle());
            imageView.setImageBitmap(metadata.getDescription().getIconBitmap());
            textView_duration.setText(Long2TimeString(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)));
            seekBar.setMax((int) metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION));
        }

        //プレイヤーの状態が変更された時に呼び出される
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {

            //プレイヤーの状態によってボタンの挙動とアイコンを変更する
            if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                button_play.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mController.getTransportControls().pause();
                    }
                });
                button_play.setImageResource(R.drawable.exo_controls_pause);
            } else {
                button_play.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mController.getTransportControls().play();
                    }
                });
                button_play.setImageResource(R.drawable.exo_controls_play);
            }

            textView_position.setText(Long2TimeString(state.getPosition()));
            seekBar.setProgress((int) state.getPosition());

        }
    };

    private void Play(String id) {
        //MediaControllerからサービスへ操作を要求するためのTransportControlを取得する
        //playFromMediaIdを呼び出すと、サービス側のMediaSessionのコールバック内のonPlayFromMediaIdが呼ばれる
        mController.getTransportControls().playFromMediaId(id, null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBrowser.disconnect();
        if (mController.getPlaybackState().getState() != PlaybackStateCompat.STATE_PLAYING)
            stopService(new Intent(this, MusicService.class));
    }

    //Long値をm:ssの形式の文字列にする
    private String Long2TimeString(long src) {
        String mm = String.valueOf(src / 1000 / 60);
        String ss = String.valueOf((src / 1000) % 60);

        //秒は常に二桁じゃないと変
        if (ss.length() == 1) ss = "0" + ss;

        return mm + ":" + ss;
    }
}
