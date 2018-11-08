package com.ocotooo.smartcharging;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.icu.text.SimpleDateFormat;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class SmartChargingService extends Service {
    private static final String TAG = SmartChargingService.class.getSimpleName();

    private static final int NOTIFICATION_ID = 1;

    private static final int BATTERY_UPPER_LIMIT = 95;
    private static final int BATTERY_LOWER_LIMIT = 90;

    private static final String POWER_ON_SPREAD_SHEET_ID = "電源オンのトリガーを書き込む Google スプレッドシートの ID";
    private static final String POWER_OFF_SPREAD_SHEET_ID = "電源オフのトリガーを書き込む Google スプレッドシートの ID";

    private static com.google.api.services.sheets.v4.Sheets SERVICE;

    private int mPreviousStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;

    private boolean mIsForeground = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, SmartChargingService.class);
        context.startService(intent);

        Credential credential = authorize(context);
        SERVICE = getService(credential);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // バッテリー状態が変化したときにインテントを受け取るブロードキャストレシーバーを登録
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mBatteryChangedReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // ブロードキャストレシーバーを解除
        unregisterReceiver(mBatteryChangedReceiver);

        mPreviousStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
        mIsForeground = false;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private BroadcastReceiver mBatteryChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            int scale = intent.getIntExtra("scale", 0);
            int level = intent.getIntExtra("level", 0);
            float remainingBattery = 100;
            if (level != -1 && scale != -1) {
                remainingBattery = (int) (level / (float) scale * 100);
            }
            Log.i(TAG, "remainingBattery: " + remainingBattery);

            // 充電中の場合
            if (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL) {
                // 電池残量が十分の場合
                if (remainingBattery >= BATTERY_UPPER_LIMIT) {
                    // スマートプラグを電源オフして充電を止める
                    powerOff();
                // 電池残量が不十分の場合
                } else {
                    // そのまま充電を続ける

                    // フォアグラウンドサービスを開始していなかったら開始する
                    if (!mIsForeground) {
                        startForeground(NOTIFICATION_ID, createNotification(context, "充電中です"));
                        mIsForeground = true;
                    // 充電が始まったらメッセージを更新する
                    } else {
                        if (mPreviousStatus != BatteryManager.BATTERY_STATUS_CHARGING
                                && mPreviousStatus != BatteryManager.BATTERY_STATUS_FULL) {
                            updateNotification(context, "充電中です");
                        }
                    }
                }
            // 充電中ではない場合
            } else {
                // 電池残量が不十分の場合
                if (remainingBattery < BATTERY_LOWER_LIMIT) {
                    // 直前まで充電されていた場合
                    if (mPreviousStatus == BatteryManager.BATTERY_STATUS_CHARGING
                            || mPreviousStatus == BatteryManager.BATTERY_STATUS_FULL) {
                        // 強制的に充電を中止されたとみなしてスマートプラグを電源オフし、サービスも止める
                        powerOff();
                        stopSelf();
                    } else {
                        // スマートプラグを電源オンして充電を開始する
                        powerOn();

                        // フォアグラウンドサービスを開始していなかったら開始する
                        if (!mIsForeground) {
                            startForeground(NOTIFICATION_ID, createNotification(context, "充電を開始中です"));
                            mIsForeground = true;
                        }
                    }
                // 電池残量が十分の場合
                } else {
                    // 充電の必要がないので、何もせずサービスを止める
                    stopSelf();
                }
            }

            mPreviousStatus = status;
        }
    };

    private Notification createNotification(Context context, String message) {
        final String notificationTitle = message;
        final String notificationContentText = "タップしてバッテリーの詳細を確認";

        final String channelId = "smart charging notification channel";

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel notificationChannel = new NotificationChannel(
                channelId, notificationTitle, NotificationManager.IMPORTANCE_DEFAULT);

        notificationManager.createNotificationChannel(notificationChannel);

        Intent notificationIntent = new Intent(this, SmartChargingActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.battery)
                .setContentTitle(notificationTitle)
                .setContentText(notificationContentText)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        return notification;
    }

    private void updateNotification(Context context, String message) {
        final String notificationTitle = message;
        final String notificationContentText = "タップしてバッテリーの詳細を確認";

        final String channelId = "smart charging notification channel";

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel notificationChannel = new NotificationChannel(
                channelId, notificationTitle, NotificationManager.IMPORTANCE_DEFAULT);

        notificationManager.createNotificationChannel(notificationChannel);

        Intent notificationIntent = new Intent(this, SmartChargingActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.battery)
                .setContentTitle(notificationTitle)
                .setContentText(notificationContentText)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void powerOn() {
        new SpreadSheetAddRowTask(POWER_ON_SPREAD_SHEET_ID).execute();
    }

    private void powerOff() {
        new SpreadSheetAddRowTask(POWER_OFF_SPREAD_SHEET_ID).execute();
    }

    private class SpreadSheetAddRowTask extends AsyncTask<Void, Void, Void> {
        private Exception mLastError = null;
        private String mSpreadSheetId;

        SpreadSheetAddRowTask(String spreadSheetId) {
            mSpreadSheetId = spreadSheetId;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                return request();
            } catch (Exception e) {
                Log.e(TAG, e.toString());
                mLastError = e;
                cancel(true);
                return null;
            }
        }

        private Void request() throws IOException {
            Log.i(TAG, "request");

            String range = "sheet!A1";

            ValueRange valueRange = new ValueRange();

            List row = new ArrayList<>();

            // 現在時刻を記録
            Date date = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy'年'MM'月'dd'日' kk'時'mm'分'ss'秒'");
            List col = Arrays.asList(sdf.format(date));
            row.add(col);
            valueRange.setValues(row);

            valueRange.setRange(range);

            SERVICE.spreadsheets().values()
                    .append(mSpreadSheetId, range, valueRange)
                    .setValueInputOption("RAW")
                    .setInsertDataOption("INSERT_ROWS")
                    .setIncludeValuesInResponse(false)
                    .setResponseValueRenderOption("FORMATTED_VALUE")
                    .execute();

            return null;
        }

        @Override
        protected void onCancelled() {
            if (mLastError != null) {
                Log.e(TAG, "The following error occurred:\n" + mLastError.getMessage());
            } else {
                Log.e(TAG, "Request cancelled.");
            }
        }
    }

    private static GoogleCredential authorize(Context context) {
        GoogleCredential credential = null;

        try {
            Resources res = context.getResources();
            InputStream inputStream = res.openRawResource(R.raw.serviceaccountkey);
            credential = GoogleCredential.fromStream(inputStream)
                    .createScoped(Arrays.asList(SheetsScopes.SPREADSHEETS));
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }

        return credential;
    }

    private static com.google.api.services.sheets.v4.Sheets getService(Credential credential) {
        com.google.api.services.sheets.v4.Sheets service = null;

        try {
            HttpTransport transport = AndroidHttp.newCompatibleTransport();
            JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
            service = new com.google.api.services.sheets.v4.Sheets.Builder(transport, jsonFactory, credential)
                    .setApplicationName("Smart Charging")
                    .build();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }

        return service;
    }
}
