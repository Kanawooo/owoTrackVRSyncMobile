package org.owoTrack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class TrackingService extends Service {
    private UDPGyroProviderClient client;
    private GyroListener listener;
    private PowerManager.WakeLock wakeLock;

    private String ip_address;
    private AppStatus stat;


    private static TrackingService instance = null;
    public static boolean isInstanceCreated(){
        return instance != null;
    }

    @Override
    public void onCreate() {
        instance = this;
    }


    Runnable on_death = () -> {
            stopSelf();
            LocalBroadcastManager.getInstance(TrackingService.this).sendBroadcast(new Intent("pls-let-me-die"));
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if((intent == null) || (intent.getExtras() == null)){
            foregroundstuff();
            return START_STICKY;
        }
        Bundle data = intent.getExtras();
        ip_address = data.getString("ipAddrTxt");
        int port_no = data.getInt("port_no");
        boolean mag = data.getBoolean("magnetometer", true);

        System.out.println("Start command");
        foregroundstuff();

        stat = new AppStatus((Service)this);
        client = new UDPGyroProviderClient(stat, this);
        try {
            listener = new GyroListener((SensorManager)getSystemService(Context.SENSOR_SERVICE), client, stat, mag);
        } catch (Exception e) {
            stat.update("on GyroListener: " + e.toString());
            on_death.run();
            return START_STICKY;
        }


        try {
            Thread thread = new Thread(() -> {
                client.setTgt(ip_address, port_no);
                client.connect(on_death);
                if (client == null || !client.isConnected()) {
                    on_death.run();
                }
            });
            thread.start();
        } catch(OutOfMemoryError err) {
            stat.update("Out of memory error when trying to spawn thread");
            on_death.run();
            return START_STICKY;
        }

        listener.register_listeners();

        String tag = "owoTrackVRSync::BackgroundTrackingSync";

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M && Build.MANUFACTURER.equals("Huawei")) { tag = "LocationManagerService"; }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wakeLock.acquire();

        register_recenter_yaw();

        ignoreWifi = false;
        lockWifi();

        return START_STICKY;
    }

    boolean ignoreWifi = false;
    ConnectivityManager.NetworkCallback callback = null;

    private void lockWifi(){
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            if(callback == null) {
                callback = new ConnectivityManager.NetworkCallback() {
                    public void onAvailable(Network network) {
                        if(ignoreWifi) return;

                        super.onAvailable(network);
                        connectivityManager.bindProcessToNetwork(network);
                    }
                };
            }

            connectivityManager.requestNetwork(
                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    callback
            );
        }
    }

    private void unlockWifi(){
        ignoreWifi = true;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.bindProcessToNetwork(null);
            if(callback != null)
                connectivityManager.unregisterNetworkCallback(callback);
            callback = null;

        }
    }

    private final IBinder localBinder = new TrackingBinder();
    @Override
    public IBinder onBind(Intent intent) {

        return localBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    public class TrackingBinder extends Binder {
        public TrackingService getService() {
            return TrackingService.this;
        }
    }



    public String getLog(){
        if(stat == null){
            return "Service not started";
        }
        return stat.statusi;
    }

    public boolean is_running(){
        return stat != null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("cya-ded"));
        unlockWifi();
        if(listener != null) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot((long) (200), (int) (255)));
            }else {
                v.vibrate(200);
            }
            listener.stop();
            wakeLock.release();

            unregisterReceiver(broadcastReceiver);
            unregisterReceiver(recenterReceiver);

            if(client != null) {
                client.stop();
                client = null;
            }
        }

    }

    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(stat != null) stat.update("Killed.");
            if(on_death != null) on_death.run();
        }
    };

    //private long last_screen_time = 0;
    BroadcastReceiver recenterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(client != null)
                client.button_pushed();
        }
    };


    private void register_recenter_yaw(){
        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        //screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(recenterReceiver, screenStateFilter);
    }

    // stupid foreground stuff
    private void foregroundstuff(){
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel("NOTIFICATION_CHANNEL_ID", "Foreground Service", NotificationManager.IMPORTANCE_DEFAULT));
        }

        registerReceiver(broadcastReceiver, new IntentFilter("kill-ze-service"));

        Intent intent = new Intent("kill-ze-service");
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);

        Notification notification = new NotificationCompat.Builder(this, "NOTIFICATION_CHANNEL_ID")
                .setContentTitle("owoTrackVR")
                .setTicker("owoTrackVR")
                .setContentText("Currently connected to " + ip_address)
                .setSmallIcon(R.mipmap.ic_launcher)
                .addAction(0, "Stop", pendingIntent)
                .setOngoing(true).build();

        startForeground(1001, notification);
    }
}
