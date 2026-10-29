import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/download_record.dart';
class DownloadLibraryService {
  static const _prefsKey = 'download_library_v1';
  static final DownloadLibraryService instance = DownloadLibraryService._();
  final ValueNotifier<int> updates = ValueNotifier<int>(0);
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
      // Deduplicate by filePath keeping latest record
      final seenPaths = <String>{};
      final uniqueList = <DownloadRecord>[];
      for (final r in list) {
        if (!seenPaths.contains(r.filePath)) {
          seenPaths.add(r.filePath);
          uniqueList.add(r);
        }
      }
      return uniqueList;
    } catch (_) {
      return [];
    }
  }
  Future<void> _saveAll(List<DownloadRecord> records) async {
    final prefs = await SharedPreferences.getInstance();
    records.sort((a, b) => b.downloadedAtMs.compareTo(a.downloadedAtMs));
    // Deduplicate before saving
    final seenPaths = <String>{};
    final uniqueList = <DownloadRecord>[];
    for (final r in records) {
      if (!seenPaths.contains(r.filePath)) {
        seenPaths.add(r.filePath);
        uniqueList.add(r);
      }
    }
    await prefs.setString(
      _prefsKey,
      jsonEncode(uniqueList.map((e) => e.toJson()).toList()),
    );
    updates.value++;
  }
  Future<void> add(DownloadRecord record) async {
    final records = await loadAll();
    records.removeWhere((e) => e.id == record.id || e.filePath == record.filePath);
    records.add(record);
    await _saveAll(records);
  }
  Future<void> update(DownloadRecord record) async {
    await add(record);
  }
  Future<void> remove(String id) async {
    final records = await loadAll();
    records.removeWhere((e) => e.id == id);
    await _saveAll(records);
  }
  Future<void> clear() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_prefsKey);
    updates.value++;
  }
}
