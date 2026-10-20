package com.keralarescue.mobile;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Receives push messages while the app is open. (When it is closed, Android shows the notification itself.) */
public class RescueMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL = "rescue_updates";

    @Override public void onNewToken(String token) {
        getSharedPreferences("kerala_rescue", MODE_PRIVATE).edit().remove("push_registered").apply();
        PushRegistrar.register(this, BuildConfig.API_BASE_URL);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        RemoteMessage.Notification n = message.getNotification();
        String title = n != null && n.getTitle() != null ? n.getTitle() : "Rescue team update";
        String body = n != null && n.getBody() != null ? n.getBody() : "Open the app to see your request.";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Rescue updates", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_kerala_rescue)
                .setContentTitle(title).setContentText(body).setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open);
        getSystemService(NotificationManager.class).notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), b.build());
    }
}
