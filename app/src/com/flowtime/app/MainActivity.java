package com.flowtime.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> mUploadMessage;
    private static final int FILE_CHOOSER = 1;
    private boolean lockOn = false;
    private boolean focusRunning = false;
    private boolean exitedWhileFocus = false;
    private int remindIdx = 0;
    private static final String[] REMINDERS = {
        "你真的要放弃专注吗？",
        "别让别的东西毁掉你的专注。",
        "再坚持一下，别功亏一篑。",
        "分心只要一秒，专注却要很久。",
        "关掉打扰，回到眼前这件事。"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        webView = new WebView(this);
        webView.setBackgroundColor(0xfffaf8f6);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 状态栏与页面底色一致，使用深色状态栏图标
        getWindow().setStatusBarColor(0xfffaf8f6);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new KeepScreenBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                }
                mUploadMessage = callback;
                Intent intent = (params != null) ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                try {
                    startActivityForResult(intent, FILE_CHOOSER);
                } catch (Exception e) {
                    mUploadMessage = null;
                    return false;
                }
                return true;
            }
        });
        // 关掉系统的长按行为（文本选择 / 图片另存菜单）；App 内的长按由 JS 自己实现，不受影响
        webView.setLongClickable(false);
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/flowtime.html");
        setContentView(webView);

        // Android 13+ 通知需要运行时授权；拒绝也不影响计时，只是到点没有通知
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
            } catch (Exception ignored) {
            }
            // targetSdk 35+ 起系统强制启用预测性返回，onBackPressed 不再被调用，
            // 必须注册 OnBackInvokedCallback 才能真正接管侧滑返回
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override
                            public void onBackInvoked() {
                                handleBack();
                            }
                        });
            } catch (Throwable ignored) {
            }
        }
    }

    /** 统一的返回处理：先让网页逐层后退，都没有才退到桌面（保留后台计时） */
    private void handleBack() {
        if (lockOn) {
            showFocusReminder(true);
            return;
        }
        if (webView == null) {
            finish();
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{return (window.closeTopLayer&&closeTopLayer())?'1':'0';}catch(e){return '0';}})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && value.indexOf('1') >= 0) return;
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        moveTaskToBack(true);
                    }
                }
            });
    }

    private class KeepScreenBridge {
        @android.webkit.JavascriptInterface
        public void setKeepScreenOn(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }
            });
        }
        @android.webkit.JavascriptInterface
        public void setLockMode(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { lockOn = on; }
            });
        }
        @android.webkit.JavascriptInterface
        public void setFocusRunning(final boolean running) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { focusRunning = running; }
            });
        }
        @android.webkit.JavascriptInterface
        public void startFocusService(final boolean countdown, final long endAt, final long startAt, final String title) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(MainActivity.this, FocusService.class);
                        i.setAction(FocusService.ACTION_START);
                        i.putExtra("countdown", countdown);
                        i.putExtra("endAt", endAt);
                        i.putExtra("startAt", startAt);
                        i.putExtra("title", title);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(i);
                        } else {
                            startService(i);
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        @android.webkit.JavascriptInterface
        public void stopFocusService() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(MainActivity.this, FocusService.class);
                    i.setAction(FocusService.ACTION_STOP);
                    try {
                        startService(i);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        @android.webkit.JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    int o;
                    if ("landscape".equals(mode)) {
                        o = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    } else if ("auto".equals(mode)) {
                        o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                    } else {
                        o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    }
                    setRequestedOrientation(o);
                }
            });
        }
    }

    private String nextReminder() {
        String msg = REMINDERS[remindIdx % REMINDERS.length];
        remindIdx++;
        return msg;
    }

    private void showFocusReminder(final boolean allowExit) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
                b.setTitle("专注守护");
                b.setMessage(nextReminder());
                b.setPositiveButton("继续专注", null);
                if (allowExit) {
                    b.setNegativeButton("放弃退出", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            finish();
                        }
                    });
                }
                b.show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (mUploadMessage == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            results = new Uri[]{ data.getData() };
        }
        mUploadMessage.onReceiveValue(results);
        mUploadMessage = null;
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lockOn) {
            exitedWhileFocus = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exitedWhileFocus) {
            exitedWhileFocus = false;
            showFocusReminder(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
