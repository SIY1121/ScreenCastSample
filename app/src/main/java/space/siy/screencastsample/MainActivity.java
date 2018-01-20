package space.siy.screencastsample;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity implements Runnable {

    Button button_start;
    SeekBar seekBar_scale;
    SeekBar seekBar_bitrate;
    SeekBar seekBar_fps;
    Spinner spinner_codec;
    TextView textView_status;
    TextView textView_scale;
    TextView textView_bitrate;
    TextView textView_FPS;

    //コーデックのリスト
    String[] codecs = new String[]{
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9};

    MediaProjectionManager manager;//MediaProjectionを取得するためのManager
    final int REQUEST_CODE = 1;

    MediaProjection mediaProjection;//仮想ディスプレイを作成するのに使用
    VirtualDisplay virtualDisplay;//仮想ディスプレイ、ミラーモード

    Surface inputSurface;//エンコーダにフレームを渡すためのSurface
    MediaCodec codec;//エンコーダ

    Thread serverThread;//ポートを待ち受けるスレッド
    HandlerThread senderThread;//送信用スレッド
    Handler senderHandler;//送信用スレッドのハンドラ


    ServerSocket listener;//サーバーソケット

    Socket clientSocket;//クライアント側へのソケット
    InputStream inputStream;//クライアントからのメッセージ受信用、今回は未使用
    OutputStream outputStream;//クライアントへのデータ送信用ストリーム

    //ステータス管理
    enum States {
        Stop, Waiting, Running
    }

    States states;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        button_start = (Button) findViewById(R.id.button_start);
        seekBar_scale = (SeekBar) findViewById(R.id.seekBar_scale);
        seekBar_bitrate = (SeekBar) findViewById(R.id.seekBar_bitrate);
        seekBar_fps = (SeekBar) findViewById(R.id.seekBar_fps);
        spinner_codec = (Spinner) findViewById(R.id.spinner_codec);
        textView_status = (TextView) findViewById(R.id.textView_status);
        textView_scale = (TextView) findViewById(R.id.textView_scale);
        textView_bitrate = (TextView) findViewById(R.id.textView_bitrate);
        textView_FPS = (TextView) findViewById(R.id.textView_fps);


        //Spinnerにコーデックリストをセット
        spinner_codec.setAdapter(new ArrayAdapter<String>(this, R.layout.support_simple_spinner_dropdown_item, codecs));

        //シークバーのリスナー、一括で
        SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                switch (seekBar.getId()) {
                    case R.id.seekBar_scale:
                        textView_scale.setText("Scale : " + progress + "%");
                        break;
                    case R.id.seekBar_bitrate:
                        textView_bitrate.setText("Bitrate : " + (progress * 0.001) + "Kbps");
                        break;
                    case R.id.seekBar_fps:
                        textView_FPS.setText("FPS : " + progress);
                        break;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        };

        seekBar_scale.setOnSeekBarChangeListener(seekBarChangeListener);
        seekBar_bitrate.setOnSeekBarChangeListener(seekBarChangeListener);
        seekBar_fps.setOnSeekBarChangeListener(seekBarChangeListener);

        button_start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                switch (states) {
                    case Stop:
                        //キャプチャ確認用のダイアログを表示
                        manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE);
                        break;
                    case Waiting:
                        //待受をキャンセル
                        Disconnect();
                        break;
                    case Running:
                        //切断
                        Disconnect();
                        break;
                }

            }
        });

        //初期状態はStop
        setState(States.Stop);

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "permission denied", Toast.LENGTH_LONG).show();
            return;
        }

        //ユーザーが画面キャプチャを承認した場合
        //MediaProjectionを取得
        mediaProjection = manager.getMediaProjection(resultCode, intent);


        //仮想ディスプレイのサイズを決定
        double SCALE = seekBar_scale.getProgress() * 0.01;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int WIDTH = (int) (metrics.widthPixels * SCALE);
        final int HEIGHT = (int) (metrics.heightPixels * SCALE);
        final int DENSITY = metrics.densityDpi;


        try {

            PrepareEncoder(
                    WIDTH,
                    HEIGHT,
                    codecs[spinner_codec.getSelectedItemPosition()],
                    seekBar_bitrate.getProgress(),
                    seekBar_fps.getProgress(),
                    10//Iフレームは固定で
            );

            SetupVirtualDisplay(WIDTH, HEIGHT, DENSITY);

            StartServer();



        } catch (Exception ex) {//エンコーダ作成時のエラーとか
            ex.printStackTrace();
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
        }


    }

    //仮想ディスプレイのセットアップ
    private void SetupVirtualDisplay(int WIDTH, int HEIGHT, int DENSITY) {

        virtualDisplay = mediaProjection
                .createVirtualDisplay("Capturing Display",
                        WIDTH, HEIGHT, DENSITY,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface, null, null);//書き込むSurfaceにエンコーダーから取得したものを使用
    }

    //エンコーダの準備
    private void PrepareEncoder(int WIDTH, int HEIGHT, String MIME_TYPE, int BIT_RATE, int FPS, int IFRAME_INTERVAL) throws Exception {

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        //フォーマットのプロパティを設定
        //最低限のプロパティを設定しないとconfigureでエラーになる
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);


        //エンコーダの取得
        codec = MediaCodec.createEncoderByType(MIME_TYPE);

        codec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.d("MediaCodec", "onInputBufferAvailable : " + codec.getCodecInfo());

            }

            @Override
            public void onOutputBufferAvailable(@NonNull final MediaCodec codec, final int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d("MediaCodec", "onOutputBufferAvailable : " + info.toString());
                ByteBuffer buffer = codec.getOutputBuffer(index);
                byte[] array = new byte[buffer.limit()];
                buffer.get(array);

                //エンコードされたデータを送信
                Send(array);

                //バッファを解放
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.d("MediaCodec", "onError : " + e.getMessage());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.d("MediaCodec", "onOutputFormatChanged : " + format.getString(MediaFormat.KEY_MIME));
            }
        });

        //エンコーダを設定
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        //エンコーダにフレームを渡すのに使うSurfaceを取得
        //configureとstartの間で呼ぶ必要あり
        inputSurface = codec.createInputSurface();

    }

    //ステータスをUIに反映
    private void setState(final States states) {
        this.states = states;
        new Handler(getMainLooper()).post(new Runnable() {
            @Override
            public void run() {

                textView_status.setText(String.valueOf(states));

                switch (states) {
                    case Stop:
                        button_start.setText("Start");
                        setSettingUIEnabled(true);
                        break;
                    case Waiting:
                        button_start.setText("Cancel");
                        setSettingUIEnabled(false);
                        break;
                    case Running:
                        button_start.setText("Stop");
                        setSettingUIEnabled(false);
                        break;
                }
            }
        });
    }

    //設定系のUIの有効、無効化
    private void setSettingUIEnabled(boolean value) {
        seekBar_scale.setEnabled(value);
        seekBar_bitrate.setEnabled(value);
        seekBar_fps.setEnabled(value);
        spinner_codec.setEnabled(value);
    }


    //待受用、送信用のスレッドを開始
    private void StartServer() {
        senderThread = new HandlerThread("senderThread");
        senderThread.start();
        senderHandler = new Handler(senderThread.getLooper());

        serverThread = new Thread(this);
        serverThread.start();

        setState(States.Waiting);
    }

    //サーバースレッド
    //接続は1回きり受け付ける
    public void run() {
        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(8080));
            System.out.println("Server listening on port 8080...");

            clientSocket = listener.accept();//接続まで待機

            inputStream = clientSocket.getInputStream();
            outputStream = clientSocket.getOutputStream();

            //クライアントが接続されたタイミングでエンコードを開始する必要あり
            codec.start();

            setState(States.Running);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //データを送信
    //順番が入れ替わらないように
    //キューに追加
    private void Send(final byte[] array) {
        senderHandler.post(new Runnable() {
            @Override
            public void run() {

                try {
                    outputStream.write(array);
                } catch (IOException ex) {
                    //送信できなかった場合、切断されたとみなす
                    ex.printStackTrace();
                    Disconnect();
                }

            }
        });
    }

    //切断処理
    private void Disconnect() {

        try {
            codec.stop();
            codec.release();
            virtualDisplay.release();
            mediaProjection.stop();


            listener.close();
            if (clientSocket != null)
                clientSocket.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        setState(States.Stop);
    }

}
