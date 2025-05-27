import 'package:flutter/services.dart';

class NfcPassportReader {
  static const MethodChannel _channel = MethodChannel(
    'com.example.nfcreaderapp/nfc_reader',
  );

  Future<Map<String, dynamic>> scanMRZ() async {
    try {
      final Map<String, dynamic> result = await _channel.invokeMethod(
        'scanMRZ',
      );
      return result;
    } on PlatformException catch (e) {
      throw Exception("Failed to scan MRZ: '${e.message}'");
    }
  }


  Future<Map<String, dynamic>> startMRZScan() async {
    try {
      // The native side will send back the MRZ data once found
      final Map<dynamic, dynamic> result = await _channel.invokeMethod('startMRZScan');
      return result.cast<String, dynamic>(); // Cast to desired type
    } on PlatformException catch (e) {
      throw Exception("Failed to start MRZ scan: '${e.message}' (Code: ${e.code}, Details: ${e.details})");
    }
  }


  Future<void> stopMRZScan() async {
    try {
      await _channel.invokeMethod('stopMRZScan');
    } on PlatformException catch (e) {
      // Log or handle error if stopping fails, but usually not critical for UI
      print("Failed to stop MRZ scan: '${e.message}'");
    }
  }

  Future<Map<String, dynamic>> readNfc(
    String documentNumber,
    String dateOfBirth,
    String expirationDate,
  ) async {
    try {
      final Map<String, dynamic> result = await _channel
          .invokeMethod('readNfc', {
            'documentNumber': documentNumber,
            'dateOfBirth': dateOfBirth,
            'expirationDate': expirationDate,
          });
      return result;
    } on PlatformException catch (e) {
      throw Exception("Failed to read NFC: '${e.message}'");
    }
  }
}
