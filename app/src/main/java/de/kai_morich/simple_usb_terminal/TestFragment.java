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
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private String fpIndexTestData = "0000B8C6";
    private int dCmTestData = 13;

    private double[] cirRealTestData = {
            -6.0, -10.0, 14.0, -19.0, -23.0, -33.0, 13.0, 11.0, 28.0, 3.0, -5.0, 37.0, 19.0, 40.0, 12.0, 1.0, 15.0, 20.0, 5.0, -25.0, -38.0, -14.0, -5.0, -8.0, -20.0, -6.0, 26.0, 29.0, 62.0, 10.0, -11.0, -2.0, 22.0, -2.0, 7.0, -1.0, 3.0, -36.0, -344.0, -1184.0, -2099.0, -2272.0, -1446.0, 123.0, 877.0, 511.0, 43.0, -129.0, -18.0, 47.0, 21.0, -6.0, 73.0, 110.0, 53.0, 8.0, -5.0, -1.0, 38.0, 99.0, 112.0, 85.0, 14.0, -97.0, -130.0, -89.0, 30.0, 142.0, 180.0, 110.0, 62.0, 3.0, -14.0, -3.0, 16.0, 74.0, 78.0, 63.0, 96.0, 102.0, 111.0, 62.0, 89.0, 38.0, 23.0, 13.0, 2.0, 6.0, 18.0, -14.0, -25.0, -22.0, -6.0, -23.0, -46.0, -24.0, -27.0, 5.0, 4.0, 2.0
    };

    private double[] cirImagTestData = {
            -16.0, -28.0, -19.0, -15.0, -31.0, -23.0, -25.0, 1.0, -24.0, -21.0, -29.0, -10.0, 2.0, 36.0, 16.0, -11.0, -9.0, -4.0, -28.0, 15.0, -35.0, -13.0, 2.0, 4.0, 24.0, 31.0, 5.0, 7.0, -6.0, 3.0, -14.0, -18.0, 1.0, 20.0, 16.0, -24.0, -49.0, -27.0, -40.0, -15.0, 187.0, 530.0, 787.0, 449.0, -171.0, -470.0, -238.0, 29.0, 44.0, -90.0, -167.0, -170.0, -195.0, -136.0, -33.0, 29.0, 77.0, 91.0, 147.0, 114.0, 86.0, 68.0, 52.0, 46.0, 17.0, 62.0, 117.0, 94.0, 5.0, -106.0, -149.0, -98.0, -38.0, -27.0, -40.0, -75.0, -84.0, -29.0, 44.0, 49.0, 12.0, -7.0, -72.0, -33.0, -36.0, -21.0, 30.0, 18.0, -36.0, -59.0, -57.0, -4.0, -16.0, -22.0, -17.0, -12.0, -45.0, -92.0, -72.0, -41.0
    };

    private double firstPathIndexTestData;

    // Thresholds and parameters for peak detection
    private int upsampleFactor = 64;
    private double slopeThreshold = 1;
    private double amplitudeThreshold = 290;
    private int minDistance = 100;

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

        // Compute firstPathIndexTestData using hexToFixedPoint()
        firstPathIndexTestData = hexToFixedPoint(fpIndexTestData);

        buttonRunTests.setOnClickListener(v -> {
            runTests();
        });
    }
    private void runTests() {
        StringBuilder results = new StringBuilder();

        results.append("FPindex: ").append(fpIndexTestData).append("\n");
        results.append("First Path Index: ").append(firstPathIndexTestData).append("\n");
        results.append("D_cm: ").append(dCmTestData).append("\n\n");

        // Step 1: Calculate CIR Magnitude
        double[] cirMagnitude = calculateCirMagnitude(cirRealTestData, cirImagTestData);
        results.append("CIR Magnitude: ").append(Arrays.toString(cirMagnitude)).append("\n\n");

        // Step 2: Upsample the magnitude
        double[] resampledMagnitude = upsample(cirMagnitude, upsampleFactor);
        results.append("Upsampled Magnitude Length: ").append(resampledMagnitude.length).append("\n\n");

        // Step 3: Calculate adjusted index and align
        int adjustedIndex = calculateAdjustedIndex(firstPathIndexTestData, upsampleFactor);
        results.append("Adjusted Index: ").append(adjustedIndex).append("\n\n");

        double[] alignedCIR = alignCirMagnitude(resampledMagnitude, adjustedIndex);
        results.append("Aligned CIR Magnitude Length: ").append(alignedCIR.length).append("\n\n");

        // Step 4: Peak Detection
        List<Integer> peakIndices = detectPeaks(alignedCIR, slopeThreshold, amplitudeThreshold, minDistance);
        results.append("Detected Peaks: ").append(peakIndices.toString()).append("\n\n");

        // Step 5: Feature Extraction
        Map<String, Object> features = extractFeatures(alignedCIR, peakIndices);
        results.append("Extracted Features: ").append(features.toString()).append("\n\n");

        // Display results
        textViewTestResults.setText(results.toString());
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
        return (int) Math.round((firstPathIndex - 721) * upsampleFactor);
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

    private List<Integer> detectPeaks(double[] data, double slopeThreshold, double amplitudeThreshold, int minDistance) {
        double[] firstDerivative = computeGradient(data);
        double[] secondDerivative = computeGradient(firstDerivative);

        List<Integer> potentialMergedPeaks = new ArrayList<>();
        for (int j = 1; j < data.length - 1; j++) {
            boolean slopeCondition = Math.abs(firstDerivative[j]) < slopeThreshold;
            boolean convexityCondition = secondDerivative[j - 1] * secondDerivative[j + 1] < 0;
            boolean amplitudeCondition = data[j] > amplitudeThreshold;

            if (slopeCondition && convexityCondition && amplitudeCondition) {
                potentialMergedPeaks.add(j);
            }
        }

        List<Integer> mergedPeaks = applyMinDistanceCriterion(potentialMergedPeaks, minDistance);
        List<Integer> regularPeaks = findRegularPeaks(data, amplitudeThreshold, minDistance);

        Set<Integer> allPeaksSet = new HashSet<>(mergedPeaks);
        allPeaksSet.addAll(regularPeaks);
        List<Integer> allPeaks = new ArrayList<>(allPeaksSet);
        Collections.sort(allPeaks);

        Log.d("TestFragment", "All Peaks: " + allPeaks.toString());
        return allPeaks;
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

    private List<Integer> applyMinDistanceCriterion(List<Integer> peaks, int minDistance) {
        List<Integer> filteredPeaks = new ArrayList<>();
        int lastPeak = -minDistance;
        for (int peak : peaks) {
            if (peak - lastPeak >= minDistance) {
                filteredPeaks.add(peak);
                lastPeak = peak;
            }
        }
        return filteredPeaks;
    }

    private List<Integer> findRegularPeaks(double[] data, double amplitudeThreshold, int minDistance) {
        List<Integer> peaks = new ArrayList<>();
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] > amplitudeThreshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                if (peaks.isEmpty() || i - peaks.get(peaks.size() - 1) >= minDistance) {
                    peaks.add(i);
                }
            }
        }
        return peaks;
    }

    private Map<String, Object> extractFeatures(double[] alignedCIR, List<Integer> peakIndices) {
        Map<String, Object> features = new HashMap<>();

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
            for (int j = 1; j < Math.min(p, numPeaks); j++) {
                double P_pos_ratio = peakMagnitudes[sortedByPosition[0]] / peakMagnitudes[sortedByPosition[j]];
                P_pos_ratios.add(P_pos_ratio);

                int T_pos_distance = peakIndices.get(sortedByPosition[j]) - peakIndices.get(sortedByPosition[0]);
                T_pos_distances.add(T_pos_distance);
            }

            for (int j = 1; j < Math.min(p, numPeaks); j++) {
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

        features.put("P_pos_ratios", P_pos_ratios);
        features.put("P_power_ratios", P_power_ratios);
        features.put("T_pos_distances", T_pos_distances);
        features.put("T_power_distances", T_power_distances);
        features.put("Pmax", Pmax);
        features.put("Tmax", Tmax);
        features.put("Num_Peaks", numPeaks);

        Log.d("TestFragment", "Extracted Features: " + features.toString());
        return features;
    }

    private int[] sortIndicesByValues(int[] values) {
        Integer[] indices = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, Comparator.comparingInt(i -> values[i]));
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