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
import org.jtransforms.fft.DoubleFFT_1D;

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
    String fpIndexTestData = "0000B9E7";
    long timestampTestData = 1729703683716L;
    int dCmTestData = 37;
    double[] cirRealTestData = { 6d, 22d, -11d, -7d, 3d, 2d, 15d, 8d, -40d, -36d, -36d, 5d, 15d, 24d, 8d, -26d, -36d, -7d, -12d, -2d, -2d, -13d, -37d, -73d, -45d, -14d, 22d, 36d, 6d, -34d, -58d, -5d, 0d, 24d, 21d, 15d, 41d, -33d, -28d, -1d, 20d, -14d, -103d, -506d, -1198d, -1712d, -1487d, -769d, -270d, -299d, -563d, -585d, -196d, 95d, 163d, 23d, -54d, -37d, 64d, 132d, 80d, -56d, -98d, -66d, 10d, 49d, 117d, 187d, 157d, 91d, 61d, 65d, 92d, 67d, 13d, 21d, 52d, 33d, 17d, 34d, 83d, 52d, -30d, -23d, -27d, -5d, 12d, 6d, 2d, -35d, -9d, 32d, 28d, 30d, 7d, 37d, 4d, -3d, 17d, 58d };
    double[] cirImagTestData = { 26d, -6d, 10d, -9d, -5d, -9d, 34d, 46d, 5d, 35d, 9d, -23d, -47d, 70d, 98d, 70d, 27d, 29d, 16d, -13d, 34d, -12d, 3d, 28d, 18d, 50d, 21d, -5d, -16d, -23d, -28d, -60d, -5d, 16d, 9d, -11d, -30d, -13d, -2d, -2d, -3d, 4d, 64d, 312d, 668d, 748d, 296d, -432d, -752d, -519d, -67d, 478d, 805d, 745d, 441d, -84d, -380d, -387d, -238d, -124d, -86d, -62d, 19d, 48d, 45d, 15d, 31d, -11d, -96d, -160d, -33d, 8d, 42d, 45d, 40d, 40d, 34d, 13d, -10d, -48d, -27d, -1d, 22d, 23d, 42d, 88d, 53d, 13d, 23d, 34d, 0d, -16d, -6d, 23d, 13d, -14d, -18d, 18d, -2d, 30d };

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
    public static double[] trimArray(double[] array, int start, int end) {
        if (array.length <= (start + end)) {
            return new double[0]; // Return an empty array if there aren't enough elements
        }

        int newLength = array.length - start - end;
        double[] trimmedArray = new double[newLength];

        System.arraycopy(array, start, trimmedArray, 0, newLength);

        return trimmedArray;
    }
    private void runTests() {
        StringBuilder results = new StringBuilder();

        long startTime = System.nanoTime();
        long endTime = System.nanoTime();

        results.append("FPindex: ").append(fpIndexTestData).append("\n");
        results.append("First Path Index: ").append(firstPathIndexTestData).append("\n");
        results.append("D_cm: ").append(dCmTestData).append("\n\n");
        cirRealTestData = trimArray(cirRealTestData, 10, 20);
        cirImagTestData = trimArray(cirImagTestData, 10, 20);

        startTime = System.nanoTime();
        // Step 1: Calculate CIR Magnitude
        double[] cirMagnitude = calculateCirMagnitude(cirRealTestData, cirImagTestData);
        results.append("CIR Magnitude: ").append(Arrays.toString(cirMagnitude)).append("\n\n");
        endTime = System.nanoTime();
        Log.d("Performance", "CIR Magnitude Calculation Time: " + (endTime - startTime) + " ns");


        // Step 2: Upsample the magnitude
        startTime = System.nanoTime();
        double[] resampledMagnitude = resampleFFT(cirMagnitude, upsampleFactor * 70 );
        //double[] resampledMagnitude = upsample(cirMagnitude, 64);
        results.append("Upsampled Magnitude Length: ").append(resampledMagnitude.length).append("\n\n");
        endTime = System.nanoTime();
        Log.d("Performance", "Upsampling Time: " + (endTime - startTime) + " ns");


        // Step 3: Calculate adjusted index and align
        startTime = System.nanoTime();
        int adjustedIndex = calculateAdjustedIndex(firstPathIndexTestData, upsampleFactor);
        results.append("Adjusted Index: ").append(adjustedIndex).append("\n\n");
        double[] alignedCIR = alignCirMagnitude(resampledMagnitude, adjustedIndex);
        results.append("Aligned CIR Magnitude Length: ").append(alignedCIR.length).append("\n\n");
        endTime = System.nanoTime();
        Log.d("Performance", "Alignment Time: " + (endTime - startTime) + " ns");

        // Step 4: Peak Detection
        startTime = System.nanoTime();
        List<Integer> peakIndices = detectPeaks(alignedCIR, slopeThreshold, amplitudeThreshold, minDistance);
        results.append("Detected Peaks: ").append(peakIndices.toString()).append("\n\n");
        endTime = System.nanoTime();
        Log.d("Performance", "Peak Detection Time: " + (endTime - startTime) + " ns");

        // Step 5: Feature Extraction
        startTime = System.nanoTime();
        Map<String, Object> features = extractFeatures(alignedCIR, peakIndices);
        results.append("Extracted Features: ").append(features.toString()).append("\n\n");
        endTime = System.nanoTime();
        Log.d("Performance", "Feature Extraction Time: " + (endTime - startTime) + " ns");

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

    private List<Integer> detectPeaks(double[] data, double slopeThreshold, double amplitudeThreshold, int minDistance) {
        double[] firstDerivative = computeGradient(data);
        double[] secondDerivative = computeGradient(firstDerivative);

        // Step 1: Detect potential merged peaks without applying minDistance
        List<Integer> potentialMergedPeaks = new ArrayList<>();
        for (int j = 1; j < data.length - 1; j++) {
            boolean slopeCondition = Math.abs(firstDerivative[j]) < slopeThreshold;
            boolean convexityCondition = secondDerivative[j - 1] * secondDerivative[j + 1] < 0;
            boolean amplitudeCondition = data[j] > amplitudeThreshold;

            if (slopeCondition && convexityCondition && amplitudeCondition) {
                potentialMergedPeaks.add(j);
            }
        }

        // Step 2: Detect regular peaks without applying minDistance
        List<Integer> regularPeaks = findRegularPeaksWithoutMinDistance(data, amplitudeThreshold);

        // Step 3: Combine all peaks into a single list
        Set<Integer> allPeaksSet = new HashSet<>(potentialMergedPeaks);
        allPeaksSet.addAll(regularPeaks);
        List<Integer> allPeaks = new ArrayList<>(allPeaksSet);

        // Step 4: Apply minDistance criterion to all peaks, prioritizing higher amplitude peaks
        List<Integer> filteredPeaks = applyMinDistanceCriterionToAllPeaks(allPeaks, data, minDistance);

        Log.d("TestFragment", "Filtered Peaks: " + filteredPeaks.toString());
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


    private List<Integer> findRegularPeaksWithoutMinDistance(double[] data, double amplitudeThreshold) {
        List<Integer> peaks = new ArrayList<>();
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] > amplitudeThreshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                peaks.add(i);
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