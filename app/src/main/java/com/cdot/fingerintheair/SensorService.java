package com.cdot.fingerintheair;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.cdot.onewire.OneWireError;
import com.cdot.onewire.OneWireSearch;
import com.cdot.onewire.OneWireDriver;
import com.cdot.onewire.OneWireThermometer;
import com.felhr.usbserial.UsbSerialDevice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

public class SensorService extends Service {

    private static final String TAG = "SensorService";

    public static final String ACTION_LOG = "com.cdot.fingerintheair.LOG";
    public static final String EXTRA_LOG_MSG = "com.cdot.fingerintheair.LOG_MSG";

    public static final String ACTION_SENSOR_UPDATE = "com.cdot.fingerintheair.SENSOR_UPDATE";
    public static final String EXTRA_SENSOR_ID = "com.cdot.fingerintheair.SENSOR_ID";
    public static final String EXTRA_SENSOR_VALUE = "com.cdot.fingerintheair.SENSOR_VALUE";
    public static final String EXTRA_SENSOR_TIMESTAMP = "com.cdot.fingerintheair.SENSOR_TIMESTAMP";

    public static final String ACTION_SENSOR_LOST = "com.cdot.fingerintheair.SENSOR_LOST";

    public static final String PREFERENCES = "FingerInTheAirPrefs" ;

    private static final String ACTION_USB_PERMISSION = "com.cdot.fingerintheair.USB_PERMISSION";

    // PREFERENCE_ must match android:key in prefs_frag,xml
    static final String PREFERENCE_SAMPLE_FREQUENCY = "sampleFrequency";
    static final int DEFAULT_SAMPLE_FREQUENCY = 20; // seconds
    static final String PREFERENCE_SAMPLE_FILE = "sampleFile";
    static final String DEFAULT_SAMPLE_FILE = new File(Environment.getExternalStoragePublicDirectory("fingerintheair"), "data.csv").toString();
    static final int DEFAULT_SAMPLE_LIFE = 60 * 60 * 24; // 1 day
    static final String PREFERENCE_SAMPLE_LIFE = "sampleCount";

    private SharedPreferences mSharedPreferences;

    // Map from sensor ID to thread
    private HashMap<Long, SensorThread> mSensorThreads = new HashMap<Long, SensorThread>();
    private UsbManager mUSBManager;

    private void log(String s) {
        Log.d(TAG, s);
        Intent intent = new Intent(ACTION_LOG);
        intent.putExtra(EXTRA_LOG_MSG, TAG + ": " + s);
        sendBroadcast(intent);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device;
            switch (intent.getAction()) {
                case ACTION_USB_PERMISSION:
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (!granted) {
                        log(String.format("Permission to access %s not granted", device.getDeviceName()));
                        return;
                    }
                    connectUSBDevice(device);
                    break;
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    // A USB device has been attached. See if it opens as a Serial port
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    requestPermission(device);
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    for (Map.Entry<Long, SensorThread> entry : mSensorThreads.entrySet()) {
                        if (entry.getValue().isOnDevice(device)) {
                            // Interrupt the SensorThread
                            entry.getValue().interrupt();
                            mSensorThreads.remove(entry.getKey());
                            // Pass on the news to MainActivity
                            intent = new Intent(ACTION_SENSOR_LOST);
                            intent.putExtra(EXTRA_SENSOR_ID, entry.getKey());
                            sendBroadcast(intent);
                        }
                    }
                    break;
            }
        }
    };

    private void connectUSBDevice(UsbDevice device) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            log( "FEATURE_USB_HOST MISSING");
            return;
        }
        //log( "connecting " + device.getDeviceName());
        UsbDeviceConnection connection = mUSBManager.openDevice(device);
        if (connection == null) {
            log( "Connection to " + device.getDeviceName() + " failed. No idea why.");
            return;
        }

        log( String.format("%s connected. Scanning 1-wire bus", device.getDeviceName()));

        OneWireDriver driver = new AndroidSerial1WireDriver(device, connection);
        OneWireSearch scanner = new OneWireSearch(driver);
        class MyHandler implements OneWireSearch.Device {
            public OneWireError device(long serno) {
                log( String.format("1-wire device %X found on %s", serno, device.getDeviceName()));
                SensorThread st = new SensorThread(new OneWireThermometer(serno, driver));
                mSensorThreads.put(serno, st);
                st.start();
                return OneWireError.NO_ERROR_SET;
            }
        }

        scanner.scan(new MyHandler());
    }

    private void requestPermission(UsbDevice device) {
        if (mUSBManager.hasPermission(device))
            connectUSBDevice(device);
        else {
            log( "Requesting permission for " + device.getDeviceName());
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            mUSBManager.requestPermission(device, pendingIntent);
        }
    }

    private class SensorThread extends Thread {
        OneWireThermometer thermometer;

        SensorThread(OneWireThermometer t) {
            thermometer = t;
        }

        boolean isOnDevice(UsbDevice d) {
            return thermometer.getDriver().isUsingPort(d.getDeviceName());
        }

        @Override
        public void run() {
            // Interrupting this thread kills it
            while (!isInterrupted()) {
                thermometer.update();
                long timestamp = System.currentTimeMillis();

                long keep = System.currentTimeMillis() - mSharedPreferences.getInt(SensorService.PREFERENCE_SAMPLE_LIFE, SensorService.DEFAULT_SAMPLE_LIFE) * 1000;
                File fn = new File(mSharedPreferences.getString(SensorService.PREFERENCE_SAMPLE_FILE, SensorService.DEFAULT_SAMPLE_FILE));

                Vector<String> lines = new Vector<>();
                try {
                    FileInputStream fi = new FileInputStream(fn);
                    StringBuilder fileContent = new StringBuilder();

                    byte[] buffer = new byte[1024];
                    int n;
                    while ((n = fi.read(buffer)) != -1) {
                        fileContent.append(new String(buffer, 0, n));
                    }
                    fi.close();
                    for (String str : fileContent.toString().split("\n")) {
                        String[] row = str.split(",");
                        try {
                            if (Long.parseLong(row[1]) > keep)
                                lines.add(str);
                        } catch (NumberFormatException nfe) {
                            log("Bad time in " + str);
                        }
                    }
                } catch (IOException ioe) {
                    log( fn + " read failed " + ioe);
                }
                lines.insertElementAt(String.format("%X,%d,%g", thermometer.serialNumber, timestamp, thermometer.temperature), 0);
                try {
                    FileOutputStream fo = new FileOutputStream(fn);
                    fo.write(TextUtils.join("\n", lines).getBytes());
                    fo.close();
                } catch (IOException ioe) {
                    log( fn + " write failed " + ioe);
                }

                // Tell MainActivity about it
                Intent intent = new Intent(ACTION_SENSOR_UPDATE);
                intent.putExtra(EXTRA_SENSOR_ID, thermometer.serialNumber);
                intent.putExtra(EXTRA_SENSOR_VALUE, thermometer.temperature);
                intent.putExtra(EXTRA_SENSOR_TIMESTAMP, timestamp);
                sendBroadcast(intent);

                long snooze = (long) mSharedPreferences.getInt(PREFERENCE_SAMPLE_FREQUENCY, DEFAULT_SAMPLE_FREQUENCY) * 1000;
                try {
                    Thread.sleep(snooze);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            Intent intent = new Intent(ACTION_SENSOR_LOST);
            intent.putExtra(EXTRA_SENSOR_ID, thermometer.serialNumber);
            sendBroadcast(intent);
        }
    }
    @Override
    public void onCreate() {
        mSharedPreferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(mBroadcastReceiver, filter);

        mUSBManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Try to open the first encountered usb device connected, excluding usb root hubs
        HashMap<String, UsbDevice> usbDevices = mUSBManager.getDeviceList();
        boolean foundDevice = false;
        UsbDevice device;
        if (!usbDevices.isEmpty()) {
            // first, dump the hashmap for diagnostic purposes
            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                log( String.format("USBDevice %s: %X:%X class:%X:%X is%s supported",
                        device.getDeviceName(),
                        device.getVendorId(), device.getProductId(),
                        device.getDeviceClass(), device.getDeviceSubclass(),
                        UsbSerialDevice.isSupported(device) ? "" : " not"));

                if (UsbSerialDevice.isSupported(device)) {
                    foundDevice = true;
                    requestPermission(device);
                }
            }
        }
        if (!foundDevice) {
            log( "No serial device connected");
        }
    }

    @Override
    public void onDestroy() {
        // Kill the running sensor threads
        for (Map.Entry<Long, SensorThread> entry : mSensorThreads.entrySet()) {
            entry.getValue().interrupt();
            mSensorThreads.remove(entry.getKey());
        }
        unregisterReceiver(mBroadcastReceiver);
        // Free the ports

        super.onDestroy();
    }

    public class LocalBinder extends Binder {
        SensorService getService() {
            return SensorService.this;
        }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }
}
