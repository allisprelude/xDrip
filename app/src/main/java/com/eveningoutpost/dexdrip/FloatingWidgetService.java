package com.eveningoutpost.dexdrip;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;

public class FloatingWidgetService extends Service implements BloodSugarUpdateReceiver.BloodSugarUpdateListener  {
    private WindowManager mWindowManager;
    private View mFloatingWidget;
    private static TextView mTextViewBloodSugar;
    private static TextView mTextViewBloodSugarTime;
    private WindowManager.LayoutParams params;

    // 添加定时器相关变量
    private static Handler mHandler;
    private static Runnable mTimeUpdateRunnable;
    private static BgReading mCurrentBgReading; // 保存当前血糖读数用于时间更新

    private BloodSugarUpdateReceiver mBloodSugarUpdateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        // 创建广播接收器并注册
        mBloodSugarUpdateReceiver = new BloodSugarUpdateReceiver(this);
        IntentFilter filter = new IntentFilter(BloodSugarUpdateReceiver.ACTION_UPDATE_BLOOD_SUGAR);
        registerReceiver(mBloodSugarUpdateReceiver, filter);

        // 初始化Handler
        mHandler = new Handler();
        setupTimeUpdateRunnable();
    }

    // 设置定时更新任务
    private void setupTimeUpdateRunnable() {
        mTimeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateTimeDisplay();
                // 每分钟更新一次
                mHandler.postDelayed(this, 60000);
            }
        };
    }

    // 更新时间显示
    private static void updateTimeDisplay() {
        if (mTextViewBloodSugarTime != null && mCurrentBgReading != null) {
            long currentTime = System.currentTimeMillis();
            long timeDiff = (currentTime - mCurrentBgReading.timestamp) / (1000 * 60);
            String timeInfo = timeDiff + " mins ago";
            mTextViewBloodSugarTime.setText(timeInfo);
        }
    }

    private String getCurrentBloodSugar() {
        return "--/--";
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mFloatingWidget == null) {
            createFloatingWidget();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void createFloatingWidget() {
        mFloatingWidget = LayoutInflater.from(this).inflate(R.layout.floating_widget_layout, null);
        mTextViewBloodSugar = mFloatingWidget.findViewById(R.id.textView_blood_sugar);
        mTextViewBloodSugarTime = mFloatingWidget.findViewById(R.id.textView_blood_sugar_time);

        // 设置悬浮窗参数
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mWindowManager.addView(mFloatingWidget, params);

        // 初始化显示最新血糖数据
        try {
            BgReading lastReading = BgReading.last();
            if (lastReading != null) {
                updateBloodSugarValue(lastReading);
            } else {
                // 显示默认值
                mTextViewBloodSugarTime.setText("-- mins ago");
                mTextViewBloodSugar.setText("-- → +0.0");
            }
        } catch (Exception e) {
            mTextViewBloodSugarTime.setText("-- mins ago");
            mTextViewBloodSugar.setText("-- → +0.0");
        }

        // 启动定时器
        mHandler.post(mTimeUpdateRunnable);

        // 设置浮窗可拖动
        mFloatingWidget.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        mWindowManager.updateViewLayout(mFloatingWidget, params);
                        return true;
                }
                return false;
            }
        });
    }

    // 重构后的主要更新方法 - 只接收BgReading对象
    public static void updateBloodSugarValue(BgReading bgReading) {
        if (mTextViewBloodSugar != null && mTextViewBloodSugarTime != null && bgReading != null) {
            // 保存当前血糖读数用于定时器更新
            mCurrentBgReading = bgReading;

            // 计算时间差（分钟）
            long currentTime = System.currentTimeMillis();
            long timeDiff = (currentTime - bgReading.timestamp) / (1000 * 60);

            // 第一行：英文格式的时间差
            String timeInfo = timeDiff + " mins ago";
            mTextViewBloodSugarTime.setText(timeInfo);

            // 第二行：血糖值 + 箭头 + 增量值（小字体）
            String bgValueStr = bgReading.displayValue(null);

            // 获取趋势箭头
            String arrow = "";
            try {
                arrow = bgReading.displaySlopeArrow();
                if (arrow == null) arrow = "";
            } catch (Exception e) {
                arrow = "";
            }

            // 计算血糖变化值
            String changeValue = "+0.0"; // 默认值
            try {
                // 获取前一次读数来计算变化
                BgReading previousReading = BgReading.getForPreciseTimestamp(
                    bgReading.timestamp - (5 * 60 * 1000), 5 * 60 * 1000);

                if (previousReading != null) {
                    // 使用getDg_mgdl()方法获取血糖值，与显示值保持一致
                    double currentBg = bgReading.getDg_mgdl();
                    double previousBg = previousReading.getDg_mgdl();

                    // 检查单位设置
                    String unit = com.eveningoutpost.dexdrip.utilitymodels.Pref.getString("units", "mgdl");

                    if (unit.equals("mgdl")) {
                        // mg/dL单位
                        double change = currentBg - previousBg;
                        if (change >= 0) {
                            changeValue = String.format("+%.0f", change);
                        } else {
                            changeValue = String.format("%.0f", change);
                        }
                    } else {
                        // mmol/L单位
                        double currentMmol = mmolConvert(currentBg);
                        double previousMmol = mmolConvert(previousBg);
                        double change = currentMmol - previousMmol;
                        if (change >= 0) {
                            changeValue = String.format("+%.1f", change);
                        } else {
                            changeValue = String.format("%.1f", change);
                        }
                    }
                }
            } catch (Exception e) {
                // 使用默认值
            }

            // 格式化显示：血糖值 + 箭头 + 小字体增量值
            // 使用HTML格式来实现小字体效果
            String finalDisplay = bgValueStr + arrow + " <small>" + changeValue + "</small>";

            // 设置HTML文本以支持小字体
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                mTextViewBloodSugar.setText(android.text.Html.fromHtml(finalDisplay, android.text.Html.FROM_HTML_MODE_COMPACT));
            } else {
                mTextViewBloodSugar.setText(android.text.Html.fromHtml(finalDisplay));
            }
        }
    }

    // 兼容旧的字符串调用 - 转换为BgReading调用
    public static void updateBloodSugarValue(String bloodSugarValue) {
        try {
            BgReading lastReading = BgReading.last();
            if (lastReading != null) {
                updateBloodSugarValue(lastReading);
            }
        } catch (Exception e) {
            // 如果无法获取BgReading，显示默认值
            if (mTextViewBloodSugarTime != null && mTextViewBloodSugar != null) {
                mTextViewBloodSugarTime.setText("-/- mins ago");
                mTextViewBloodSugar.setText("-/- +0.0");
            }
        }
    }

    // 添加mmol转换方法
    private static double mmolConvert(double mgdl) {
        return mgdl * Constants.MGDL_TO_MMOLL;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFloatingWidget != null) {
            mWindowManager.removeView(mFloatingWidget);
        }
        // 移除定时器
        if (mHandler != null && mTimeUpdateRunnable != null) {
            mHandler.removeCallbacks(mTimeUpdateRunnable);
        }
    }

    @Override
    public void onBloodSugarUpdate(String bloodSugarValue) {
        updateBloodSugarValue(bloodSugarValue);
    }
}
