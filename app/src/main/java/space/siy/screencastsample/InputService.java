package space.siy.screencastsample;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Created by sota on 2018/01/24.
 */

public class InputService {
    InputManager im;
    Method injectInputEventMethod;


    public InputService() throws Exception {

        //InputManagerのインスタンスを取得
        im = (InputManager) InputManager.class.getDeclaredMethod("getInstance").invoke(null, new Object[0]);

        //MotionEventを生成するstaticメソッドを呼べるようにする
        MotionEvent.class.getDeclaredMethod(
                "obtain",
                long.class, long.class, int.class, int.class,
                MotionEvent.PointerProperties[].class, MotionEvent.PointerCoords[].class,
                int.class, int.class, float.class, float.class, int.class, int.class, int.class, int.class
        ).setAccessible(true);

        //システムにイベントを介入させるメソッドを取得
        injectInputEventMethod = InputManager.class.getDeclaredMethod("injectInputEvent", new Class[]{InputEvent.class, int.class});

    }

    //タッチイベントを生成
    public void injectMotionEvent(int inputSource, int action, float x, float y) throws InvocationTargetException, IllegalAccessException {

        MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[1];
        pointerProperties[0] = new MotionEvent.PointerProperties();
        pointerProperties[0].id = 0;


        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[1];
        pointerCoords[0] = new MotionEvent.PointerCoords();
        pointerCoords[0].pressure = 1;
        pointerCoords[0].size = 1;
        pointerCoords[0].touchMajor = 1;
        pointerCoords[0].touchMinor = 1;
        pointerCoords[0].x = x;
        pointerCoords[0].y = y;

        MotionEvent event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, 1, pointerProperties, pointerCoords, 0, 0, 1, 1, 0, 0, inputSource, 0);
        injectInputEventMethod.invoke(im, new Object[]{event, 0});
    }

    //キーイベントを生成
    public void injectKeyEvent(KeyEvent event)throws InvocationTargetException, IllegalAccessException{
        injectInputEventMethod.invoke(im, new Object[]{event, 0});
    }
}
