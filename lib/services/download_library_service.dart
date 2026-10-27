import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/download_record.dart';
class DownloadLibraryService {
  static const _prefsKey = 'download_library_v1';
  static final DownloadLibraryService instance = DownloadLibraryService._();
  DownloadLibraryService._();
  Future<List<DownloadRecord>> loadAll() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_prefsKey);
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = (jsonDecode(raw) as List)
          .map((e) => DownloadRecord.fromJson(e as Map<String, dynamic>))
          .toList();
      list.sort((a, b) => b.downloadedAtMs.compareTo(a.downloadedAtMs));
      return list;
    } catch (_) {
      return [];
    }
  }
  Future<void> _saveAll(List<DownloadRecord> records) async {
    final prefs = await SharedPreferences.getInstance();
    records.sort((a, b) => b.downloadedAtMs.compareTo(a.downloadedAtMs));
    await prefs.setString(
      _prefsKey,
      jsonEncode(records.map((e) => e.toJson()).toList()),
    );
  }
  Future<void> add(DownloadRecord record) async {
    final records = await loadAll();
    records.removeWhere((e) => e.id == record.id);
    records.add(record);
    await _saveAll(records);
  }
  Future<void> remove(String id) async {
    final records = await loadAll();
    records.removeWhere((e) => e.id == id);
    await _saveAll(records);
  }
  Future<void> clear() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_prefsKey);
  }
}
