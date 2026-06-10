package com.mathreader.boox;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JS 桥：boox-pen.js 把当前可写画布区域传进来，原生侧用 Onyx TouchHelper
 * 在该区域内做低延迟直渲染书写；抬笔后把整笔触点回传给页面，由页面以合成
 * PointerEvent 回放，复用 PWA 自己的笔迹提交/撤销/保存逻辑。
 *
 * TouchHelper 的用法（setLimitRect → openRawDrawing → setRawDrawingEnabled，
 * 动态改区域时先 setRawDrawingEnabled(false)）与官方 OnyxPenDemo 各示例一致。
 */
public class BooxPenBridge {
    private static final String TAG = "BooxPenBridge";

    private final Activity activity;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TouchHelper touchHelper;
    private boolean sdkAvailable;
    private boolean rawOpened;
    // JS 侧期望的开关状态，onPause/onResume 时据此恢复
    private volatile boolean wantEnabled;

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean b, TouchPoint touchPoint) {
        }

        @Override
        public void onEndRawDrawing(boolean b, TouchPoint touchPoint) {
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint touchPoint) {
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList touchPointList) {
            sendStroke(touchPointList, false);
        }

        @Override
        public void onBeginRawErasing(boolean b, TouchPoint touchPoint) {
        }

        @Override
        public void onEndRawErasing(boolean b, TouchPoint touchPoint) {
        }

        @Override
        public void onRawErasingTouchPointMoveReceived(TouchPoint touchPoint) {
        }

        @Override
        public void onRawErasingTouchPointListReceived(TouchPointList touchPointList) {
            sendStroke(touchPointList, true);
        }
    };

    public BooxPenBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        try {
            touchHelper = TouchHelper.create(webView, rawInputCallback);
            sdkAvailable = touchHelper != null;
        } catch (Throwable t) {
            Log.w(TAG, "Onyx Pen SDK unavailable, fallback to plain WebView: " + t);
            sdkAvailable = false;
        }
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return sdkAvailable;
    }

    /**
     * json: {"rects":[[l,t,r,b],...], "width":4.5}，坐标为 WebView 视图内的物理像素。
     */
    @JavascriptInterface
    public void setRects(final String json) {
        if (!sdkAvailable) {
            return;
        }
        mainHandler.post(() -> applyRects(json));
    }

    @JavascriptInterface
    public void disable() {
        if (!sdkAvailable) {
            return;
        }
        mainHandler.post(this::disableInternal);
    }

    /**
     * 短暂退出直渲染并强制重绘，让 WebView 中已提交的笔迹内容刷新上屏
     * （撤销/清空/橡皮擦除后调用）。
     */
    @JavascriptInterface
    public void refresh() {
        if (!sdkAvailable) {
            return;
        }
        mainHandler.post(() -> {
            if (!wantEnabled) {
                return;
            }
            try {
                touchHelper.setRawDrawingEnabled(false);
            } catch (Throwable t) {
                Log.w(TAG, "refresh disable failed", t);
            }
            webView.invalidate();
            mainHandler.postDelayed(() -> {
                if (wantEnabled) {
                    try {
                        touchHelper.setRawDrawingEnabled(true);
                    } catch (Throwable t) {
                        Log.w(TAG, "refresh enable failed", t);
                    }
                }
            }, 260);
        });
    }

    private void applyRects(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.getJSONArray("rects");
            float width = (float) obj.optDouble("width", 4.0);
            List<Rect> rects = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray r = arr.getJSONArray(i);
                Rect rect = new Rect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3));
                if (!rect.isEmpty()) {
                    rects.add(rect);
                }
            }
            if (rects.isEmpty()) {
                disableInternal();
                return;
            }
            touchHelper.setRawDrawingEnabled(false);
            touchHelper.setStrokeWidth(width).setLimitRect(rects, new ArrayList<>());
            if (!rawOpened) {
                touchHelper.openRawDrawing();
                rawOpened = true;
                touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL);
            }
            if (rects.size() > 1) {
                touchHelper.setMultiRegionMode();
            } else {
                touchHelper.setSingleRegionMode();
            }
            touchHelper.setRawDrawingEnabled(true);
            wantEnabled = true;
        } catch (Throwable t) {
            Log.w(TAG, "setRects failed: " + json, t);
        }
    }

    private void disableInternal() {
        wantEnabled = false;
        try {
            touchHelper.setRawDrawingEnabled(false);
        } catch (Throwable t) {
            Log.w(TAG, "disable failed", t);
        }
        webView.invalidate();
    }

    private void sendStroke(TouchPointList touchPointList, boolean erase) {
        if (touchPointList == null || touchPointList.getPoints() == null
                || touchPointList.getPoints().isEmpty()) {
            return;
        }
        List<TouchPoint> points = touchPointList.getPoints();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            TouchPoint p = points.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[')
                    .append(String.format(Locale.US, "%.1f", p.getX())).append(',')
                    .append(String.format(Locale.US, "%.1f", p.getY())).append(',')
                    .append(String.format(Locale.US, "%.2f", normalizePressure(p.getPressure())))
                    .append(']');
        }
        sb.append(']');
        final String js = "window.__booxPen&&window.__booxPen.onStroke(" + sb + "," + erase + ");";
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    private static float normalizePressure(float pressure) {
        if (pressure <= 0f) {
            return 0.5f;
        }
        if (pressure <= 1f) {
            return pressure;
        }
        // 部分设备回报 0~4096 的原始压感值
        return Math.min(1f, pressure / 4096f);
    }

    public void onResume() {
        if (sdkAvailable && wantEnabled) {
            try {
                touchHelper.setRawDrawingEnabled(true);
            } catch (Throwable t) {
                Log.w(TAG, "onResume enable failed", t);
            }
        }
    }

    public void onPause() {
        if (sdkAvailable) {
            try {
                touchHelper.setRawDrawingEnabled(false);
            } catch (Throwable t) {
                Log.w(TAG, "onPause disable failed", t);
            }
        }
    }

    public void onDestroy() {
        if (sdkAvailable && rawOpened) {
            try {
                touchHelper.closeRawDrawing();
            } catch (Throwable t) {
                Log.w(TAG, "closeRawDrawing failed", t);
            }
        }
    }
}
