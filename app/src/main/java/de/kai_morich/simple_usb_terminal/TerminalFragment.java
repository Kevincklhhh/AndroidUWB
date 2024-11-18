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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private Sensor magnetometer;
    private StringBuilder dataBuffer = new StringBuilder();
    private BlockingQueue<Map<String, Object>> cirDataQueue = new LinkedBlockingQueue<>();
    private RandomForestClassifier model;
    private Map<Integer, String> labelMapping;

    private int CIRlength = 70;
    // Thresholds
    private static final float GYROSCOPE_THRESHOLD = 1.2f; // Adjust as needed
    private static final float MAGNETOMETER_THRESHOLD = 30.0f; // Adjust as needed
    private float lastGyroMagnitude = 0.0f;

    // Debounce durations (in milliseconds)
    private static final long ENTER_DEBOUNCE_DURATION = 300;
    private static final long EXIT_DEBOUNCE_DURATION = 1000;

    // Timestamps
    private long gyroThresholdStartTime = 0;
    private long sensorsBelowThresholdStartTime = 0;
    // List to store magnetometer magnitudes during Movement Detection State
    private List<Float> magnetometerMagnitudes = new ArrayList<>();
    private static final float MAGNETOMETER_FLUCTUATION_THRESHOLD = 5.0f; // in µT
    private float lastMagnetometerMagnitude = 0.0f;

    // List to store features of collected CIRs
    private List<Map<String, Double>> collectedFeatures = new ArrayList<>();

    // List to store classification results
    private List<Integer> classificationResults = new ArrayList<>();

    // Number of CIRs to collect
    private static final int NUM_CIRS_TO_COLLECT = 5;

    // Variance thresholds for features (adjust these thresholds based on your data)
    private Map<String, Double> varianceThresholds = new HashMap<>();


    private enum MovementState {
        IDLE,
        UWB_RANGING, MOVEMENT_DETECTION
    }
    private MovementState currentState = MovementState.IDLE;
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

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
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
        if (currentState == MovementState.IDLE) {
            sensorManager.registerListener(sensorEventListener, gyroscope, 100000);
        } else if (currentState == MovementState.MOVEMENT_DETECTION) {
            sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(sensorEventListener, linearAccelerometer, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        }
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
        clearLogFile();
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines.onCreateView(view);
        Button shareLogButton = view.findViewById(R.id.share_log_button);
        shareLogButton.setOnClickListener(v -> shareLogFile());

        sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        } else {
            Toast.makeText(getActivity(), "Sensor Manager not available", Toast.LENGTH_SHORT).show();
        }

        enterIdleState();
        // Initialize and register the sensor event listener
        //initSensorEventListener();

        // Clear the IMU log file when the view is created
        clearIMULogFile();
        return view;
    }
    private SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            long timestamp = System.currentTimeMillis(); // Get current timestamp

            switch (event.sensor.getType()) {
                case Sensor.TYPE_GYROSCOPE:
                    handleGyroscopeData(event.values, timestamp);
                    break;

                case Sensor.TYPE_LINEAR_ACCELERATION:
                    handleAccelerometerData(event.values, timestamp);
                    break;

                case Sensor.TYPE_MAGNETIC_FIELD:
                    handleMagnetometerData(event.values, timestamp);
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Handle changes in sensor accuracy if needed
        }
    };
    private void handleGyroscopeData(float[] values, long timestamp) {
        float gyroMagnitude = (float) Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
        lastGyroMagnitude = gyroMagnitude;
        String data = String.format(
                "GYROSCOPE TIMESTAMP: %d, X: %.9f, Y: %.9f, Z: %.9f\n",
                timestamp, values[0], values[1], values[2]
        );
        logIMUData(data);
        if (currentState == MovementState.IDLE) {
            if (gyroMagnitude > GYROSCOPE_THRESHOLD) {
                    enterMovementDetectionState();
            }
        } else if (currentState == MovementState.MOVEMENT_DETECTION) {
            checkForExitCondition();
        }
    }
    private void handleAccelerometerData(float[] values, long timestamp) {
        String data = String.format(
                "ACCELEROMETER TIMESTAMP: %d, X: %.9f, Y: %.9f, Z: %.9f\n",
                timestamp, values[0], values[1], values[2]
        );
        logIMUData(data);
        if (currentState == MovementState.MOVEMENT_DETECTION) {
            float accelerometerMagnitude = (float) Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
        }
    }
    private void handleMagnetometerData(float[] values, long timestamp) {
        float magnetometerMagnitude = (float) Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );
        lastMagnetometerMagnitude = magnetometerMagnitude;

        // Format and log magnetometer data
        String data = String.format(
                "MAGNETOMETER TIMESTAMP: %d, X: %.9f, Y: %.9f, Z: %.9f\n",
                timestamp, values[0], values[1], values[2]
        );
        logIMUData(data);
        if (currentState == MovementState.MOVEMENT_DETECTION) {
            // Store the magnetometer magnitude
            magnetometerMagnitudes.add(magnetometerMagnitude);
        }
    }
    private void checkForExitCondition() {
        boolean gyroBelowThreshold = lastGyroMagnitude < GYROSCOPE_THRESHOLD;

        if (gyroBelowThreshold) {
            if (sensorsBelowThresholdStartTime == 0) {
                sensorsBelowThresholdStartTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - sensorsBelowThresholdStartTime >= EXIT_DEBOUNCE_DURATION) {
                // Before entering Idle State, check for UWB ranging condition
                checkForUwbRangingCondition();

                // If still in MOVEMENT_DETECTION, transition to Idle
                if (currentState == MovementState.MOVEMENT_DETECTION) {
                    enterIdleState();
                }
                // If transitioned to UWB_RANGING, do not enter Idle State
            }
        } else {
            sensorsBelowThresholdStartTime = 0;
        }
    }
    private void checkForUwbRangingCondition() {
        if (magnetometerMagnitudes.isEmpty()) {
            return;
        }

        // Log original magnetometer magnitudes
        logReceivedData("Magnetometer Magnitudes Before Filter: " + magnetometerMagnitudes.toString()+"\n");

        // Apply a median filter or remove outliers
        List<Float> filteredMagnitudes = filterMagnetometerData(magnetometerMagnitudes);

        // Log filtered magnetometer magnitudes
        logReceivedData("Magnetometer Magnitudes After Filter: " + filteredMagnitudes.toString()+"\n");

        if (filteredMagnitudes.isEmpty()) {
            // No valid data after filtering
            return;
        }

        // Calculate max and min of filtered data
        float maxMagnitude = Collections.max(filteredMagnitudes);
        float minMagnitude = Collections.min(filteredMagnitudes);
        float magnitudeDifference = Math.abs(maxMagnitude - minMagnitude);

        // Check if the magnitude difference exceeds the threshold
        if (magnitudeDifference >= MAGNETOMETER_FLUCTUATION_THRESHOLD) {
            // Log that the condition has been met
            String data = String.format(
                    "CONDITION_MET TIMESTAMP: %d, Ready to enter UWB Ranging State\n",
                    System.currentTimeMillis()
            );
            logIMUData(data);

            // Transition to UWB Ranging State
            enterUwbRangingState();
        }
    }
    private List<Float> filterMagnetometerData(List<Float> data) {
        // Calculate mean and standard deviation
        double sum = 0.0;
        for (float value : data) {
            sum += value;
        }
        double mean = sum / data.size();

        double varianceSum = 0.0;
        for (float value : data) {
            varianceSum += Math.pow(value - mean, 2);
        }
        double standardDeviation = Math.sqrt(varianceSum / data.size());

        // Remove outliers beyond 2 standard deviations
        List<Float> filteredData = new ArrayList<>();
        for (float value : data) {
            if (Math.abs(value - mean) <= 2 * standardDeviation) {
                filteredData.add(value);
            }
        }
        return filteredData;
    }

    private void enterMovementDetectionState() {
        currentState = MovementState.MOVEMENT_DETECTION;
        gyroThresholdStartTime = 0;
        sensorsBelowThresholdStartTime = 0;

        // Clear previous magnetometer data
        magnetometerMagnitudes.clear();

        // Unregister gyroscope at normal delay
        sensorManager.unregisterListener(sensorEventListener, gyroscope);

        // Register sensors at game delay
        sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(sensorEventListener, linearAccelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_GAME);

        // Log state transition
        String data = String.format(
                "STATE_TRANSITION TIMESTAMP: %d, NEW_STATE: MOVEMENT_DETECTION\n",
                System.currentTimeMillis()
        );
        logIMUData(data);

        // Update receiveText on the main thread
        updateReceiveText("Entered Movement Detection State");
    }

    private void enterIdleState() {
        currentState = MovementState.IDLE;
        gyroThresholdStartTime = 0;
        sensorsBelowThresholdStartTime = 0;

        // Unregister all sensors
        sensorManager.unregisterListener(sensorEventListener);

        // Register gyroscope at normal delay
        sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);

        // Clear magnetometer data
        magnetometerMagnitudes.clear();

        // Log state transition
        String data = String.format(
                "STATE_TRANSITION TIMESTAMP: %d, NEW_STATE: IDLE\n",
                System.currentTimeMillis()
        );
        logIMUData(data);

        // Update receiveText on the main thread
        updateReceiveText("Entered Idle State");
    }
    private void enterUwbRangingState() {
        currentState = MovementState.UWB_RANGING;

        // Unregister all sensors
        sensorManager.unregisterListener(sensorEventListener);

        // Log state transition
        String data = String.format(
                "STATE_TRANSITION TIMESTAMP: %d, NEW_STATE: UWB_RANGING\n",
                System.currentTimeMillis()
        );
        logIMUData(data);

        // Update receiveText on the main thread
        updateReceiveText("Entered UWB Ranging State");

        // Send command to UWB board
        send("initf 4 9600");

        // Clear magnetometer data
        magnetometerMagnitudes.clear();
    }
    private void enterIdleStateFromUwb() {
        currentState = MovementState.IDLE;

        // Unregister all sensors
        sensorManager.unregisterListener(sensorEventListener);

        // Register gyroscope at normal delay
        sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);

        // Log state transition
        String data = String.format(
                "STATE_TRANSITION TIMESTAMP: %d, NEW_STATE: IDLE\n",
                System.currentTimeMillis()
        );
        logIMUData(data);

        // Update receiveText on the main thread
        updateReceiveText("Entered Idle State from UWB Ranging");

        // Send command "stop"
        send("stop");


        // Reset debounce timers
        gyroThresholdStartTime = 0;
        sensorsBelowThresholdStartTime = 0;
    }



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
    private void logCIRData(List<Double> cirData) {
        String fileName = "CIR_Log.txt";
        String filePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + fileName;

        StringBuilder sb = new StringBuilder();
        for (Double value : cirData) {
            sb.append(value).append(",");
        }
        sb.append("\n");

        try {
            File file = new File(filePath);
            FileWriter writer = new FileWriter(file, true); // true for append mode
            writer.append(sb.toString());
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();

        for (byte[] data : datas) {
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
                logReceivedData(msg);
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
    private void initializeVarianceThresholds() {
        varianceThresholds.put("Num_Peaks", 0.5);
        varianceThresholds.put("Pmax", 50.0);
        varianceThresholds.put("Tmax", 10.0);
        varianceThresholds.put("P_pos_ratio_1", 0.2);
        varianceThresholds.put("P_power_ratio_1", 0.2);
        varianceThresholds.put("T_pos_distance_1", 5.0);
        varianceThresholds.put("T_power_distance_1", 5.0);
        // Add thresholds for other features as needed
    }

    private void processCirDataAsync(Map<String, Object> cirData) {
        String fpIndex = (String) cirData.get("fpIndex");
        List<Integer> cirRealValues = (List<Integer>) cirData.get("cirRealValues");
        List<Integer> cirImagValues = (List<Integer>) cirData.get("cirImagValues");
        int dCm = (int) cirData.get("dCm");

        // Convert FPindex from hex to fixed-point decimal
        double firstPathIndex = hexToFixedPoint(fpIndex);

        // Convert CIR values to arrays
        double[] cirRealArray = cirRealValues.stream().mapToDouble(Integer::doubleValue).toArray();
        double[] cirImagArray = cirImagValues.stream().mapToDouble(Integer::doubleValue).toArray();

        // Compute CIR magnitude
        double[] cirMagnitude = new double[cirRealArray.length];
        for (int i = 0; i < cirRealArray.length; i++) {
            cirMagnitude[i] = Math.sqrt(cirRealArray[i] * cirRealArray[i] + cirImagArray[i] * cirImagArray[i]);
        }

        // Proceed with upsampling
        double[] upsampledCIR = resampleFFT(cirMagnitude, 64 * CIRlength);

        // Align the upsampled CIR using the first path index
        double[] alignedCIR = alignCir(upsampledCIR, firstPathIndex);

        // Detect peaks in the alignedCIR
        List<Integer> peakIndices = detectPeaks(alignedCIR);

        // Extract features from the aligned CIR and peaks
        Map<String, Double> features = extractFeatures(alignedCIR, peakIndices);

        // Store features
        synchronized (collectedFeatures) {
            collectedFeatures.add(features);
        }

        // Check if we have collected enough CIRs
        if (collectedFeatures.size() >= NUM_CIRS_TO_COLLECT) {
            // Evaluate feature variance
            boolean isStable = evaluateFeatureVariance(collectedFeatures);

            if (isStable) {
                // Proceed with classification and majority voting
                classifyCollectedCIRs();
            } else {
                // Data is unstable, discard and start over
                synchronized (collectedFeatures) {
                    collectedFeatures.clear();
                }
                synchronized (classificationResults) {
                    classificationResults.clear();
                }
                // Optionally log or display a message indicating instability
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    logReceivedData("Data unstable, collecting new CIRs...\n");
                });
            }
        }
    }
    private boolean evaluateFeatureVariance(List<Map<String, Double>> featuresList) {
        Map<String, List<Double>> featureValuesMap = new HashMap<>();

        // Collect all values for each feature
        for (Map<String, Double> features : featuresList) {
            for (String featureName : features.keySet()) {
                if (!featureValuesMap.containsKey(featureName)) {
                    featureValuesMap.put(featureName, new ArrayList<>());
                }
                featureValuesMap.get(featureName).add(features.get(featureName));
            }
        }

        // Calculate variance for each feature
        for (String featureName : featureValuesMap.keySet()) {
            List<Double> values = featureValuesMap.get(featureName);
            double variance = calculateVariance(values);

            // Compare with threshold
            double threshold = varianceThresholds.getOrDefault(featureName, Double.MAX_VALUE);
            if (variance > threshold) {
                Log.d("FeatureVariance", "Feature " + featureName + " variance " + variance + " exceeds threshold " + threshold);
                return false; // Data is unstable
            }
        }

        return true; // Data is stable
    }

    private double calculateVariance(List<Double> values) {
        int n = values.size();
        if (n == 0) return 0.0;

        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= n;

        double variance = 0.0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        variance /= n;

        return variance;
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

        // Get the class name
        String className = labelMapping.get(finalPrediction);

        // Transition back to Idle State and handle the result
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            logReceivedData("Final Classification Result: " + className + "\n");
            // Optionally display the classification result
            // displayClassificationResult(className);

            if (currentState == MovementState.UWB_RANGING) {
                enterIdleStateFromUwb();
            }
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
    private List<Integer> detectPeaks(double[] data) {
        double slopeThreshold = 1.0;
        double amplitudeThreshold = 290.0;
        int minDistance = 100;

        double[] firstDerivative = computeGradient(data);
        double[] secondDerivative = computeGradient(firstDerivative);

        // Identify potential merged peaks
        List<Integer> potentialMergedPeaks = new ArrayList<>();
        for (int j = 1; j < data.length - 1; j++) {
            boolean slopeCondition = Math.abs(firstDerivative[j]) < slopeThreshold;
            boolean convexityCondition = secondDerivative[j - 1] * secondDerivative[j + 1] < 0;
            boolean amplitudeCondition = data[j] > amplitudeThreshold;

            if (slopeCondition && convexityCondition && amplitudeCondition) {
                potentialMergedPeaks.add(j);
            }
        }

        // Detect regular peaks without applying minDistance
        List<Integer> regularPeaks = findRegularPeaksWithoutMinDistance(data, amplitudeThreshold);

        // Combine all peaks
        Set<Integer> allPeaksSet = new HashSet<>(potentialMergedPeaks);
        allPeaksSet.addAll(regularPeaks);
        List<Integer> allPeaks = new ArrayList<>(allPeaksSet);

        // Apply minimum distance criterion to all peaks, prioritizing higher amplitude peaks
        List<Integer> filteredPeaks = applyMinDistanceCriterionToAllPeaks(allPeaks, data, minDistance);

        return filteredPeaks;
    }

    private double[] computeGradient(double[] data) {
        double[] gradient = new double[data.length];
        gradient[0] = data[1] - data[0];
        for (int i = 1; i < data.length - 1; i++) {
            gradient[i] = (data[i + 1] - data[i - 1]) / 2.0;
        }
        gradient[data.length - 1] = data[data.length - 1] - data[data.length - 2];
        return gradient;
    }

    private List<Integer> findRegularPeaksWithoutMinDistance(double[] data, double amplitudeThreshold) {
        List<Integer> peaks = new ArrayList<>();
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] > amplitudeThreshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                peaks.add(i);
            }
        }
        return peaks;
    }

    private List<Integer> applyMinDistanceCriterionToAllPeaks(List<Integer> peaks, double[] data, int minDistance) {
        // Sort peaks by amplitude in descending order
        peaks.sort((p1, p2) -> Double.compare(data[p2], data[p1]));

        List<Integer> filteredPeaks = new ArrayList<>();
        boolean[] removed = new boolean[data.length];

        for (int peak : peaks) {
            if (!removed[peak]) {
                filteredPeaks.add(peak);
                // Mark peaks within minDistance as removed
                int start = Math.max(peak - minDistance, 0);
                int end = Math.min(peak + minDistance, data.length - 1);
                for (int i = start; i <= end; i++) {
                    removed[i] = true;
                }
                removed[peak] = false; // Keep the current peak
            }
        }

        // Sort the filtered peaks by their original indices
        filteredPeaks.sort(Integer::compareTo);

        return filteredPeaks;
    }

    private Map<String, Double> extractFeatures(double[] alignedCIR, List<Integer> peakIndices) {
        Map<String, Double> features = new HashMap<>();

        double[] peakMagnitudes = new double[peakIndices.size()];
        for (int i = 0; i < peakIndices.size(); i++) {
            peakMagnitudes[i] = alignedCIR[peakIndices.get(i)];
        }

        int[] sortedByPosition = sortIndicesByValues(peakIndices.stream().mapToInt(Integer::intValue).toArray());
        int[] sortedByMagnitude = sortIndicesByValuesDescending(peakMagnitudes);

        int numPeaks = peakIndices.size();
        int p = 4;

        List<Double> P_pos_ratios = new ArrayList<>();
        List<Double> P_power_ratios = new ArrayList<>();
        List<Integer> T_pos_distances = new ArrayList<>();
        List<Integer> T_power_distances = new ArrayList<>();

        if (numPeaks > 1) {
            int numRatios = Math.min(p - 1, numPeaks - 1);

            // Calculate position-based ratios and distances
            for (int j = 1; j <= numRatios; j++) {
                double P_pos_ratio = peakMagnitudes[sortedByPosition[0]] / peakMagnitudes[sortedByPosition[j]];
                P_pos_ratios.add(P_pos_ratio);

                int T_pos_distance = peakIndices.get(sortedByPosition[j]) - peakIndices.get(sortedByPosition[0]);
                T_pos_distances.add(T_pos_distance);
            }

            // Calculate magnitude-based ratios and distances
            for (int j = 1; j <= numRatios; j++) {
                double P_power_ratio = peakMagnitudes[sortedByMagnitude[0]] / peakMagnitudes[sortedByMagnitude[j]];
                P_power_ratios.add(P_power_ratio);

                int T_power_distance = peakIndices.get(sortedByMagnitude[j]) - peakIndices.get(sortedByMagnitude[0]);
                T_power_distances.add(T_power_distance);
            }
        } else {
            P_pos_ratios.add(1.0);
            P_power_ratios.add(1.0);
            T_pos_distances.add(0);
            T_power_distances.add(0);
        }

        double Pmax = numPeaks > 0 ? peakMagnitudes[sortedByMagnitude[0]] : 0;
        int Tmax = numPeaks > 0 ? peakIndices.get(sortedByMagnitude[0]) : 0;

        // Ensure the lists have exactly 3 elements
        while (P_pos_ratios.size() < 3) P_pos_ratios.add(1.0);
        while (P_power_ratios.size() < 3) P_power_ratios.add(1.0);
        while (T_pos_distances.size() < 3) T_pos_distances.add(0);
        while (T_power_distances.size() < 3) T_power_distances.add(0);

        // Store features in the specified order
        features.put("Num_Peaks", (double) numPeaks);
        features.put("Pmax", Pmax);
        features.put("Tmax", (double) Tmax);

        features.put("P_pos_ratio_1", P_pos_ratios.get(0));
        features.put("P_power_ratio_1", P_power_ratios.get(0));
        features.put("T_pos_distance_1", (double) T_pos_distances.get(0));
        features.put("T_power_distance_1", (double) T_power_distances.get(0));

        features.put("P_pos_ratio_2", P_pos_ratios.get(1));
        features.put("P_power_ratio_2", P_power_ratios.get(1));
        features.put("T_pos_distance_2", (double) T_pos_distances.get(1));
        features.put("T_power_distance_2", (double) T_power_distances.get(1));

        features.put("P_pos_ratio_3", P_pos_ratios.get(2));
        features.put("P_power_ratio_3", P_power_ratios.get(2));
        features.put("T_pos_distance_3", (double) T_pos_distances.get(2));
        features.put("T_power_distance_3", (double) T_power_distances.get(2));

        return features;
    }

    private int argMax(double[] inputs) {
        int maxIndex = 0;
        double maxValue = inputs[0];
        for (int i = 1; i < inputs.length; i++) {
            if (inputs[i] > maxValue) {
                maxValue = inputs[i];
                maxIndex = i;
            }
        }
        return maxIndex;
    }
//    private void displayClassificationResult(String className) {
//        // Update your UI elements here
//        // For example, display the class name in a TextView
//        TextView resultTextView = getView().findViewById(R.id.resultTextView);
//        resultTextView.setText("Predicted Class: " + className);
//    }


    private int[] sortIndicesByValues(int[] values) {
        Integer[] indices = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices,Comparator.comparingInt(i -> values[i]));
        return Arrays.stream(indices).mapToInt(Integer::intValue).toArray();
    }

    private int[] sortIndicesByValuesDescending(double[] values) {
        Integer[] indices = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, (i1, i2) -> Double.compare(values[i2], values[i1]));
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
