package com.example.nfc;

import io.flutter.embedding.android.FlutterActivity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import net.sf.scuba.smartcards.CardFileInputStream;
import net.sf.scuba.smartcards.CardService;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardSecurityFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SecurityInfo;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.DG11File;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.iso19794.FaceImageInfo;
import org.jmrtd.lds.iso19794.FaceInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MainActivity extends FlutterActivity implements MethodChannel.MethodCallHandler {

    private static final String CHANNEL = "com.example.nfcreaderapp/passport_reader";

    // OCR related fields
    private static final Pattern line1Pattern = Pattern.compile("([A|C|I][A-Z0-9<]{1})([A-Z]{3})([A-Z0-9<]{9})([0-9]{1})([A-Z0-9<]{15})");
    private static final Pattern line2Pattern = Pattern.compile("([0-9]{6})([0-9]{1})([M|F|X|<]{1})([0-9]{6})([0-9]{1})([A-Z]{3})([A-Z0-9<]{11})([0-9]{1})");
    private static final String TAG = "PassportReaderNative"; // More descriptive tag

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private TextRecognizer textRecognizer;
    private ExecutorService cameraExecutor;
    private ImageAnalysis imageAnalysis; // Keep a reference to unbind
    private ProcessCameraProvider cameraProvider; // Keep a reference to unbind

    private MethodChannel.Result ocrResultCallback; // Stores result object for OCR
    private MethodChannel.Result nfcResultCallback; // Stores result object for NFC

    // NFC related fields
    private NfcAdapter nfcAdapter;
    private final ExecutorService nfcExecutor = Executors.newSingleThreadExecutor();

    // You won't directly use PreviewView or TextView in FlutterActivity anymore for UI
    // private PreviewView viewFinder;
    // private TextView ocrResultTextView;
    // private Button startNfcButton;

    // Permissions
    private final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA, Manifest.permission.NFC};
    private static final int REQUEST_CODE_PERMISSIONS = 10; // Can be triggered from Flutter too


    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL)
                .setMethodCallHandler(this);
    }


    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "startMRZScan":
                // Check camera permission
                if (!allPermissionsGranted()) {
                    // Request permissions if not granted. Flutter should handle this first, but double check.
                    // If permissions are not granted, we can't start camera.
                    result.error("CAMERA_PERMISSION_DENIED", "Camera permission is required to scan MRZ.", null);
                    return;
                }
                if (ocrResultCallback != null) {
                    result.error("SCAN_IN_PROGRESS", "MRZ scan is already in progress.", null);
                    return;
                }
                this.ocrResultCallback = result; // Store callback
                startCameraForMRZ();
                break;
            case "stopMRZScan":
                stopCameraForMRZ();
                result.success(true); // Always succeed stopping (for now)
                break;
            case "readNfc":
                // Check NFC permission
                if (!allPermissionsGranted()) {
                    result.error("NFC_PERMISSION_DENIED", "NFC permission is required to read card.", null);
                    return;
                }
                if (nfcResultCallback != null) {
                    result.error("NFC_SCAN_IN_PROGRESS", "NFC scan is already in progress.", null);
                    return;
                }
                this.nfcResultCallback = result; // Store callback
                String documentNumber = call.argument("documentNumber");
                String dateOfBirth = call.argument("dateOfBirth");
                String expirationDate = call.argument("expirationDate");

                if (nfcAdapter == null) {
                    nfcAdapter = NfcAdapter.getDefaultAdapter(this);
                    if (nfcAdapter == null) {
                        nfcResultCallback.error("NFC_UNAVAILABLE", "NFC is not available on this device.", null);
                        nfcResultCallback = null; // Clear callback
                        return;
                    }
                }
                // Inform Flutter that it's waiting for a tap
                Toast.makeText(this, "Please tap your ID card to the NFC reader...", Toast.LENGTH_LONG).show();
                // onNewIntent will handle the actual tag discovery
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView(R.layout.activity_main) here anymore, Flutter manages the layout.

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraExecutor = Executors.newSingleThreadExecutor();

        nfcAdapter = NfcAdapter.getDefaultAdapter(this); // Initialize NFC adapter early
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            setupNfcForegroundDispatch(this, nfcAdapter);
        }
        // If camera was active, rebind it (though typically Flutter manages camera lifecycle)
        // This is important if you navigate away from app and come back.
        // For simplicity, we'll rely on startMRZScan to initiate.
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            stopNfcForegroundDispatch(this, nfcAdapter);
        }
        // Consider stopping camera here if it's not managed by Flutter
        // stopCameraForMRZ(); // This might be too aggressive if app just goes to background
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        stopCameraForMRZ(); // Ensure camera is stopped
    }
    // --- Permissions ---
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    // --- OCR/Camera Methods (Refactored from old MainActivity) ---
    private void startCameraForMRZ() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get(); // Store reference
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera for MRZ: " + e.getMessage());
                if (ocrResultCallback != null) {
                    ocrResultCallback.error("CAMERA_INIT_FAILED", "Failed to initialize camera: " + e.getLocalizedMessage(), null);
                    ocrResultCallback = null;
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCameraForMRZ() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        // We don't need Preview here if Flutter is rendering its own camera preview
        // or if we're only doing background analysis.
        // If you need a native preview for some reason, you'd integrate it via PlatformView.
        // For OCR, you typically don't show the native camera preview directly
        // unless you're embedding an AndroidView. For this specific OCR task,
        // we're relying on ImageAnalysis only to capture frames.

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720)) // Adjust resolution as needed
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            processImageProxy(imageProxy);
        });

        cameraProvider.unbindAll(); // Unbind existing use cases first
        cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, imageAnalysis);
    }
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImageProxy(@NonNull ImageProxy imageProxy) {
        // Only process if an OCR scan is active and a callback is waiting
        if (ocrResultCallback == null) {
            imageProxy.close();
            return;
        }

        // IMPORTANT: Ensure mrzScanned is true after success or handle this state
        // If you want to stop processing after first MRZ, you can add a flag here.
        // But the Flutter side should call stopMRZScan().

        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        textRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    String foundLine1 = null;
                    String foundLine2 = null;

                    for (Text.TextBlock block : text.getTextBlocks()) {
                        List<Text.Line> lines = block.getLines();
                        for (Text.Line line : lines) {
                            String lineText = line.getText().trim();
                            // Clean up common OCR errors for MRZ
                            lineText = lineText.replace(" ", "") // Remove spaces
                                    .replace("O", "0") // OCR often confuses O with 0
                                    .replace("Q", "0"); // OCR often confuses Q with 0

                            if (foundLine1 == null) {
                                Matcher matcher1 = line1Pattern.matcher(lineText);
                                if (matcher1.find()) {
                                    foundLine1 = lineText;
                                    Log.d(TAG, "Line 1 found: " + foundLine1);
                                }
                            }
                            if (foundLine2 == null) {
                                Matcher matcher2 = line2Pattern.matcher(lineText);
                                if (matcher2.find()) {
                                    foundLine2 = lineText;
                                    Log.d(TAG, "Line 2 found: " + foundLine2);
                                }
                            }
                        }
                    }

                    if (foundLine1 != null && foundLine2 != null) {
                        Log.d(TAG, "Both MRZ lines found!");
                        parseMRZAndReturn(foundLine1, foundLine2);
                        stopCameraForMRZ(); // Stop camera once MRZ is found
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Text recognition failed: " + e.getMessage());
                    // Don't send error for every frame, only if scan fails entirely or on start
                })
                .addOnCompleteListener(task -> {
                    imageProxy.close(); // IMPORTANT: close the imageProxy
                });
    }

    private void parseMRZAndReturn(String line1, String line2) {
        if (ocrResultCallback == null) return; // Callback might have been cleared

        Map<String, String> mrzData = new HashMap<>();
        try {
            // Simplified parsing directly from substrings, as per your original code
            // Consider using JMRTD's MRZInfo(line1, line2) for more robust parsing if needed.
            String documentNumber = line1.substring(5, 14).replace("<", "");
            String dateOfBirth = line2.substring(0, 6);
            String expirationDate = line2.substring(8, 14);

            mrzData.put("documentNumber", documentNumber);
            mrzData.put("dateOfBirth", dateOfBirth);
            mrzData.put("expirationDate", expirationDate);

            // Success: send data back to Flutter
            ocrResultCallback.success(mrzData);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse MRZ: " + e.getMessage());
            ocrResultCallback.error("MRZ_PARSE_FAILED", "Failed to parse MRZ: " + e.getLocalizedMessage(), null);
        } finally {
            ocrResultCallback = null; // Clear callback after responding
        }
    }
    // --- NFC Methods (Already modified in previous answer, ensuring it's here) ---
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNfcIntent(intent);
    }

    private void handleNfcIntent(Intent intent) {
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction()) || NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                String[] techList = tag.getTechList();
                boolean isIsoDepSupported = false;
                for (String tech : techList) {
                    if (tech.equals(IsoDep.class.getName())) {
                        isIsoDepSupported = true;
                        break;
                    }
                }

                if (isIsoDepSupported) {
                    if (nfcResultCallback != null) {
                        // Retrieve MRZ data from the original call's arguments
                        // This assumes the MethodChannel call is still active or you passed it.
                        // For this structure, you'd need to store the documentNumber, dateOfBirth, expirationDate
                        // as member variables if the NFC call happens much later than the MRZ scan.
                        // A better approach is to pass these as arguments to the 'readNfc' method from Flutter.
                        // Assuming they are passed as arguments to 'readNfc' method as per previous example.
                        String documentNumber = (String) channel.getLatestMethodCall().argument("documentNumber"); // Access the latest call
                        String dateOfBirth = (String) channel.getLatestMethodCall().argument("dateOfBirth");
                        String expirationDate = (String) channel.getLatestMethodCall().argument("expirationDate");

                        if (documentNumber == null  dateOfBirth == null  expirationDate == null) {
                            nfcResultCallback.error("MRZ_DATA_MISSING", "MRZ data (docNumber, DOB, Expiry) not provided for NFC scan.", null);
                            nfcResultCallback = null;
                            return;
                        }

                        nfcExecutor.execute(() -> {
                            readNfcTag(tag, documentNumber, dateOfBirth, expirationDate, nfcResultCallback);
                            nfcResultCallback = null; // Clear callback after response
                        });
                    } else {
                        Log.w(TAG, "NFC Tag discovered but no active Flutter 'readNfc' call to respond to.");
                    }
                } else {
                    if (nfcResultCallback != null) {
                        nfcResultCallback.error("UNSUPPORTED_NFC_TAG", "Tag does not support IsoDep (not an eMRTD).", null);
                        nfcResultCallback = null;
                    } else {
                        Toast.makeText(this, "This is not an eID card/passport.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    private void readNfcTag(Tag tag, String documentNumber, String dateOfBirth, String expirationDate, MethodChannel.Result result) {
        // ... (Your existing readNfcTag logic, but ensure it sends data/errors via 'result' object) ...
        // This method was already modified in the previous answer to send a Map back to Flutter.
        // Just make sure it uses the 'result' object passed to it.
        IsoDep isoDep = null;
        PassportService ps = null;
        Map<String, Object> nfcDataMap = new HashMap<>(); // To send back to Flutter

        try {
            isoDep = IsoDep.get(tag);
            if (isoDep == null) {
                throw new IOException("IsoDep not found on tag.");
            }
            isoDep.connect();

            CardService cardService = CardService.getInstance(isoDep);
            cardService.open();
            ps = new PassportService(cardService, PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, true, false);
            ps.open();

            BACKey bacKey = new BACKey(documentNumber, dateOfBirth, expirationDate);
            boolean paceSucceeded = checkBACKEY(bacKey, ps);
            ps.sendSelectApplet(paceSucceeded);
            if (!paceSucceeded) {
                try {
                    int ignored = ps.getInputStream(PassportService.EF_COM).read();
                } catch (Exception e) {
                    ps.doBAC(bacKey);
                }
            }

            // Read DG1
            CardFileInputStream dg1In = ps.getInputStream(PassportService.EF_DG1);
            DG1File dg1File = new DG1File(dg1In);
            nfcDataMap.put("documentType", dg1File.getMRZInfo().getDocumentType());
            nfcDataMap.put("documentNumber", dg1File.getMRZInfo().getDocumentNumber().replace("<", ""));
            nfcDataMap.put("issuingState", dg1File.getMRZInfo().getIssuingState());
            nfcDataMap.put("nationality", dg1File.getMRZInfo().getNationality());
            nfcDataMap.put("dateOfBirth", dg1File.getMRZInfo().getDateOfBirth());
            nfcDataMap.put("gender", dg1File.getMRZInfo().getGender());
            nfcDataMap.put("dateOfExpiry", dg1File.getMRZInfo().getDateOfExpiry());
            nfcDataMap.put("primaryIdentifier", dg1File.getMRZInfo().getPrimaryIdentifier().replace("<", ""));
            nfcDataMap.put("secondaryIdentifier", dg1File.getMRZInfo().getSecondaryIdentifier().replace("<", ""));

            // Read DG2 (Face Image)
            byte[] faceImageBytes = getFaceImageFromDG2(ps);
            if (faceImageBytes != null) {
                nfcDataMap.put("faceImage", faceImageBytes);
            }

            // Read DG11 (Personal Data)
            try {
                CardFileInputStream dg11In = ps.getInputStream(PassportService.EF_DG11);
                DG11File dg11File = new DG11File(dg11In);
                nfcDataMap.put("fullName", dg11File.getNameOfHolder().replace("<", ""));
                nfcDataMap.put("otherNames", dg11File.getOtherNames());
                nfcDataMap.put("placeOfBirth", dg11File.getPlaceOfBirth());
                // Add more DG11 fields as needed
            } catch (Exception e) {
                Log.w(TAG, "DG11 not found or accessible: " + e.getMessage());
                nfcDataMap.put("fullName", "N/A"); // Indicate not found
            }

            result.success(nfcDataMap); // Success: send data back to Flutter

        } catch (Exception e) {
            Log.e(TAG, "NFC read error: " + e.getMessage(), e);
            result.error("NFC_READ_ERROR", e.getLocalizedMessage(), null); // Send error to Flutter
        } finally {
            if (isoDep != null && isoDep.isConnected()) {
                try {
                    isoDep.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing IsoDep: " + e.getMessage());
                }
            }
        }
    }
    // --- Helper Methods (from NFCActivity.java) ---
    private Boolean checkBACKEY(BACKey bacKey, PassportService service) {
        try {
            CardSecurityFile cardSecurityFile = new CardSecurityFile(service.getInputStream(PassportService.EF_CARD_SECURITY));
            Collection<SecurityInfo> securityInfoCollection = cardSecurityFile.getSecurityInfos();
            for (SecurityInfo securityInfo : securityInfoCollection) {
                if (securityInfo instanceof PACEInfo paceInfo) {
                    service.doPACE(bacKey, paceInfo.getObjectIdentifier(), PACEInfo.toParameterSpec(paceInfo.getParameterId()), null);
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false; // Return false if no PACE or other security found
    }

    private byte[] getFaceImageFromDG2(PassportService ps) {
        byte[] imageBytes = null;
        try {
            CardFileInputStream dg2In = ps.getInputStream(PassportService.EF_DG2);
            DG2File dg2File = new DG2File(dg2In);

            if (dg2File.getFaceInfos() != null && !dg2File.getFaceInfos().isEmpty()) {
                FaceInfo faceInfo = dg2File.getFaceInfos().get(0);
                if (faceInfo.getFaceImageInfos() != null && !faceInfo.getFaceImageInfos().isEmpty()) {
                    FaceImageInfo faceImageInfo = faceInfo.getFaceImageInfos().get(0);

                    InputStream imageStream = faceImageInfo.getImageInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[1024];
                    while ((nRead = imageStream.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    buffer.flush();
                    imageBytes = buffer.toByteArray();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading DG2 image: " + e.getMessage(), e);
        }
        return imageBytes;
    }

    private void setupNfcForegroundDispatch(Context context, NfcAdapter nfcAdapter) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_MUTABLE);
        String[][] techLists = new String[][]{
                new String[]{IsoDep.class.getName()},
                new String[]{NfcA.class.getName()},
                new String[]{NfcB.class.getName()}
        };
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, techLists);
    }

    private void stopNfcForegroundDispatch(Context context, NfcAdapter nfcAdapter) {
        nfcAdapter.disableForegroundDispatch(this);
    }
}