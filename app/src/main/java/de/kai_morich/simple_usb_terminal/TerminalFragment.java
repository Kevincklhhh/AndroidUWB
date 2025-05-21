package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.XonXoffFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.net.Uri;
import androidx.core.content.FileProvider;
import android.widget.Button;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.json.JSONObject;
import org.jtransforms.fft.DoubleFFT_1D;
import de.kai_morich.simple_usb_terminal.RandomForestClassifier;
public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private final Handler mainLooper;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ImageButton sendBtn;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private enum SendButtonState {Idle, Busy, Disabled};

    private ControlLines controlLines = new ControlLines();
    private XonXoffFilter flowControlFilter;

    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private SensorManager sensorManager;

    private Sensor linearAccelerometer;
    private Sensor gyroscope;

    private StringBuilder dataBuffer = new StringBuilder();
    private BlockingQueue<Map<String, Object>> cirDataQueue = new LinkedBlockingQueue<>();
    private RandomForestClassifier model;
    private Map<Integer, String> labelMapping;

    private int CIRlength = 70;
    // Thresholds




    private List<Pair<Long, float[]>> gyroWindow = new ArrayList<>();
    private List<Pair<Long, float[]>> accelWindow = new ArrayList<>();
    private long windowStartTime = 0;
    private static final int WINDOW_SIZE_MS = 1600;
    private static final int STEP_MS = 200;
    private static final float GYROSCOPE_TRIGGER_THRESHOLD = 2.0f;
    private static final float ACCEL_STD_THRESHOLD = 1.2f;
    private static final float NET_DISPLACEMENT_THRESHOLD = 0.9f;
    private final Queue<String> recentStablePredictions = new LinkedList<>();
    private final int REQUIRED_STABLE_COUNT = 3;
    private long localizationStartTime = -1;


    private static final float ACCEL_HIGH_THRESHOLD = 1.0f;
    private int dataCollectionCount = 0;


    private static final float MIN_DURATION_ABOVE_ACCEL = 0.7f;  // in seconds
    private static final float GYROSCOPE_THRESHOLD = 1.0f; // for activation (unchanged)
    private static final float ACCEL_THRESHOLD = 1.0f;      // for activation (unchanged)
    // To track when we last processed a window.
    private long lastProcessTime = 0;
    private static final float EXIT_DEBOUNCE_DURATION = 1000; // 1 second debounce (unchanged)



    private static final float ACCEL_DURATION_THRESHOLD = 0.6f;  // 30% of samples in window must exceed ACCEL_MIN_VALUE
    private static final float ACCEL_MIN_VALUE = 1.4f;
    // Flag and handler for UWB ranging state
    private boolean isUwbActive = false;
    private Handler uwbHandler = new Handler();
    private static final int UWB_DURATION_MS = 5000;



    // List to store features of collected CIRs
    private List<Map<String, Double>> collectedFeatures = new ArrayList<>();

    // List to store classification results
    private List<Integer> classificationResults = new ArrayList<>();

    // Number of CIRs to collect
    private static final int NUM_CIRS_TO_COLLECT = 5;

    // Variance thresholds for features (adjust these thresholds based on your data)
    private Map<String, Double> varianceThresholds = new HashMap<>();


    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        // Clear the log file only if this is the first creation (i.e., savedInstanceState == null)
        if (savedInstanceState == null) {
            clearLogFile();
            clearIMULogFile();
        }
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        startProcessing();
        model = new RandomForestClassifier();
        labelMapping = loadLabelMapping(getContext());
    }
    private Map<Integer, String> loadLabelMapping(Context context) {
        Map<Integer, String> labelMapping = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("label_mapping_inverse.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String jsonString = new String(buffer, "UTF-8");
            JSONObject jsonObject = new JSONObject(jsonString);
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int intKey = Integer.parseInt(key);
                String value = jsonObject.getString(key);
                labelMapping.put(intKey, value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return labelMapping;
    }
    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
        processingExecutor.shutdownNow();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }

        sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(sensorEventListener, linearAccelerometer, SensorManager.SENSOR_DELAY_GAME);

        if(connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        if (sensorManager != null && sensorEventListener != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }
        controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");
        // Clear the log file when the view is created
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines.onCreateView(view);
        Button shareLogButton = view.findViewById(R.id.share_log_button);
        shareLogButton.setOnClickListener(v -> shareLogFile());

        sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            // We only need the linear accelerometer and gyroscope for this task.
            linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        } else {
            Toast.makeText(getActivity(), "Sensor Manager not available", Toast.LENGTH_SHORT).show();
        }
        // Register sensors at UI (5Hz) rate.
        sensorManager.registerListener(sensorEventListener, linearAccelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_GAME);

        return view;
    }

    private SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            long timestamp = System.currentTimeMillis();

            // If UWB ranging is active, pause window processing.
            if (isUwbActive) {
                // Optionally, you might want to ignore sensor events or clear window buffers.
                return;
            }

            switch (event.sensor.getType()) {
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    handleAccelerometerData(event.values, timestamp);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    handleGyroscopeData(event.values, timestamp);
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used here
        }
    };

    // -------------------- GYROSCOPE HANDLER --------------------
    private void handleGyroscopeData(float[] values, long timestamp) {
        // If UWB ranging is active, skip processing new gyro data.
        if (isUwbActive) {
            return;
        }

        float gyroMag = (float) Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );
        // Add the full 3-axis reading and timestamp to the gyro buffer.
        gyroWindow.add(new Pair<>(timestamp, values.clone()));

        // Check if it's time to process the rolling window.
        if (timestamp - lastProcessTime >= STEP_MS) {
            processWindow(timestamp);
            lastProcessTime = timestamp;
        }
    }

    private void handleAccelerometerData(float[] values, long timestamp) {
        // If UWB ranging is active, skip processing new accelerometer data.
        if (isUwbActive) {
            return;
        }

        float accelMag = (float) Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );
        // Add the full 3-axis reading and timestamp to the accelerometer buffer.
        accelWindow.add(new Pair<>(timestamp, values.clone()));

        // Check if it's time to process the rolling window.
        if (timestamp - lastProcessTime >= STEP_MS) {
            processWindow(timestamp);
            lastProcessTime = timestamp;
        }
    }


    private void processWindow(long currentTimestamp) {
        // Remove samples older than the current window from both buffers.
        while (!accelWindow.isEmpty() && accelWindow.get(0).first < currentTimestamp - WINDOW_SIZE_MS) {
            accelWindow.remove(0);
        }
        while (!gyroWindow.isEmpty() && gyroWindow.get(0).first < currentTimestamp - WINDOW_SIZE_MS) {
            gyroWindow.remove(0);
        }

        // --- Sort accelWindow by timestamp ---
        if (!accelWindow.isEmpty()) {
            Collections.sort(accelWindow, new Comparator<Pair<Long, float[]>>() {
                @Override
                public int compare(Pair<Long, float[]> p1, Pair<Long, float[]> p2) {
                    return Long.compare(p1.first, p2.first);
                }
            });
        }

        // 1) Compute the maximum gyroscope magnitude within the window.
        float maxGyro = 0f;
        for (Pair<Long, float[]> g : gyroWindow) {
            float mag = (float) Math.sqrt(
                    g.second[0] * g.second[0] +
                            g.second[1] * g.second[1] +
                            g.second[2] * g.second[2]
            );
            if (mag > maxGyro) {
                maxGyro = mag;
            }
        }

        // 2) Compute the standard deviation of the accelerometer magnitudes.
        int n = accelWindow.size();
        double stdAccelMag = 0.0;
        double netDisplacement = 0.0;
        if (n > 1) {
            double[] t = new double[n];
            double[] ax = new double[n];
            double[] ay = new double[n];
            double[] az = new double[n];
            double[] accelMags = new double[n];

            for (int i = 0; i < n; i++) {
                t[i] = accelWindow.get(i).first / 1000.0; // convert ms to seconds
                float[] values = accelWindow.get(i).second;
                ax[i] = values[0];
                ay[i] = values[1];
                az[i] = values[2];
                accelMags[i] = Math.sqrt(values[0]*values[0] + values[1]*values[1] + values[2]*values[2]);
            }

            // Standard deviation of the accelerometer magnitudes.
            double sumAccelMag = 0.0;
            for (int i = 0; i < n; i++) {
                sumAccelMag += accelMags[i];
            }
            double meanAccelMag = sumAccelMag / n;
            double sumSqDiff = 0.0;
            for (int i = 0; i < n; i++) {
                double diff = accelMags[i] - meanAccelMag;
                sumSqDiff += diff * diff;
            }
            stdAccelMag = Math.sqrt(sumSqDiff / n);

            // Compute net displacement via double integration.
            double[] vx = new double[n];
            double[] vy = new double[n];
            double[] vz = new double[n];
            vx[0] = 0; vy[0] = 0; vz[0] = 0;
            for (int i = 1; i < n; i++) {
                double dt = t[i] - t[i-1];
                vx[i] = vx[i-1] + 0.5 * (ax[i] + ax[i-1]) * dt;
                vy[i] = vy[i-1] + 0.5 * (ay[i] + ay[i-1]) * dt;
                vz[i] = vz[i-1] + 0.5 * (az[i] + az[i-1]) * dt;
            }
            double[] sx = new double[n];
            double[] sy = new double[n];
            double[] sz = new double[n];
            sx[0] = 0; sy[0] = 0; sz[0] = 0;
            for (int i = 1; i < n; i++) {
                double dt = t[i] - t[i-1];
                sx[i] = sx[i-1] + 0.5 * (vx[i] + vx[i-1]) * dt;
                sy[i] = sy[i-1] + 0.5 * (vy[i] + vy[i-1]) * dt;
                sz[i] = sz[i-1] + 0.5 * (vz[i] + vz[i-1]) * dt;
            }
            netDisplacement = Math.sqrt(sx[n-1]*sx[n-1] + sy[n-1]*sy[n-1] + sz[n-1]*sz[n-1]);
        }

        // 3) Check thresholds: if maxGyro, accelerometer std, and net displacement all exceed their thresholds.
        if (maxGyro > GYROSCOPE_TRIGGER_THRESHOLD &&
                stdAccelMag > ACCEL_STD_THRESHOLD &&
                netDisplacement > NET_DISPLACEMENT_THRESHOLD) {

            String message = String.format(
                    "UWB activated\nGyro Max: %.3f\nAccel Std Dev: %.3f m/s²\nNet Displacement: %.3f m\n",
                    maxGyro, stdAccelMag, netDisplacement
            );
            //
            //updateReceiveText(message);
            //logIMUData(message);

            // Iterate through the gyroscope window and log each reading.
            for (Pair<Long, float[]> gyroReading : gyroWindow) {
                String gyroMsg = String.format("GYROSCOPE TIMESTAMP: %d, X: %.8f, Y: %.8f, Z: %.8f\n",
                        gyroReading.first,
                        gyroReading.second[0],
                        gyroReading.second[1],
                        gyroReading.second[2]);
                logIMUData(gyroMsg);
            }

            // Iterate through the accelerometer window and log each reading.
            for (Pair<Long, float[]> accelReading : accelWindow) {
                String accelMsg = String.format("ACCELEROMETER TIMESTAMP: %d, X: %.8f, Y: %.8f, Z: %.8f\n",
                        accelReading.first,
                        accelReading.second[0],
                        accelReading.second[1],
                        accelReading.second[2]);
                logIMUData(accelMsg);
            }

            // (If magnetometer data is available, similar logging can be added here.)

            // Activate UWB ranging.
            activateUwbRanging();

            // Clear buffers to pause further IMU data accumulation during UWB ranging.
            gyroWindow.clear();
            accelWindow.clear();
        }


    }







    private void activateUwbRanging() {
        // Set flag to pause further IMU processing.
        isUwbActive = true;
//        updateReceiveText("UWB ranging activated.");
//        logIMUData("UWB ranging activated.\n");
        long startTimestamp = System.currentTimeMillis();
        String logEntryStart = "LOCALIZATION START TIMESTAMP: " + startTimestamp + "\n";
        logReceivedData(logEntryStart);

        send("initf 4 9600");
        // Here, insert your code to actually start UWB ranging.
        // For now, we simulate by scheduling a stop after 5 seconds.
        uwbHandler.postDelayed(stopUwbRunnable, 3000);
    }

    private Runnable stopUwbRunnable = new Runnable() {
        @Override
        public void run() {
            isUwbActive = false;
            updateReceiveText("UWB ranging stopped; resuming IMU detection.");
            send("stop");
            // Optionally, re-register sensors if needed.
        }
    };



    private void updateReceiveText(String message) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            SpannableStringBuilder spn = new SpannableStringBuilder(message + "\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
        });
    }


    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        controlLines.onPrepareOptionsMenu(menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, which) -> {
                newline = newlineValues[which];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            item.setChecked(controlLines.showControlLines(!item.isChecked()));
            return true;
        } else if (id == R.id.flowControl) {
            controlLines.selectFlowControl();
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    private static final int REQUEST_WRITE_STORAGE = 112;

    private void checkStoragePermission() {
        boolean hasPermission = ContextCompat.checkSelfPermission(getActivity(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (!hasPermission) {
            ActivityCompat.requestPermissions(getActivity(),
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        updateSendBtn(SendButtonState.Idle);
        usbSerialPort = null;
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg;
        byte[] data;
        if(hexEnabled) {
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, TextUtil.fromHexString(str));
            TextUtil.toHexString(sb, newline.getBytes());
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
        } else {
            msg = str;
            data = (str + newline).getBytes();
        }
        try {
            // Record the timestamp when the command is sent
            long sendTimestamp = System.currentTimeMillis(); // You can use System.nanoTime() for higher resolution if needed

            // Log the timestamp and the sent command to the log file
            String logEntry = "SEND TIMESTAMP: " + sendTimestamp + ", COMMAND: " + str + "\n";
            logReceivedData(logEntry);
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (SerialTimeoutException e) { // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void sendAgain(byte[] data0, int offset) {
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Busy : SendButtonState.Disabled);
        if (connected != Connected.True) {
            return;
        }
        byte[] data;
        if (offset == 0) {
            data = data0;
        } else {
            data = new byte[data0.length - offset];
            System.arraycopy(data0, offset, data, 0, data.length);
        }
        try {
            service.write(data);
        } catch (SerialTimeoutException e) {
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
            return;
        } catch (Exception e) {
            onSerialIoError(e);
        }
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
    }


    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();

        for (byte[] data : datas) {
            long receiveTimestamp = System.currentTimeMillis();
            if (flowControlFilter != null)
                data = flowControlFilter.filter(data);
            if (hexEnabled) {
                String hexData = TextUtil.toHexString(data);
                spn.append(hexData).append('\n');
                // Log the hex data
                logReceivedData(hexData + "\n");
            } else {
                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
                //logReceivedData(msg);
                String receivedString = new String(data, StandardCharsets.UTF_8);
                synchronized (dataBuffer) {
                    dataBuffer.append(receivedString);
                }
                if (receivedString.contains("!")) {
                    // Process the data in the buffer
                    //enterIdleStateFromUwb();
                    processDataBuffer();
                }

            }
//            String logEntry = "<RECEIVE TIMESTAMP: " + receiveTimestamp + ">";
//            logReceivedData(logEntry);
            // Process the received data
        }
        //receiveText.append(spn);
    }
    private void processDataBuffer() {
        synchronized (dataBuffer) {
            String data = dataBuffer.toString();
            int index = 0;

            while (true) {
                int endIndex = data.indexOf('!', index);
                if (endIndex == -1) {
                    // No complete message found, exit
                    // Remove processed data up to index
                    dataBuffer.delete(0, index);
                    break;
                }

                // Extract the message up to '!'
                String completeMessage = data.substring(index, endIndex + 1); // include '!'

                // Process the complete message
                processCompleteMessage(completeMessage);

                // Move index past the end of this message
                index = endIndex + 1;
                data = dataBuffer.toString(); // Update data in case dataBuffer was modified in processing
            }

            // Remove processed data from buffer
            dataBuffer.delete(0, index);
        }
    }

    private void processCompleteMessage(String message) {
        //logReceivedData("CIR data raw msg: " + message + "\n");
        // Initialize variables
        String fpIndex = null;
        List<Integer> cirRealValues = new ArrayList<>();
        List<Integer> cirImagValues = new ArrayList<>();
        int dCm = -1;

        // Patterns
        Pattern fpIndexPattern = Pattern.compile("Ipatov FpIndex:\\s*(\\w+)");
        Pattern cirRealValuesPattern = Pattern.compile("CIR_real_values=\\[(.*?)\\]", Pattern.DOTALL);
        Pattern cirImagValuesPattern = Pattern.compile("CIR_imag_values=\\[(.*?)\\]", Pattern.DOTALL);
        Pattern dCmPattern = Pattern.compile("\"D_cm\":\\s*(\\d+)");

        // Find FPindex
        Matcher fpIndexMatcher = fpIndexPattern.matcher(message);
        if (fpIndexMatcher.find()) {
            fpIndex = fpIndexMatcher.group(1);
        }

        // Find CIR_real_values
        Matcher cirRealValuesMatcher = cirRealValuesPattern.matcher(message);
        if (cirRealValuesMatcher.find()) {
            String numbersString = cirRealValuesMatcher.group(1);
            cirRealValues = extractNumbers(numbersString);
        }

        // Find CIR_imag_values
        Matcher cirImagValuesMatcher = cirImagValuesPattern.matcher(message);
        if (cirImagValuesMatcher.find()) {
            String numbersString = cirImagValuesMatcher.group(1);
            cirImagValues = extractNumbers(numbersString);
        }

        // Find D_cm
        Matcher dCmMatcher = dCmPattern.matcher(message);
        if (dCmMatcher.find()) {
            dCm = Integer.parseInt(dCmMatcher.group(1));
        }

        // Now, process the CIR data
        if (fpIndex != null && !cirRealValues.isEmpty() && !cirImagValues.isEmpty()) {
            // Process the CIR block
            processCirData(fpIndex, cirRealValues, cirImagValues, dCm);
        } else {
            // Missing data, handle error
            Log.e("CIRParser", "Incomplete CIR data");
        }
    }
    private List<Integer> extractNumbers(String s) {
        List<Integer> numbers = new ArrayList<>();
        Pattern numberPattern = Pattern.compile("-?\\d+");
        Matcher matcher = numberPattern.matcher(s);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }
        return numbers;
    }

    private void processCirData(String fpIndex, List<Integer> cirRealValues, List<Integer> cirImagValues, int dCm) {
        Map<String, Object> cirData = new HashMap<>();
        cirData.put("fpIndex", fpIndex);
        cirData.put("cirRealValues", cirRealValues);
        cirData.put("cirImagValues", cirImagValues);
        cirData.put("dCm", dCm);

        // Enqueue the CIR data for processing
        try {
            cirDataQueue.put(cirData);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private ExecutorService processingExecutor = Executors.newSingleThreadExecutor();

    public void startProcessing() {
        processingExecutor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Map<String, Object> cirData = cirDataQueue.take();
                    // Process the CIR data
                    processCirDataAsync(cirData);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    // Inside your real-time processing code, you'd do something like:
    UnboundedPeakTracker peakTracker = new UnboundedPeakTracker(
            90,   // tolerance
            5,     // maxUnmatchedFrames
            4,     // top-n to check, for example
            false   // debug
    );
    private void processCirDataAsync(Map<String, Object> cirData) {
        long frameStartTimeNs = System.nanoTime(); // Start total timer

        // 1) Parse raw data from the Map
        String fpIndex = (String) cirData.get("fpIndex");
        List<Integer> cirRealValues = (List<Integer>) cirData.get("cirRealValues");
        List<Integer> cirImagValues = (List<Integer>) cirData.get("cirImagValues");
        int dCm = (int) cirData.get("dCm");  // optional distance in cm

        // 2) Convert FPindex from hex to fixed-point
        double firstPathIndex = hexToFixedPoint(fpIndex);

        // 3) Convert CIR values (real & imag) into arrays
        double[] cirRealArray = cirRealValues.stream().mapToDouble(Integer::doubleValue).toArray();
        double[] cirImagArray = cirImagValues.stream().mapToDouble(Integer::doubleValue).toArray();

        // 4) Compute CIR magnitude
        double[] cirMagnitude = new double[cirRealArray.length];
        for (int i = 0; i < cirRealArray.length; i++) {
            cirMagnitude[i] = Math.sqrt(cirRealArray[i] * cirRealArray[i] + cirImagArray[i] * cirImagArray[i]);
        }

        // 5+6) Upsample the CIR and Align
        long upsampleAlignStartNs = System.nanoTime();
        int CIRlength = cirMagnitude.length;
        double[] upsampledCIR = resampleFFT(cirMagnitude, 64 * CIRlength);
        double[] alignedCIR = alignCir(upsampledCIR, firstPathIndex);
        double upsampleAlignTimeMs = (System.nanoTime() - upsampleAlignStartNs) / 1e6;

        // 7) Detect peaks
        long peakDetectionStartNs = System.nanoTime();
        double amplitudeThreshold = 220.0;
        int minDistance = 90;
        List<Integer> framePeaks = detectPeaksLocalMax(
                alignedCIR,
                amplitudeThreshold,
                minDistance,
                /*debug=*/true
        );
        UnboundedPeakTracker.UpdateResult updateResult = peakTracker.update(framePeaks);
        List<Integer> stablePeaks = updateResult.getFinalIndices();
        boolean stable = updateResult.isStable();
        double peakDetectionTimeMs = (System.nanoTime() - peakDetectionStartNs) / 1e6;

        // 9) Feature extraction
        long featureExtractionStartNs = System.nanoTime();
        Map<String, Double> featureMap = buildFeaturesFromStablePeaks(
                stablePeaks,
                alignedCIR,
                (double) dCm
        );
        double featureExtractionTimeMs = (System.nanoTime() - featureExtractionStartNs) / 1e6;

        if (featureMap == null || featureMap.isEmpty()) {
            logReceivedData("No valid features extracted; skipping classification.\n");
            return;
        }

        // 11) Build feature vector
        double[] featureVector = new double[]{
                featureMap.getOrDefault("Num_Peaks", 0.0),
                featureMap.getOrDefault("Pmax", 0.0),
                featureMap.getOrDefault("Tmax", 0.0),
                featureMap.getOrDefault("P_pos_ratio_1", 1.0),
                featureMap.getOrDefault("P_power_ratio_1", 1.0),
                featureMap.getOrDefault("T_pos_distance_1", 0.0),
                featureMap.getOrDefault("T_power_distance_1", 0.0),
                featureMap.getOrDefault("P_pos_ratio_2", 1.0),
                featureMap.getOrDefault("P_power_ratio_2", 1.0),
                featureMap.getOrDefault("T_pos_distance_2", 0.0),
                featureMap.getOrDefault("T_power_distance_2", 0.0),
                featureMap.getOrDefault("P_pos_ratio_3", 1.0),
                featureMap.getOrDefault("P_power_ratio_3", 1.0),
                featureMap.getOrDefault("T_pos_distance_3", 0.0),
                featureMap.getOrDefault("T_power_distance_3", 0.0),
                featureMap.getOrDefault("DistanceBin", 0.0)
        };

        // 12) Inference
        long inferenceStartNs = System.nanoTime();
        double[] prediction = model.score(featureVector);
        int predictedIndex = argMax(prediction);
        double inferenceTimeMs = (System.nanoTime() - inferenceStartNs) / 1e6;

        double totalFrameTimeMs = (System.nanoTime() - frameStartTimeNs) / 1e6;

        // === Log timing breakdown ===
        StringBuilder timingLog = new StringBuilder();
        timingLog.append("\n   Upsample+Align:  ").append(String.format("%.3f ms", upsampleAlignTimeMs))
                .append("\n   Detect+Track:    ").append(String.format("%.3f ms", peakDetectionTimeMs))
                .append("\n   FeatExtract:     ").append(String.format("%.3f ms", featureExtractionTimeMs))
                .append("\n   Inference:       ").append(String.format("%.3f ms", inferenceTimeMs))
                .append("\n   TOTAL Frame:     ").append(String.format("%.3f ms", totalFrameTimeMs));

        //logReceivedData(timingLog.toString());
    }



// 4) Logging or display








    /**
     * detectPeaksLocalMax: a simplified version that only uses local maxima
     * with a specified amplitude threshold and minDistance,
     * mirroring the "regular peaks" logic in Python's single_frame_peaks.
     */
    private List<Integer> detectPeaksLocalMax(
            double[] data,
            double amplitudeThreshold,
            int minDistance,
            boolean debug
    ) {
        if (data == null || data.length < 3) {
            return new ArrayList<>();
        }

        // Step A: Identify local maxima above amplitudeThreshold
        List<Integer> rawPeaks = new ArrayList<>();
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] > amplitudeThreshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                rawPeaks.add(i);
            }
        }

        // Step B: Apply minDistance by prioritizing peaks with higher amplitude
        // Sort peaks in descending amplitude
        rawPeaks.sort((p1, p2) -> Double.compare(data[p2], data[p1]));

        List<Integer> filteredPeaks = new ArrayList<>();
        boolean[] removed = new boolean[data.length];

        for (int peakIdx : rawPeaks) {
            if (!removed[peakIdx]) {
                filteredPeaks.add(peakIdx);
                // Mark ±minDistance as removed
                int start = Math.max(peakIdx - minDistance, 0);
                int end   = Math.min(peakIdx + minDistance, data.length - 1);
                for (int j = start; j <= end; j++) {
                    removed[j] = true;
                }
                // Re-enable the actual peak to keep it recognized
                removed[peakIdx] = false;
            }
        }

        // Finally, sort in ascending order of index
        filteredPeaks.sort(Integer::compare);

        if (debug) {
            Log.d("detectPeaksLocalMax",
                    String.format("rawPeaks=%d, finalPeaks=%d", rawPeaks.size(), filteredPeaks.size()));
        }

        return filteredPeaks;
    }




    private void classifyCollectedCIRs() {
        // Clear previous classification results
        synchronized (classificationResults) {
            classificationResults.clear();
        }

        // Classify each set of features
        for (Map<String, Double> features : collectedFeatures) {
            // Prepare the feature vector in the correct order
            double[] featureVector = new double[] {
                    features.getOrDefault("Num_Peaks", 0.0),
                    features.getOrDefault("Pmax", 0.0),
                    features.getOrDefault("Tmax", 0.0),
                    features.getOrDefault("P_pos_ratio_1", 1.0),
                    features.getOrDefault("P_power_ratio_1", 1.0),
                    features.getOrDefault("T_pos_distance_1", 0.0),
                    features.getOrDefault("T_power_distance_1", 0.0),
                    features.getOrDefault("P_pos_ratio_2", 1.0),
                    features.getOrDefault("P_power_ratio_2", 1.0),
                    features.getOrDefault("T_pos_distance_2", 0.0),
                    features.getOrDefault("T_power_distance_2", 0.0),
                    features.getOrDefault("P_pos_ratio_3", 1.0),
                    features.getOrDefault("P_power_ratio_3", 1.0),
                    features.getOrDefault("T_pos_distance_3", 0.0),
                    features.getOrDefault("T_power_distance_3", 0.0)
            };

            // Classify using the model
            double[] prediction = model.score(featureVector);

            // Interpret the prediction
            int predictedLabel = argMax(prediction);

            // Store the classification result
            synchronized (classificationResults) {
                classificationResults.add(predictedLabel);
            }
        }

        // Perform majority voting
        int finalPrediction = majorityVote(classificationResults);


        // Transition back to Idle State and handle the result
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            logReceivedData("Final Classification Result: " + finalPrediction + "\n");
            updateReceiveText(String.valueOf(finalPrediction));
            // Optionally display the classification result
            // displayClassificationResult(className);
// testing: stay in UWB ranging forever
//            if (currentState == MovementState.UWB_RANGING) {
//                enterIdleStateFromUwb();
//            }
        });

        // Clear the collected data
        synchronized (collectedFeatures) {
            collectedFeatures.clear();
        }
        synchronized (classificationResults) {
            classificationResults.clear();
        }
    }
    private int majorityVote(List<Integer> predictions) {
        Map<Integer, Integer> voteCounts = new HashMap<>();
        for (int prediction : predictions) {
            voteCounts.put(prediction, voteCounts.getOrDefault(prediction, 0) + 1);
        }

        int maxVotes = 0;
        int majorityLabel = -1;
        for (Map.Entry<Integer, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                majorityLabel = entry.getKey();
            }
        }

        return majorityLabel;
    }

    public double[] resampleFFT(double[] signal, int newLength) {
        int originalLength = signal.length;

        // Compute the FFT of the signal
        DoubleFFT_1D fftDo = new DoubleFFT_1D(originalLength);
        double[] fft = new double[2 * originalLength];
        System.arraycopy(signal, 0, fft, 0, originalLength);
        fftDo.realForwardFull(fft);

        // Number of FFT points (complex numbers)
        int numFFTPoints = fft.length / 2;

        // Determine the scaling factor
        double scale = (double) newLength / originalLength;

        // Adjust the FFT to the new length
        int newNumFFTPoints = newLength;
        double[] newFFT = new double[2 * newNumFFTPoints];

        int minPoints = Math.min(numFFTPoints, newNumFFTPoints);
        int halfPoints = minPoints / 2;

        // Copy the positive frequencies
        System.arraycopy(fft, 0, newFFT, 0, 2 * halfPoints);

        // If upsampling, zero-pad the remaining frequencies
        // If downsampling, higher frequencies are discarded automatically

        // Copy the negative frequencies
        System.arraycopy(fft, fft.length - 2 * halfPoints, newFFT, newFFT.length - 2 * halfPoints, 2 * halfPoints);

        // Inverse FFT to get the resampled signal
        DoubleFFT_1D ifftDo = new DoubleFFT_1D(newLength);
        ifftDo.complexInverse(newFFT, true);

        // Extract the real part of the inverse FFT result
        double[] resampledSignal = new double[newLength];
        for (int i = 0; i < newLength; i++) {
            resampledSignal[i] = newFFT[2 * i] * scale;
        }

        return resampledSignal;
    }



    private double hexToFixedPoint(String hexValue) {
        // Remove any leading '0x' or leading zeros
        hexValue = hexValue.replace("0x", "").replaceAll("^0+", "");

        if (hexValue.isEmpty()) {
            return 0.0;
        }

        int intValue = Integer.parseInt(hexValue, 16);
        String binValue = String.format("%16s", Integer.toBinaryString(intValue)).replace(' ', '0');
        String integerPart = binValue.substring(0, 10);
        String fractionalPart = binValue.substring(10);

        int integerValue = Integer.parseInt(integerPart, 2);
        double fractionalValue = 0.0;
        for (int i = 0; i < fractionalPart.length(); i++) {
            if (fractionalPart.charAt(i) == '1') {
                fractionalValue += Math.pow(2, -(i + 1));
            }
        }
        return integerValue + fractionalValue;
    }

    private double[] alignCir(double[] resampledMagnitude, double firstPathIndex) {
        int upsampleFactor = 64;
        int adjustedIndex = (int) Math.round((firstPathIndex - 801 + CIRlength) * upsampleFactor);

        double[] alignedCIR;
        if (adjustedIndex < 0) {
            int startIndex = -adjustedIndex;
            if (startIndex >= resampledMagnitude.length) {
                alignedCIR = new double[0];
            } else {
                alignedCIR = Arrays.copyOfRange(resampledMagnitude, startIndex, resampledMagnitude.length);
            }
        } else {
            if (adjustedIndex >= resampledMagnitude.length) {
                alignedCIR = new double[0];
            } else {
                alignedCIR = Arrays.copyOfRange(resampledMagnitude, adjustedIndex, resampledMagnitude.length);
            }
        }
        return alignedCIR;
    }


    public static class UnboundedPeakTracker {

        private static final String TAG = "UnboundedPeakTracker";

        private final int tolerance;           // Max distance for matching old vs. new
        private final int maxUnmatchedFrames;  // If unmatched_count > this, remove the peak
        private final int n;                   // Check first n peaks by ascending index for instability
        private final boolean debug;           // If true, print debug logs

        private final List<Peak> oldPeaks;     // The list of stored old peaks
        private int nextPeakId;               // Assign unique IDs to newly created peaks

        // Container for storing each tracked peak
        private static class Peak {
            int peakId;
            int index;
            int unmatchedCount; // increment each frame if not matched

            Peak(int peakId, int index) {
                this.peakId = peakId;
                this.index = index;
                this.unmatchedCount = 0;
            }

            @Override
            public String toString() {
                return String.format("{peakId=%d, index=%d, unmatchedCount=%d}",
                        peakId, index, unmatchedCount);
            }
        }

        // Return structure (optional but convenient)
        public static class UpdateResult {
            private final List<Integer> finalIndices;
            private final boolean stable;

            public UpdateResult(List<Integer> finalIndices, boolean stable) {
                this.finalIndices = finalIndices;
                this.stable = stable;
            }

            public List<Integer> getFinalIndices() { return finalIndices; }
            public boolean isStable() { return stable; }

            @Override
            public String toString() {
                return String.format(
                        "{stable=%b, finalIndices=%s}", stable, finalIndices);
            }
        }

        public UnboundedPeakTracker(int tolerance, int maxUnmatchedFrames, int n, boolean debug) {
            this.tolerance = tolerance;
            this.maxUnmatchedFrames = maxUnmatchedFrames;
            this.n = n;
            this.debug = debug;

            this.oldPeaks = new ArrayList<>();
            this.nextPeakId = 1;
        }

        public void reset() {
            if (debug) {
                Log.d(TAG, "[reset()] Clearing all stored peaks.");
            }
            oldPeaks.clear();
            nextPeakId = 1;
        }

        /**
         * Updates the tracker with new peaks (by index).  We do the python-like approach:
         *   • increment unmatched_count for old peaks
         *   • for each new index, match or create new
         *   • remove stale peaks
         *   • sort by index, find top n => count unmatched + newly created
         *   • if total > 1 => unstable => remove unmatched
         * @param newPeakIndices Detected peaks in the current frame
         * @return UpdateResult(finalIndices, stable)
         */
        public UpdateResult update(List<Integer> newPeakIndices) {
            if (debug) {
                Log.d(TAG, String.format("\n[update()] newPeaks=%s\noldPeaks(before)=%s",
                        newPeakIndices, oldPeaks));
            }

            // 1) Increment unmatched_count
            for (Peak op : oldPeaks) {
                op.unmatchedCount++;
            }

            // We'll track which indexes are "new" so we can see if they're in top n
            List<Integer> newlyCreatedIndices = new ArrayList<>();

            // 2) Cross-order matching
            for (int newIdx : newPeakIndices) {
                int bestIndex = -1;
                int bestDist = Integer.MAX_VALUE;
                for (int i = 0; i < oldPeaks.size(); i++) {
                    Peak op = oldPeaks.get(i);
                    int dist = Math.abs(newIdx - op.index);
                    if (dist <= tolerance && dist < bestDist) {
                        bestDist = dist;
                        bestIndex = i;
                    }
                }

                if (bestIndex >= 0) {
                    // matched
                    Peak matched = oldPeaks.get(bestIndex);
                    matched.index = newIdx;
                    matched.unmatchedCount = 0;
                    if (debug) {
                        Log.d(TAG, String.format("   [MATCHED] newPeak=%d => oldPeakId=%d (dist=%d)",
                                newIdx, matched.peakId, bestDist));
                    }
                } else {
                    // new peak
                    Peak newPeak = new Peak(nextPeakId, newIdx);
                    nextPeakId++;
                    oldPeaks.add(newPeak);
                    newlyCreatedIndices.add(newIdx);

                    if (debug) {
                        Log.d(TAG, String.format("   [NEW] Peak %d => peakId=%d", newIdx, newPeak.peakId));
                    }
                }
            }

            // 3) Remove stale peaks
            List<Integer> staleIndices = new ArrayList<>();
            for (int i = 0; i < oldPeaks.size(); i++) {
                if (oldPeaks.get(i).unmatchedCount > maxUnmatchedFrames) {
                    staleIndices.add(i);
                }
            }
            for (int i = staleIndices.size() - 1; i >= 0; i--) {
                int idx = staleIndices.get(i);
                if (debug) {
                    Log.d(TAG, String.format("   [STALE] Removing oldPeakId=%d, unmatchedCount=%d",
                            oldPeaks.get(idx).peakId, oldPeaks.get(idx).unmatchedCount));
                }
                oldPeaks.remove(idx);
            }

            // 4) Sort old_peaks by ascending index
            oldPeaks.sort(Comparator.comparingInt(p -> p.index));

            // 5) Count unmatched_in_top_n + new_in_top_n
            boolean unstable = false;
            if (!oldPeaks.isEmpty()) {
                int count = Math.min(n, oldPeaks.size());
                int unmatchedInTopN = 0;
                // gather the top-n's indexes for counting new
                List<Integer> topNindexes = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    topNindexes.add(oldPeaks.get(i).index);
                    if (oldPeaks.get(i).unmatchedCount > 0) {
                        unmatchedInTopN++;
                    }
                }

                // count how many newlyCreated indices appear in top-n
                int newPeaksInTopN = 0;
                for (Integer newCreatedIdx : newlyCreatedIndices) {
                    if (topNindexes.contains(newCreatedIdx)) {
                        newPeaksInTopN++;
                    }
                }

                int totalUnstablePeaks = unmatchedInTopN + newPeaksInTopN;
                if (debug) {
                    Log.d(TAG, String.format(
                            "   [DEBUG] unmatchedInTopN=%d, newPeaksInTopN=%d => total=%d",
                            unmatchedInTopN, newPeaksInTopN, totalUnstablePeaks));
                }

                if (totalUnstablePeaks > 1) {
                    unstable = true;
                    if (debug) {
                        Log.d(TAG, String.format(
                                "   [UNSTABLE] %d (unmatched + new) in top %d => partial removal of unmatched peaks.",
                                totalUnstablePeaks, n));
                    }

                    // remove only unmatched
                    List<Peak> retained = new ArrayList<>();
                    for (Peak op : oldPeaks) {
                        if (op.unmatchedCount == 0) {
                            retained.add(op);
                        } else {
                            if (debug) {
                                Log.d(TAG, String.format(
                                        "   -> Removing unmatched oldPeakId=%d, index=%d",
                                        op.peakId, op.index));
                            }
                        }
                    }
                    oldPeaks.clear();
                    oldPeaks.addAll(retained);
                }
            }

            // 6) finalIndices
            List<Integer> finalIndices = new ArrayList<>();
            for (Peak pk : oldPeaks) {
                finalIndices.add(pk.index);
            }

            boolean stable = !unstable;
            if (debug) {
                Log.d(TAG, String.format(
                        "   [DONE] stable=%b, oldPeaks(after)=%s, finalIndices=%s",
                        stable, oldPeaks, finalIndices));
            }
            return new UpdateResult(finalIndices, stable);
        }
    }








    /**
     * Build feature map from a list of stable peak indices in the aligned CIR array.
     * This replicates the Python logic for computing peak-based features.
     *
     * @param stablePeaks A list of final stable peak indices (e.g., from peakTracker.update(...))
     * @param alignedCIR   The CIR magnitude array after upsampling and alignment
     * @param distanceBin  (Optional) a distance measurement or bin ID. Pass null if unavailable.
     * @return A map of feature names to values, e.g. "Num_Peaks", "Pmax", ...
     *         Returns an empty or default-filled map if no stable peaks are available.
     */
    private Map<String, Double> buildFeaturesFromStablePeaks(
            List<Integer> stablePeaks,
            double[] alignedCIR,
            Double distanceBin
    ) {
        // Using LinkedHashMap preserves a consistent insertion order (like Python).
        Map<String, Double> feats = new LinkedHashMap<>();

        // 1) If no peaks, fill default placeholders
        if (stablePeaks == null || stablePeaks.isEmpty()) {
            feats.put("Num_Peaks", 0.0);
            feats.put("Pmax", 0.0);
            feats.put("Tmax", 0.0);

            feats.put("P_pos_ratio_1", 1.0);
            feats.put("P_power_ratio_1", 1.0);
            feats.put("T_pos_distance_1", 0.0);
            feats.put("T_power_distance_1", 0.0);

            feats.put("P_pos_ratio_2", 1.0);
            feats.put("P_power_ratio_2", 1.0);
            feats.put("T_pos_distance_2", 0.0);
            feats.put("T_power_distance_2", 0.0);

            feats.put("P_pos_ratio_3", 1.0);
            feats.put("P_power_ratio_3", 1.0);
            feats.put("T_pos_distance_3", 0.0);
            feats.put("T_power_distance_3", 0.0);

            // Optional field(s)
            feats.put("DistanceBin", (distanceBin != null) ? distanceBin : Double.NaN);

            return feats;
        }

        // 2) Gather the indices + amplitudes from the alignedCIR
        //    (Ensure each index is valid)
        List<Integer> validIndices = new ArrayList<>();
        List<Double> validAmps = new ArrayList<>();

        for (Integer idx : stablePeaks) {
            if (idx >= 0 && idx < alignedCIR.length) {
                validIndices.add(idx);
                validAmps.add(alignedCIR[idx]);
            }
        }

        // If all stablePeaks were out of range, treat as no peaks
        if (validIndices.isEmpty()) {
            feats.put("Num_Peaks", 0.0);
            feats.put("Pmax", 0.0);
            feats.put("Tmax", 0.0);

            feats.put("P_pos_ratio_1", 1.0);
            feats.put("P_power_ratio_1", 1.0);
            feats.put("T_pos_distance_1", 0.0);
            feats.put("T_power_distance_1", 0.0);

            feats.put("P_pos_ratio_2", 1.0);
            feats.put("P_power_ratio_2", 1.0);
            feats.put("T_pos_distance_2", 0.0);
            feats.put("T_power_distance_2", 0.0);

            feats.put("P_pos_ratio_3", 1.0);
            feats.put("P_power_ratio_3", 1.0);
            feats.put("T_pos_distance_3", 0.0);
            feats.put("T_power_distance_3", 0.0);

            feats.put("DistanceBin", (distanceBin != null) ? distanceBin : Double.NaN);
            return feats;
        }

        // 3) Build arrays for easier sorting
        int n = validIndices.size();
        int[] idxArray = new int[n];
        double[] ampArray = new double[n];
        for (int i = 0; i < n; i++) {
            idxArray[i] = validIndices.get(i);
            ampArray[i] = validAmps.get(i);
        }

        // 4) Basic feature: number of peaks
        feats.put("Num_Peaks", (double) n);

        // 5) Find max amplitude + index
        int idxMax = argMax(ampArray);
        double pmax = ampArray[idxMax];
        double tmax = idxArray[idxMax];
        feats.put("Pmax", pmax);
        feats.put("Tmax", tmax);

        // 6) Sort by position (ascending) and by amplitude (descending) for ratio calculations
        int[] sortedByPosition = sortIndicesByValues(idxArray);          // ascending
        int[] sortedByAmplitude = sortIndicesByValuesDescending(ampArray); // descending

        // 7) We'll fill up to p=4 => 3 ratio sets
        final int p = 4;
        List<Double> pPosRatios   = new ArrayList<>();
        List<Double> pPowRatios   = new ArrayList<>();
        List<Double> tPosDistances= new ArrayList<>();
        List<Double> tPowDistances= new ArrayList<>();

        if (n > 1) {
            int numRatios = Math.min(p - 1, n - 1);

            // (A) Position-based comparisons
            // Compare each subsequent peak to the first peak by position
            for (int j = 1; j <= numRatios; j++) {
                double denomAmp = ampArray[sortedByPosition[j]];
                double numerAmp = ampArray[sortedByPosition[0]];
                double ratio = (denomAmp != 0.0) ? (numerAmp / denomAmp) : 1.0;
                pPosRatios.add(ratio);

                double dist = idxArray[sortedByPosition[j]] - idxArray[sortedByPosition[0]];
                tPosDistances.add(dist);
            }

            // (B) Amplitude-based comparisons
            // Compare each subsequent peak to the highest amplitude peak
            for (int j = 1; j <= numRatios; j++) {
                double denomAmp = ampArray[sortedByAmplitude[j]];
                double numerAmp = ampArray[sortedByAmplitude[0]];
                double ratio = (denomAmp != 0.0) ? (numerAmp / denomAmp) : 1.0;
                pPowRatios.add(ratio);

                double dist = idxArray[sortedByAmplitude[j]] - idxArray[sortedByAmplitude[0]];
                tPowDistances.add(dist);
            }
        }

        // 8) Ensure we have 3 entries for each
        while (pPosRatios.size() < 3)      pPosRatios.add(1.0);
        while (tPosDistances.size() < 3)   tPosDistances.add(0.0);
        while (pPowRatios.size() < 3)      pPowRatios.add(1.0);
        while (tPowDistances.size() < 3)   tPowDistances.add(0.0);

        // 9) Insert them into the feature map
        feats.put("P_pos_ratio_1", pPosRatios.get(0));
        feats.put("P_power_ratio_1", pPowRatios.get(0));
        feats.put("T_pos_distance_1", tPosDistances.get(0));
        feats.put("T_power_distance_1", tPowDistances.get(0));

        feats.put("P_pos_ratio_2", pPosRatios.get(1));
        feats.put("P_power_ratio_2", pPowRatios.get(1));
        feats.put("T_pos_distance_2", tPosDistances.get(1));
        feats.put("T_power_distance_2", tPowDistances.get(1));

        feats.put("P_pos_ratio_3", pPosRatios.get(2));
        feats.put("P_power_ratio_3", pPowRatios.get(2));
        feats.put("T_pos_distance_3", tPosDistances.get(2));
        feats.put("T_power_distance_3", tPowDistances.get(2));

        // 10) Optional fields: e.g. "DistanceBin"
        feats.put("DistanceBin", (distanceBin != null) ? distanceBin : Double.NaN);

        return feats;
    }



    private int argMax(double[] arr) {
        int maxIndex = 0;
        double maxVal = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > maxVal) {
                maxVal = arr[i];
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    /** Sort integer array in ascending order, return index array. */
    private int[] sortIndicesByValues(int[] arr) {
        Integer[] indices = new Integer[arr.length];
        for (int i = 0; i < arr.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, Comparator.comparingInt(i -> arr[i]));
        return Arrays.stream(indices).mapToInt(Integer::intValue).toArray();
    }

    /** Sort double array in descending order, return index array. */
    private int[] sortIndicesByValuesDescending(double[] arr) {
        Integer[] indices = new Integer[arr.length];
        for (int i = 0; i < arr.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, (i1, i2) -> Double.compare(arr[i2], arr[i1]));
        return Arrays.stream(indices).mapToInt(Integer::intValue).toArray();
    }








    private void logIMUData(String data) {
        new Thread(() -> {
            String fileName = "imu_data_log.txt";
            File filePath = new File(getActivity().getFilesDir(), fileName);

            try {
                FileWriter writer = new FileWriter(filePath, true); // 'true' for append mode
                writer.append(data);
                writer.flush();
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
    private void clearIMULogFile() {
        String fileName = "imu_data_log.txt";
        File filePath = new File(getActivity().getFilesDir(), fileName);

        if (filePath.exists()) {
            try {
                FileWriter writer = new FileWriter(filePath, false); // 'false' to overwrite the file
                writer.write("");
                writer.flush();
                writer.close();
                Log.d("TerminalFragment", "IMU log file cleared");
            } catch (IOException e) {
                e.printStackTrace();
                Log.e("TerminalFragment", "Failed to clear IMU log file: " + e.getMessage());
            }
        }
    }
    private void logReceivedData(String data) {
        String fileName = "received_data_log.txt";
        File filePath = new File(getActivity().getFilesDir(), fileName);

        try {
            FileWriter writer = new FileWriter(filePath, true); // 'true' for append mode
            writer.append(data);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void shareLogFile() {
        String fileName1 = "received_data_log.txt";
        String fileName2 = "imu_data_log.txt";
        File filePath1 = new File(getActivity().getFilesDir(), fileName1);
        File filePath2 = new File(getActivity().getFilesDir(), fileName2);

        // Write internal log files to external storage
//        writeLogFileToExternalStorage(fileName1);
//        writeLogFileToExternalStorage(fileName2);

        ArrayList<Uri> filesToShare = new ArrayList<>();

        if (filePath1.exists()) {
            Uri fileUri1 = FileProvider.getUriForFile(getActivity(),
                    getActivity().getPackageName() + ".fileprovider", filePath1);
            filesToShare.add(fileUri1);
        } else {
            Toast.makeText(getActivity(), "Data log file not found", Toast.LENGTH_SHORT).show();
        }

        if (filePath2.exists()) {
            Uri fileUri2 = FileProvider.getUriForFile(getActivity(),
                    getActivity().getPackageName() + ".fileprovider", filePath2);
            filesToShare.add(fileUri2);
        } else {
            Toast.makeText(getActivity(), "IMU log file not found", Toast.LENGTH_SHORT).show();
        }

        if (!filesToShare.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("text/plain");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, filesToShare);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share log files via"));
        } else {
            Toast.makeText(getActivity(), "No log files to share", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Copies the given log file from internal storage to the external Documents directory.
     * If a file with the same name already exists, a timestamp is appended to the filename.
     */
    private void writeLogFileToExternalStorage(String fileName) {
        File internalFile = new File(getActivity().getFilesDir(), fileName);
        if (!internalFile.exists()) {
            Toast.makeText(getActivity(), "File " + fileName + " not found in internal storage", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the external Documents directory for the app.
        File externalDir = getActivity().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (externalDir != null && !externalDir.exists()) {
            externalDir.mkdirs();
        }

        // Create an external file with the same name.
        File externalFile = new File(externalDir, fileName);
        // If the file already exists, append a timestamp to make it unique.
        if (externalFile.exists()) {
            String newName = fileName.replace(".", "_" + System.currentTimeMillis() + ".");
            externalFile = new File(externalDir, newName);
        }

        try {
            InputStream in = new FileInputStream(internalFile);
            OutputStream out = new FileOutputStream(externalFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
            Toast.makeText(getActivity(), "Log file written to external storage: " + externalFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), "Error writing log file to external storage", Toast.LENGTH_SHORT).show();
        }
    }


    private void clearLogFile() {
        String fileName = "received_data_log.txt";
        File filePath = new File(getActivity().getFilesDir(), fileName);

        if (filePath.exists()) {
            try {
                // Overwrite the file with empty content to clear it
                FileWriter writer = new FileWriter(filePath, false); // 'false' to overwrite the file
                writer.write("");
                writer.flush();
                writer.close();
                Log.d("TerminalFragment", "Log file cleared");
            } catch (IOException e) {
                e.printStackTrace();
                Log.e("TerminalFragment", "Failed to clear log file: " + e.getMessage());
            }
        } else {
            // Optionally, create the file if it doesn't exist
            try {
                boolean created = filePath.createNewFile();
                if (created) {
                    Log.d("TerminalFragment", "Log file created");
                } else {
                    Log.e("TerminalFragment", "Failed to create log file");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }



    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    void updateSendBtn(SendButtonState state) {
        sendBtn.setEnabled(state == SendButtonState.Idle);
        sendBtn.setImageAlpha(state == SendButtonState.Idle ? 255 : 64);
        sendBtn.setImageResource(state == SendButtonState.Disabled ? R.drawable.ic_block_white_24dp : R.drawable.ic_send_white_24dp);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;

        private View frame;
        private ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        private boolean showControlLines;                                               // show & update control line buttons
        private UsbSerialPort.FlowControl flowControl = UsbSerialPort.FlowControl.NONE; // !NONE: update send button state

        boolean sendAllowed = true;

        ControlLines() {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        void onCreateView(View view) {
            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        void onPrepareOptionsMenu(Menu menu) {
            try {
                EnumSet<UsbSerialPort.ControlLine> scl = usbSerialPort.getSupportedControlLines();
                EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
                menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty());
                menu.findItem(R.id.controlLines).setChecked(showControlLines);
                menu.findItem(R.id.flowControl).setEnabled(sfc.size() > 1);
            } catch (Exception ignored) {
            }
        }

        void selectFlowControl() {
            EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
            UsbSerialPort.FlowControl fc = usbSerialPort.getFlowControl();
            ArrayList<String> names = new ArrayList<>();
            ArrayList<UsbSerialPort.FlowControl> values = new ArrayList<>();
            int pos = 0;
            names.add("<none>");
            values.add(UsbSerialPort.FlowControl.NONE);
            if (sfc.contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                names.add("RTS/CTS control lines");
                values.add(UsbSerialPort.FlowControl.RTS_CTS);
                if (fc == UsbSerialPort.FlowControl.RTS_CTS) pos = names.size() -1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.DTR_DSR)) {
                names.add("DTR/DSR control lines");
                values.add(UsbSerialPort.FlowControl.DTR_DSR);
                if (fc == UsbSerialPort.FlowControl.DTR_DSR) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF_INLINE)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF_INLINE);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF_INLINE) pos = names.size() - 1;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Flow Control");
            builder.setSingleChoiceItems(names.toArray(new CharSequence[0]), pos, (dialog, which) -> {
                dialog.dismiss();
                try {
                    flowControl = values.get(which);
                    usbSerialPort.setFlowControl(flowControl);
                    flowControlFilter = usbSerialPort.getFlowControl() == UsbSerialPort.FlowControl.XON_XOFF_INLINE ? new XonXoffFilter() : null;
                    start();
                } catch (Exception e) {
                    status("Set flow control failed: "+e.getClass().getName()+" "+e.getMessage());
                    flowControl = UsbSerialPort.FlowControl.NONE;
                    flowControlFilter = null;
                    start();
                }
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
            builder.setNeutralButton("Info", (dialog, which) -> {
                dialog.dismiss();
                AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                builder2.setTitle("Flow Control").setMessage("If send is stopped by the external device, the 'Send' button changes to 'Blocked' icon.");
                builder2.create().show();
            });
            builder.create().show();
        }

        public boolean showControlLines(boolean show) {
            showControlLines = show;
            start();
            return showControlLines;
        }

        void start() {
            if (showControlLines) {
                try {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getSupportedControlLines();
                    rtsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RTS) ? View.VISIBLE : View.INVISIBLE);
                    ctsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CTS) ? View.VISIBLE : View.INVISIBLE);
                    dtrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DTR) ? View.VISIBLE : View.INVISIBLE);
                    dsrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DSR) ? View.VISIBLE : View.INVISIBLE);
                    cdBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CD)   ? View.VISIBLE : View.INVISIBLE);
                    riBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RI)   ? View.VISIBLE : View.INVISIBLE);
                } catch (IOException e) {
                    showControlLines = false;
                    status("getSupportedControlLines() failed: " + e.getMessage());
                }
            }
            frame.setVisibility(showControlLines ? View.VISIBLE : View.GONE);
            if(flowControl == UsbSerialPort.FlowControl.NONE) {
                sendAllowed = true;
                updateSendBtn(SendButtonState.Idle);
            }

            mainLooper.removeCallbacks(runnable);
            if (showControlLines || flowControl != UsbSerialPort.FlowControl.NONE) {
                run();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            sendAllowed = true;
            updateSendBtn(SendButtonState.Idle);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                if (showControlLines) {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                    if(rtsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setChecked(!rtsBtn.isChecked());
                    if(ctsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setChecked(!ctsBtn.isChecked());
                    if(dtrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setChecked(!dtrBtn.isChecked());
                    if(dsrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setChecked(!dsrBtn.isChecked());
                    if(cdBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.CD))  cdBtn.setChecked(!cdBtn.isChecked());
                    if(riBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.RI))  riBtn.setChecked(!riBtn.isChecked());
                }
                if (flowControl != UsbSerialPort.FlowControl.NONE) {
                    switch (usbSerialPort.getFlowControl()) {
                        case DTR_DSR:         sendAllowed = usbSerialPort.getDSR(); break;
                        case RTS_CTS:         sendAllowed = usbSerialPort.getCTS(); break;
                        case XON_XOFF:        sendAllowed = usbSerialPort.getXON(); break;
                        case XON_XOFF_INLINE: sendAllowed = flowControlFilter != null && flowControlFilter.getXON(); break;
                        default:              sendAllowed = true;
                    }
                    updateSendBtn(sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
                }
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

    }

}
