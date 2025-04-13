package de.kai_morich.simple_usb_terminal;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jtransforms.fft.DoubleFFT_1D;
import de.kai_morich.simple_usb_terminal.RandomForestClassifier;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link TestFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class TestFragment extends Fragment {

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    private Button buttonRunTests;
    private TextView textViewTestResults;

    // Hardcoded test data based on the provided data


    private double firstPathIndexTestData;

    // Thresholds and parameters for peak detection
    private int upsampleFactor = 64;
    private double slopeThreshold = 1;
    private double amplitudeThreshold = 290;
    private int minDistance = 100;
    private Map<Integer, String> labelMapping;
    private RandomForestClassifier model;

    public TestFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment TestFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static TestFragment newInstance(String param1, String param2) {
        TestFragment fragment = new TestFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
        //labelMapping = loadLabelMapping(getApplicationContext());

        // Initialize the model
        model = new RandomForestClassifier();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_test, container, false);
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        buttonRunTests = view.findViewById(R.id.button_run_tests);
        textViewTestResults = view.findViewById(R.id.textview_test_results);


        buttonRunTests.setOnClickListener(v -> {
            runRealTimeTestWithFiveCIRs();
        });
    }

    private void runRealTimeTestWithFiveCIRs() {
        // 1) Hardcoded test data: five separate CIR frames
        //    Each entry includes: fpIndex (hex), real[], imag[], distance (dCm)
        //    You can expand or modify these arrays to suit your tests.
        List<Map<String, Object>> testCIRFrames = new ArrayList<>();

        // Where testCIRFrames is a List<Map<String, Object>>, for example:
// private List<Map<String, Object>> testCIRFrames = new ArrayList<>();
//
// The createTestCIR signature might be:
// private Map<String, Object> createTestCIR(String fpIndex, int[] cirRealValues, int[] cirImagValues, int dCm)

        testCIRFrames.add(createTestCIR(
                "0000B8E9",
                new int[]{-9, 6, 2, 28, 46, 49, 6, 22, 18, -42, -24, 10, -4, 7, 3, -20, 8, -16, 12, 19, 29, 53, 108, 56, 3, 6, 20, 21, 11, -15, -131, -255, -205, 10, 28, -439, -1207, -1480, -1004, -300, -126, -579, -844, -525, 70, 546, 628, 500, 307, 383, 340, 123, -43, -187, -211, -218, -153, -39, -6, -69, -170, -187, -184, -115, -67, 2, 24, 16, -5, -23},
                new int[]{2, 34, 29, 21, -6, -17, -16, 19, -20, -38, -36, -30, -13, 20, 22, -16, -2, 10, -1, 4, -4, 28, -35, -36, 1, 7, -9, -12, -17, 123, 348, 467, 61, -316, 162, 1004, 706, -896, -2071, -1447, -178, 720, 1222, 1114, 581, 243, 164, 305, 124, -255, -471, -349, -169, -157, -158, -131, -76, -122, -155, -105, 12, 108, 106, 27, -31, -45, 14, 29, 31, -12},
                32
        ));

        testCIRFrames.add(createTestCIR(
                "0000B875",
                new int[]{35, -17, 39, 8, -2, 54, 10, -41, -21, -24, -22, 22, 60, 6, 8, 24, 71, 32, 62, -12, -48, -47, -3, 18, -64, -11, -20, 159, 358, 546, 37, -256, 361, 1149, 460, -1687, -2517, -1555, -142, 860, 1366, 1100, 484, 214, 158, 373, 88, -338, -470, -391, -183, -219, -200, -235, -69, -112, -157, -98, 32, 27, 14, 25, -33, -50, 38, 49, 39, 41, 41, 84},
                new int[]{38, 11, -2, -7, 28, 15, 2, -37, -43, -19, 32, 3, 42, 53, 13, 19, 23, 0, -27, -51, -74, -53, 2, 17, 5, -7, -1, 69, 226, 290, 124, -135, 59, 726, 1765, 1703, 714, 95, 87, 835, 1160, 689, -81, -492, -610, -450, -184, -414, -393, -189, 35, 136, 190, 232, 92, 15, -14, 24, 187, 207, 235, 149, 32, -34, -29, -23, 18, 28, -39, -93},
                31
        ));

        testCIRFrames.add(createTestCIR(
                "0000BAD5",
                new int[]{-11, -14, 70, 26, 53, 20, 9, 0, 29, -8, 23, 17, 16, 24, 17, 39, -25, -26, 27, 46, 31, 35, 4, 16, -3, 41, 32, 35, 44, 11, 16, 23, 14, 18, -12, -45, 21, 157, 425, 475, 46, -264, 210, 1002, 671, -897, -1955, -1322, -129, 733, 1220, 1076, 591, 236, 140, 283, 95, -268, -478, -359, -165, -176, -194, -150, -77, -131, -139, -70, 42, 106},
                new int[]{-9, 7, 27, -29, 8, 43, 51, 46, 12, 2, 26, 0, -53, -15, 50, 11, 14, -10, 13, -4, 28, 23, 37, 21, 16, -16, -10, 20, 26, -40, -21, -17, -10, -7, 20, 5, 0, 53, 139, 236, 202, -24, 9, 442, 1057, 1432, 949, 309, 179, 543, 804, 490, -77, -579, -575, -466, -277, -365, -300, -122, 11, 151, 194, 210, 124, 31, 1, 77, 168, 198},
                34
        ));

        testCIRFrames.add(createTestCIR(
                "0000B98D",
                new int[]{9, -31, 35, -18, -32, -11, -9, -12, 27, 16, 10, -18, -15, -24, -36, -9, 1, -31, 9, -4, -28, -42, -13, 39, 27, 37, -1, 15, 18, 56, 5, -38, -178, -460, -439, -68, 209, -419, -1115, -765, 535, 1295, 653, -172, -1099, -1323, -905, -148, 136, 128, 6, 126, 485, 521, 282, 76, 49, 11, 0, 28, 78, 97, -43, -107, -175, -123, -35, 11, 25, -12},
                new int[]{1, -43, -34, -13, -26, -21, -21, 8, 5, 42, 26, 4, -5, -27, -11, 14, 19, 10, 16, -14, -4, -21, -5, 36, 36, -2, 14, -9, -2, 5, -13, -8, 70, 104, 3, -161, -33, 202, -61, -972, -1809, -1603, -620, -139, -134, -70, 165, 432, 564, 614, 473, 257, 109, -17, -97, -147, -257, -305, -239, -145, -85, -90, -93, -95, -63, -67, -120, -54, -29, 50},
                36
        ));

        testCIRFrames.add(createTestCIR(
                "0000B9C6",
                new int[]{3, -34, -6, 28, -45, -44, -27, -22, -5, -22, 1, -47, 17, 20, 4, -3, -34, -64, -38, 23, -30, -35, -21, -40, -26, 26, 41, 1, 22, 29, 41, 24, -60, -257, -544, -289, 87, 97, -824, -1340, 115, 2061, 2284, 566, -329, -1471, -1524, -888, -282, 5, -157, -154, 155, 556, 559, 277, 179, 147, 213, 115, 47, 95, 164, 22, -78, -106, -62, 13, 41, 43},
                new int[]{-37, -45, -67, -10, -12, -13, -12, 15, 11, -9, -41, -7, 18, 9, -17, 37, -27, -29, -44, -48, -12, 24, -11, -12, 66, 34, 20, 14, 25, -6, 19, -31, -65, -103, -206, -197, -40, 107, -151, -934, -1880, -1696, -823, -104, -293, -881, -715, -254, 463, 583, 613, 338, 256, 348, 215, 1, -137, -168, -249, -197, -71, -38, -24, -146, -242, -209, -167, -75, 9, 57},
                31
        ));


        // 2) Prepare logging of results
        // 2) Prepare logging of results
        StringBuilder results = new StringBuilder();

// We can reuse the same UnboundedPeakTracker as if processing frames in real-time
        TerminalFragment.UnboundedPeakTracker realTimeTracker = new TerminalFragment.UnboundedPeakTracker(
                90,   // tolerance
                5,     // maxUnmatchedFrames
                4,     // top-n to check, for example
                true   // debug
        );

// 3) Process each of the five CIR frames sequentially
        int upsampleFactor = 64;
        int minDistance = 90;
        double amplitudeThreshold = 220.0;
        int frameIndex = 0;

        for (Map<String, Object> cirMap : testCIRFrames) {
            long frameStart = System.nanoTime();

            // =============== Step A: PARSE RAW DATA ===============
            String fpIndex = (String) cirMap.get("fpIndex");
            int[] cirRealValues = (int[]) cirMap.get("cirRealValues");
            int[] cirImagValues = (int[]) cirMap.get("cirImagValues");
            int dCm = (int) cirMap.get("dCm");

            // Convert fpIndex to decimal
            double firstPathIndex = hexToFixedPoint(fpIndex);

            double[] cirRealArray = new double[cirRealValues.length];
            double[] cirImagArray = new double[cirImagValues.length];
            for (int i = 0; i < cirRealValues.length; i++) {
                cirRealArray[i] = cirRealValues[i];
                cirImagArray[i] = cirImagValues[i];
            }

            // Compute magnitude
            double[] cirMagnitude = new double[cirRealArray.length];
            for (int i = 0; i < cirRealArray.length; i++) {
                cirMagnitude[i] = Math.sqrt(
                        cirRealArray[i]*cirRealArray[i] + cirImagArray[i]*cirImagArray[i]
                );
            }

            // =============== Step B: UPSAMPLE & ALIGN ===============
            long t1 = System.nanoTime();
            double[] upsampledCIR = resampleFFT(cirMagnitude, upsampleFactor * cirMagnitude.length);
            double[] alignedCIR = alignCir(upsampledCIR, firstPathIndex);
            long t2 = System.nanoTime();
            double upsampleAlignTimeMs = (t2 - t1) / 1.0e6;

            // =============== Step C: PEAK DETECTION & TRACKING ===============
            t1 = System.nanoTime();
            List<Integer> framePeaks = detectPeaksLocalMax(
                    alignedCIR,
                    amplitudeThreshold,
                    minDistance,
                    false // debug
            );

            // The new 'update(...)' returns a list of stable peaks directly
            TerminalFragment.UnboundedPeakTracker.UpdateResult updateResult = realTimeTracker.update(framePeaks);
            List<Integer> stablePeaks = updateResult.getFinalIndices();
            boolean stable = updateResult.isStable();

            t2 = System.nanoTime();
            double peakDetectionTimeMs = (t2 - t1) / 1.0e6;

            // =============== Step D: FEATURE EXTRACTION ===============
            t1 = System.nanoTime();

            // Build features from the stable peak indices
            Map<String, Double> featureMap = buildFeaturesFromStablePeaks(
                    stablePeaks,
                    alignedCIR,
                    (double) dCm  // e.g. store distance as "DistanceBin"
            );

            t2 = System.nanoTime();
            double featureExtractionTimeMs = (t2 - t1) / 1.0e6;

            long frameEnd = System.nanoTime();
            double totalFrameTimeMs = (frameEnd - frameStart) / 1.0e6;

            // 4) Log or store results
            results.append("\n===== FRAME ").append(frameIndex).append(" =====\n");
            results.append("FPIndex(hex) = ").append(fpIndex)
                    .append(", Dist=").append(dCm)
                    .append("\n   Upsample+Align: ").append(String.format("%.3f ms", upsampleAlignTimeMs))
                    .append("\n   Detect+Track:   ").append(String.format("%.3f ms", peakDetectionTimeMs))
                    .append("\n   FeatExtract:    ").append(String.format("%.3f ms", featureExtractionTimeMs))
                    .append("\n   TOTAL Frame:    ").append(String.format("%.3f ms", totalFrameTimeMs))
                    .append("\n   framePeaks: ").append(framePeaks)
                    .append("\n   stablePeaks: ").append(stablePeaks)
                    .append("\n   FeatureMap: ").append(featureMap.toString())
                    .append("\n");

            frameIndex++;
        }

// Finally, present all results in textView or logs
        textViewTestResults.setText(results.toString());

    }

    /**
     * Helper function to build each test frame's data in the same shape:
     */
    private Map<String, Object> createTestCIR(String fpIndex, int[] realArr, int[] imagArr, int dCm) {
        Map<String, Object> cirMap = new HashMap<>();
        cirMap.put("fpIndex", fpIndex);
        cirMap.put("cirRealValues", realArr);
        cirMap.put("cirImagValues", imagArr);
        cirMap.put("dCm", dCm);
        return cirMap;
    }

    /**
     * Simplified local maxima detection from prior snippet:
     */
    private List<Integer> detectPeaksLocalMax(
            double[] data,
            double amplitudeThreshold,
            int minDistance,
            boolean debug
    ) {
        // Step A: local maxima above amplitudeThreshold
        List<Integer> rawPeaks = new ArrayList<>();
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] > amplitudeThreshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                rawPeaks.add(i);
            }
        }

        // Step B: sort raw peaks by amplitude (descending)
        rawPeaks.sort((p1, p2) -> Double.compare(data[p2], data[p1]));

        // Step C: minDistance filter, picking largest amplitude first
        List<Integer> filtered = new ArrayList<>();
        boolean[] removed = new boolean[data.length];
        for (int peakIdx : rawPeaks) {
            if (!removed[peakIdx]) {
                filtered.add(peakIdx);
                int start = Math.max(peakIdx - minDistance, 0);
                int end = Math.min(peakIdx + minDistance, data.length - 1);
                for (int j = start; j <= end; j++) {
                    removed[j] = true;
                }
                // The code sets removed[peakIdx] = false, meaning "don't remove the peak itself"
                removed[peakIdx] = false;
            }
        }

        // Step D: sort final by ascending index
        filtered.sort(Integer::compareTo);

        if (debug) {
            Log.d("detectPeaksLocalMax", String.format(
                    "rawPeaks=%d, finalPeaks=%d", rawPeaks.size(), filtered.size()));
        }
        return filtered;
    }

    private double[] alignCir(double[] resampledMagnitude, double firstPathIndex) {
        int upsampleFactor = 64;
        int adjustedIndex = (int) Math.round((firstPathIndex - 801 + 70) * upsampleFactor);

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
// The rest of the methods (hexToFixedPoint, alignCir, resampleFFT, buildFeaturesFromTracker, etc.)
// remain as previously defined or updated in your code.
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
    private double[] calculateCirMagnitude(double[] cirReal, double[] cirImag) {
        int length = cirReal.length;
        double[] cirMagnitude = new double[length];
        for (int i = 0; i < length; i++) {
            cirMagnitude[i] = Math.sqrt(cirReal[i] * cirReal[i] + cirImag[i] * cirImag[i]);
        }
        Log.d("TestFragment", "CIR Magnitude: " + Arrays.toString(cirMagnitude));
        return cirMagnitude;
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

    private double[] upsample(double[] data, int upsampleFactor) {
        int originalLength = data.length;
        int upsampledLength = originalLength * upsampleFactor;
        double[] upsampledData = new double[upsampledLength];

        for (int i = 0; i < originalLength - 1; i++) {
            for (int j = 0; j < upsampleFactor; j++) {
                double fraction = (double) j / upsampleFactor;
                upsampledData[i * upsampleFactor + j] = data[i] + fraction * (data[i + 1] - data[i]);
            }
        }
        upsampledData[upsampledLength - 1] = data[originalLength - 1];

        Log.d("TestFragment", "Upsampled Data: " + Arrays.toString(upsampledData));
        return upsampledData;
    }

    private int calculateAdjustedIndex(double firstPathIndex, int upsampleFactor) {
        return (int) Math.round((firstPathIndex - 731) * upsampleFactor);
    }

    private double[] alignCirMagnitude(double[] resampledMagnitude, int adjustedIndex) {
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
        Log.d("TestFragment", "Aligned CIR Magnitude: " + Arrays.toString(alignedCIR));
        return alignedCIR;
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


}