package com.cdot.fingerintheair;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "FingerInTheAir";

    private static final int REQUEST_WRITE_STORAGE = 112;

    // Map from sensor id to text view showing value
    private HashMap<Long,TextView> mSensorViews = new HashMap<>();

    // Container for sensor text views
    private LinearLayoutCompat mSensorLayout;
    private TextView mLogView = null;

    private void log(String s) {
        if (mLogView != null) {
            mLogView.append("\n" + s);
        } else {
            Log.d(TAG, s);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case SensorService.ACTION_SENSOR_LOST:
                    Long sid = intent.getLongExtra(SensorService.EXTRA_SENSOR_ID, 0);
                    TextView tv = mSensorViews.get(sid);
                    if (tv != null) {
                        tv.setText("");
                        mSensorViews.remove(sid);
                        mSensorLayout.removeView(tv);
                        log(String.format("Sensor %X removed (USB detached)", sid));
                    }
                    break;
                case SensorService.ACTION_SENSOR_UPDATE:
                    long sensor = intent.getLongExtra(SensorService.EXTRA_SENSOR_ID, 0);
                    double temp = intent.getDoubleExtra(SensorService.EXTRA_SENSOR_VALUE, 0);
                    long timestamp = intent.getLongExtra(SensorService.EXTRA_SENSOR_TIMESTAMP, 0);
                    log(String.format("Sensor update at %d for %X = %g", timestamp, sensor, temp));
                    MainActivity.this.reportTemperature(sensor, temp, timestamp);
                    break;
                case SensorService.ACTION_LOG:
                    if (mLogView != null)
                        mLogView.append("\n" + intent.getStringExtra(SensorService.EXTRA_LOG_MSG));
                    break;
            }
        }
    };

    public void reportTemperature(long sensor, double temp, long timestamp) {
        TextView tv = mSensorViews.get(sensor);
        if (tv == null) {
            tv = new TextView(MainActivity.this);
            MainActivity.this.mSensorLayout.addView(tv);
            mSensorViews.put(sensor, tv);
        }
        tv.setText(String.format("%X: %g @ %d", sensor, temp, timestamp));
    }

    /**
     * See https://stackoverflow.com/questions/23523806/how-do-you-create-preference-activity-and-preference-fragment-on-android
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_preference:
                Intent intent = new Intent();
                intent.setClassName(this, "com.cdot.fingerintheair.PrefsActivity");
                startActivity(intent);
                return true;
            case R.id.menu_restartService:
                stopService(new Intent(MainActivity.this, SensorService.class));
                ComponentName service = startService(new Intent(this, SensorService.class));
                if (service != null) {
                    log("Service started " + service);
                } else
                    log("Failed to start service");
                return true;
            case R.id.menu_stopService:
                stopService(new Intent(MainActivity.this, SensorService.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        mSensorLayout = findViewById(R.id.sensorLayout);
        mLogView = findViewById(R.id.showLog);

        PreferenceManager.setDefaultValues(this, R.xml.prefs_frag, false);

        if (!(ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        }
        // Will bind to the service if it's already running
        log("Starting service");
        ComponentName service = startService(new Intent(this, SensorService.class));
        if (service != null) {
            log("Service started " + service);
        } else
            log("Failed to start service");
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(SensorService.ACTION_LOG);
        filter.addAction(SensorService.ACTION_SENSOR_UPDATE);
        filter.addAction(SensorService.ACTION_SENSOR_LOST);
        registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mBroadcastReceiver);
    }
}
