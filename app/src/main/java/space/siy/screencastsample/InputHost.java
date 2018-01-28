package space.siy.screencastsample;

import android.support.v4.view.InputDeviceCompat;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Created by sota on 2018/01/24.
 */

public class InputHost {
    static InputService inputService;

    static ServerSocket listener;//サーバーソケット

    static Socket clientSocket;//クライアント側へのソケット
    static InputStream inputStream;//クライアントからのメッセージ受信用
    static OutputStream outputStream;//クライアントへのデータ送信用ストリーム


    static boolean runnning = false;


    public static void main(String args[]) {
        try {
            inputService = new InputService();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(8081));
            System.out.println("Server listening on port 8081...");

            clientSocket = listener.accept();//接続まで待機

            System.out.println("Connected");

            inputStream = clientSocket.getInputStream();
            outputStream = clientSocket.getOutputStream();

            runnning = true;

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            while (runnning) {
                String msg = reader.readLine();
                String[] data = msg.split(" ");

                if (data.length > 0) {
                    if (data[0].equals("screen")) {//タッチデータの場合
                        inputService.injectMotionEvent(InputDeviceCompat.SOURCE_TOUCHSCREEN, Integer.valueOf(data[1]), Integer.valueOf(data[2]), Integer.valueOf(data[3]));
                    } else if (data[0].equals("key")) {//キーの場合
                        inputService.injectKeyEvent(new KeyEvent(Integer.valueOf(data[1]), Integer.valueOf(data[2])));
                    } else if (data[0].equals("exit")) {//終了コール
                        Disconnect();
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            Disconnect();
        }

    }

    //切断処理
    private static void Disconnect() {

        runnning = false;

        try {

            listener.close();
            if (clientSocket != null)
                clientSocket.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        System.out.println("Disconnected");

    }
}
