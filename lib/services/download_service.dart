import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:gal/gal.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player/video_player.dart';
import '../models/dash_representation.dart';
import '../models/download_record.dart';
import 'cdn_service.dart';
import 'dash_parser.dart';
import 'download_library_service.dart';
import 'facebook_extractor_service.dart';
import 'notification_service.dart';
class DownloadTask {
  final String id;
  final String title;
  final int progress;
  final String status;
  final int speedBps;
  final String? thumbnailPath;
  final bool isPaused;
  final bool isQueued;
  const DownloadTask({
    required this.id,
    required this.title,
    required this.progress,
    required this.status,
    this.speedBps = 0,
    this.thumbnailPath,
    this.isPaused = false,
    this.isQueued = false,
  });
}
class _DownloadParams {
  final String id;
  final String title;
  final bool isDash;
  final bool isNetwork;
  final String videoPath;
  String? mpdContent;
  final DashRepresentation? selectedVideoRep;
  final bool fbCdnEnabled;
  final int? durationMs;
  final String? thumbnailPath;
  final String? originalFbUrl;
  final String? fbVideoId;
  String? resolvedOutputPath;
  _DownloadParams({
    required this.id,
    required this.title,
    required this.isDash,
    required this.isNetwork,
    required this.videoPath,
    this.mpdContent,
    this.selectedVideoRep,
    this.fbCdnEnabled = false,
    this.durationMs,
    this.thumbnailPath,
    this.originalFbUrl,
    this.fbVideoId,
    this.resolvedOutputPath,
  });
  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'isDash': isDash,
        'isNetwork': isNetwork,
        'videoPath': videoPath,
        'mpdContent': mpdContent,
        'selectedVideoRep': selectedVideoRep?.toJson(),
        'fbCdnEnabled': fbCdnEnabled,
        'durationMs': durationMs,
        'thumbnailPath': thumbnailPath,
        'originalFbUrl': originalFbUrl,
        'fbVideoId': fbVideoId,
        'resolvedOutputPath': resolvedOutputPath,
      };
  factory _DownloadParams.fromJson(Map<String, dynamic> json) =>
      _DownloadParams(
        id: json['id'] as String,
        title: json['title'] as String? ?? 'Video',
        isDash: json['isDash'] as bool? ?? false,
        isNetwork: json['isNetwork'] as bool? ?? true,
        videoPath: json['videoPath'] as String? ?? '',
        mpdContent: json['mpdContent'] as String?,
        selectedVideoRep: json['selectedVideoRep'] != null
            ? DashRepresentation.fromJson(
                json['selectedVideoRep'] as Map<String, dynamic>)
            : null,
        fbCdnEnabled: json['fbCdnEnabled'] as bool? ?? false,
        durationMs: json['durationMs'] as int?,
        thumbnailPath: json['thumbnailPath'] as String?,
        originalFbUrl: json['originalFbUrl'] as String?,
        fbVideoId: json['fbVideoId'] as String?,
        resolvedOutputPath: json['resolvedOutputPath'] as String?,
      );
}
class _JobState {
  bool cancelRequested = false;
  bool pauseRequested = false;
  Completer<void>? pauseCompleter;
}
class DownloadService {
  static const _mediaChannel = MethodChannel('ply/media');
  static const _activeTasksPrefsKey = 'ply_active_downloads_v2';
  static const _maxConcurrent = 5;
  static int _activeCount = 0;
  static final _queue = <_DownloadParams>[];
  static final _jobs = <String, _JobState>{};
  static final _taskMap = <String, DownloadTask>{};
  static final _allParams = <String, _DownloadParams>{};
  static final _reservedOutputPaths = <String>{};
  static final ValueNotifier<List<DownloadTask>> actives =
      ValueNotifier<List<DownloadTask>>([]);
  static Future<void> init() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_activeTasksPrefsKey);
      if (raw != null && raw.isNotEmpty) {
        final list = jsonDecode(raw) as List;
        for (final item in list) {
          final map = item as Map<String, dynamic>;
          final params = _DownloadParams.fromJson(map);
          final savedProgress = map['savedProgress'] as int? ?? 0;
          _allParams[params.id] = params;
          if (params.resolvedOutputPath != null) {
            _reservedOutputPaths.add(params.resolvedOutputPath!);
          }
          _taskMap[params.id] = DownloadTask(
            id: params.id,
            title: params.title,
            progress: savedProgress,
            status: 'Paused',
            thumbnailPath: params.thumbnailPath,
            isPaused: true,
            isQueued: false,
          );
        }
        _rebuildActives();
      }
    } catch (_) {}
  }
  static Future<void> _savePersistedState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final list = <Map<String, dynamic>>[];
      for (final p in _allParams.values) {
        final task = _taskMap[p.id];
        list.add({
          ...p.toJson(),
          'savedProgress': task?.progress ?? 0,
          'savedStatus': task?.status ?? 'Paused',
        });
      }
      await prefs.setString(_activeTasksPrefsKey, jsonEncode(list));
    } catch (_) {}
  }
  static void _rebuildActives() {
    final list = <DownloadTask>[
      ..._taskMap.values,
      ..._queue.map((p) => DownloadTask(
            id: p.id,
            title: p.title,
            progress: 0,
            status: 'Queued',
            thumbnailPath: p.thumbnailPath,
            isQueued: true,
          )),
    ];
    actives.value = list;
  }
  static void cancelJob(String id) {
    _queue.removeWhere((p) => p.id == id);
    final param = _allParams.remove(id);
    if (param?.resolvedOutputPath != null) {
      _reservedOutputPaths.remove(param!.resolvedOutputPath!);
    }
    final job = _jobs[id];
    if (job != null) {
      job.cancelRequested = true;
      job.pauseCompleter?.complete();
    }
    _taskMap.remove(id);
    _savePersistedState();
    _rebuildActives();
  }
  static void pauseJob(String id) {
    final job = _jobs[id];
    if (job != null && !job.pauseRequested) {
      job.pauseRequested = true;
    }
    final t = _taskMap[id];
    if (t != null) {
      _taskMap[id] = DownloadTask(
        id: t.id,
        title: t.title,
        progress: t.progress,
        status: 'Paused',
        speedBps: 0,
        thumbnailPath: t.thumbnailPath,
        isPaused: true,
      );
      _savePersistedState();
      _rebuildActives();
    }
  }
  static void resumeJob(String id) {
    final job = _jobs[id];
    if (job != null && job.pauseRequested) {
      job.pauseRequested = false;
      final c = job.pauseCompleter;
      job.pauseCompleter = null;
      c?.complete();
      final t = _taskMap[id];
      if (t != null) {
        _taskMap[id] = DownloadTask(
          id: t.id,
          title: t.title,
          progress: t.progress,
          status: 'Downloading…',
          speedBps: t.speedBps,
          thumbnailPath: t.thumbnailPath,
          isPaused: false,
        );
        _savePersistedState();
        _rebuildActives();
      }
      return;
    }
    final params = _allParams[id];
    if (params != null && !_jobs.containsKey(id)) {
      if (_activeCount >= _maxConcurrent) {
        if (!_queue.any((p) => p.id == id)) {
          _queue.add(params);
        }
        _rebuildActives();
        return;
      }
      _activeCount++;
      _startJob(params);
    }
  }
  static void cancel() {
    for (final id in _allParams.keys.toList()) {
      cancelJob(id);
    }
    _queue.clear();
    _savePersistedState();
    _rebuildActives();
  }
  static String? resolveVideoUrl(
      List<DashRepresentation> reps, DashRepresentation? selected) {
    if (selected != null) {
      final match = reps.where((r) => r.id == selected.id);
      if (match.isNotEmpty) return match.first.baseUrl;
      return selected.baseUrl;
    }
    if (reps.isEmpty) return null;
    return reps.first.baseUrl;
  }
  static Future<void> startBackground({
    required String title,
    required bool isDash,
    required bool isNetwork,
    required String videoPath,
    String? mpdContent,
    DashRepresentation? selectedVideoRep,
    bool fbCdnEnabled = false,
    int? durationMs,
    String? thumbnailPath,
    String? originalFbUrl,
    String? fbVideoId,
  }) async {
    // Prevent duplicate tasks for the same video
    final isAlreadyActive = _allParams.values.any((p) {
      if (originalFbUrl != null &&
          originalFbUrl.isNotEmpty &&
          p.originalFbUrl == originalFbUrl) {
        return true;
      }
      if (fbVideoId != null &&
          fbVideoId.isNotEmpty &&
          p.fbVideoId == fbVideoId) {
        return true;
      }
      if (p.videoPath == videoPath && p.title == title) return true;
      return false;
    });
    if (isAlreadyActive) {
      return;
    }

    final id =
        'dl_${DateTime.now().millisecondsSinceEpoch}_${title.hashCode.abs()}_${_allParams.length}';
    final params = _DownloadParams(
      id: id,
      title: title,
      isDash: isDash,
      isNetwork: isNetwork,
      videoPath: videoPath,
      mpdContent: mpdContent,
      selectedVideoRep: selectedVideoRep,
      fbCdnEnabled: fbCdnEnabled,
      durationMs: durationMs,
      thumbnailPath: thumbnailPath,
      originalFbUrl: originalFbUrl,
      fbVideoId: fbVideoId,
    );
    _allParams[id] = params;
    if (_activeCount >= _maxConcurrent) {
      _queue.add(params);
      _savePersistedState();
      _rebuildActives();
      return;
    }
    _activeCount++;
    _startJob(params);
  }
  static void _startJob(_DownloadParams p) {
    final job = _JobState();
    _jobs[p.id] = job;
    _taskMap[p.id] = DownloadTask(
      id: p.id,
      title: p.title,
      progress: 0,
      status: 'Preparing…',
      thumbnailPath: p.thumbnailPath,
    );
    _savePersistedState();
    _rebuildActives();
    unawaited(_runJob(p, job));
  }
  static void _onJobDone(String id) {
    final param = _allParams.remove(id);
    if (param?.resolvedOutputPath != null) {
      _reservedOutputPaths.remove(param!.resolvedOutputPath!);
    }
    _jobs.remove(id);
    _taskMap.remove(id);
    _activeCount = _activeCount > 0 ? _activeCount - 1 : 0;
    _savePersistedState();
    if (_queue.isNotEmpty) {
      final next = _queue.removeAt(0);
      _activeCount++;
      _startJob(next);
    }
    _rebuildActives();
  }
  static Future<String> _resolveUniqueOutputPath(
      String title, Directory downloadsDir) async {
    final isDefaultTitle = title.trim().isEmpty;
    final safeTitle = isDefaultTitle
        ? 'video'
        : title.trim().replaceAll(RegExp(r'[^\w\s\-\u0600-\u06FF]'), '_');
    final existingRecords = await DownloadLibraryService.instance.loadAll();
    final usedPaths = existingRecords.map((e) => e.filePath).toSet();
    var candidate = '${downloadsDir.path}/$safeTitle.mp4';
    var counter = 1;
    while (await File(candidate).exists() ||
        _reservedOutputPaths.contains(candidate) ||
        usedPaths.contains(candidate)) {
      candidate = '${downloadsDir.path}/${safeTitle}_$counter.mp4';
      counter++;
    }
    _reservedOutputPaths.add(candidate);
    return candidate;
  }
  static Future<void> _runJob(_DownloadParams p, _JobState job) async {
    final stagedFiles = <File>[];
    final id = p.id;
    final title = p.title;
    final thumbnailPath = p.thumbnailPath;
    void updateTask(DownloadTask t) {
      _taskMap[id] = t;
      _rebuildActives();
    }
    try {
      await NotificationService.showProgress(
        title: title,
        body: 'Preparing…',
        progress: 0,
        indeterminate: true,
      );
      final persistentTempDir = await _persistentTempDirectory();
      final downloadsDir = await _downloadsDirectory();
      final outputPath = p.resolvedOutputPath ??
          await _resolveUniqueOutputPath(title, downloadsDir);
      p.resolvedOutputPath = outputPath;
      _savePersistedState();
      final outFile = File(outputPath);
      if (!p.isDash && !p.isNetwork) {
        updateTask(DownloadTask(
            id: id,
            title: title,
            progress: 90,
            status: 'Saving…',
            thumbnailPath: thumbnailPath));
        await NotificationService.showProgress(
            title: title, body: 'Saving…', progress: 90);
        await File(p.videoPath).copy(outputPath);
        await _persistToLibrary(
          id: id,
          title: title,
          filePath: outputPath,
          isDash: p.isDash,
          durationMs: p.durationMs,
          existingThumbnailPath: thumbnailPath,
        );
        await NotificationService.showComplete(title, filePath: outputPath);
        return;
      }
      String resolvedVideoUrl;
      String? audioUrl;
      var effectiveMpd = p.mpdContent;
      if (effectiveMpd == null || !effectiveMpd.contains('<MPD')) {
        if (p.videoPath.contains('<MPD')) {
          effectiveMpd = p.videoPath;
        } else if (!p.videoPath.startsWith('http') && File(p.videoPath).existsSync()) {
          try {
            final text = await File(p.videoPath).readAsString();
            if (text.contains('<MPD')) effectiveMpd = text;
          } catch (_) {}
        }
      }

      final isDash = p.isDash || (effectiveMpd != null && effectiveMpd.contains('<MPD'));
      if (isDash && effectiveMpd != null) {
        final rewrittenMpd = await CdnService.raceAndRewriteMpd(
          effectiveMpd,
          fbCdnEnabled: p.fbCdnEnabled,
        );
        final videoReps = DashParser.parse(rewrittenMpd);
        final maybeVideoUrl = resolveVideoUrl(videoReps, p.selectedVideoRep);
        if (maybeVideoUrl == null) {
          await NotificationService.showError(title, 'No video track found');
          return;
        }
        resolvedVideoUrl = maybeVideoUrl;
        final audioRep = DashParser.bestAudio(rewrittenMpd);
        if (audioRep != null) {
          audioUrl = audioRep.baseUrl;
        }
      } else {
        resolvedVideoUrl = await CdnService.raceAndRewrite(
          p.videoPath,
          fbCdnEnabled: p.fbCdnEnabled,
        );
      }
      final stagedVideoPath = '${persistentTempDir.path}/${id}_video.mp4';
      final stagedAudioPath =
          audioUrl != null ? '${persistentTempDir.path}/${id}_audio.mp4' : null;
      await NotificationService.showProgress(
          title: title, body: 'Downloading… 0%', progress: 0);
      final videoWeight = audioUrl != null ? 0.7 : 1.0;
      final audioWeight = audioUrl != null ? 0.3 : 0.0;
      var lastPct = -1;

      Future<String?> refreshFacebookTrack({required bool isAudio}) async {
        final target = p.fbVideoId ?? p.originalFbUrl;
        if (target == null || target.isEmpty) return null;
        try {
          final res = await FacebookExtractorService.instance.extract(target);
          p.mpdContent = res.dashManifest;
          final effectiveMpd = await CdnService.raceAndRewriteMpd(
            res.dashManifest,
            fbCdnEnabled: p.fbCdnEnabled,
          );
          if (isAudio) {
            final audioRep = DashParser.bestAudio(effectiveMpd);
            return audioRep?.baseUrl;
          } else {
            final videoReps = DashParser.parse(effectiveMpd);
            return resolveVideoUrl(videoReps, p.selectedVideoRep);
          }
        } catch (_) {
          return null;
        }
      }

      await _downloadToFile(
        resolvedVideoUrl,
        stagedVideoPath,
        job: job,
        onRefreshExpiredUrl: () => refreshFacebookTrack(isAudio: false),
        onProgress: (received, total, speedBps) {
          if (total == null || total <= 0) return;
          final pct =
              (received / total * videoWeight * 100).clamp(0, 89).toInt();
          if (pct > lastPct) {
            lastPct = pct;
            updateTask(DownloadTask(
              id: id,
              title: title,
              progress: pct,
              status: 'Downloading…',
              speedBps: speedBps,
              thumbnailPath: thumbnailPath,
              isPaused: job.pauseRequested,
            ));
            unawaited(NotificationService.showProgress(
                title: title, body: 'Downloading… $pct%', progress: pct));
          }
        },
      );
      stagedFiles.add(File(stagedVideoPath));
      if (audioUrl != null && stagedAudioPath != null) {
        await _downloadToFile(
          audioUrl,
          stagedAudioPath,
          job: job,
          onRefreshExpiredUrl: () => refreshFacebookTrack(isAudio: true),
          onProgress: (received, total, speedBps) {
            if (total == null || total <= 0) return;
            final pct =
                ((videoWeight + received / total * audioWeight) * 100)
                    .clamp(0, 89)
                    .toInt();
            if (pct > lastPct) {
              lastPct = pct;
              updateTask(DownloadTask(
                id: id,
                title: title,
                progress: pct,
                status: 'Downloading…',
                speedBps: speedBps,
                thumbnailPath: thumbnailPath,
                isPaused: job.pauseRequested,
              ));
              unawaited(NotificationService.showProgress(
                title: title, body: 'Downloading… $pct%', progress: pct));
            }
          },
        );
        stagedFiles.add(File(stagedAudioPath));
      }
      if (job.cancelRequested) throw const _CancelException();
      updateTask(DownloadTask(
          id: id,
          title: title,
          progress: 90,
          status: 'Merging…',
          thumbnailPath: thumbnailPath));
      await NotificationService.showProgress(
          title: title, body: 'Merging…', progress: 90);
      var muxed = false;
      String? muxError;

      if (stagedAudioPath == null) {
        // Single stream already in MP4 container, no remuxing needed
        await File(stagedVideoPath).copy(outputPath);
        muxed = true;
      } else {
        try {
          muxed = await _mediaChannel.invokeMethod<bool>('mux', {
                'videoPath': stagedVideoPath,
                'audioPath': stagedAudioPath,
                'outputPath': outputPath,
              }) ??
              false;
        } on PlatformException catch (e) {
          muxError = e.message ?? e.code;
        } catch (e) {
          muxError = e.toString();
        }

        // If muxing did not produce a valid output file, fallback to copying video
        // so the user never loses their download.
        final targetFile = File(outputPath);
        if (!muxed || !await targetFile.exists() || (await targetFile.length()) == 0) {
          try {
            await File(stagedVideoPath).copy(outputPath);
            muxed = true;
          } catch (e) {
            muxError = e.toString();
          }
        }
      }

      final outExists = await outFile.exists();
      final outLength = outExists ? await outFile.length() : 0;

      if (muxed && outExists && outLength > 0) {
        updateTask(DownloadTask(
            id: id, title: title, progress: 98, status: 'Saving…'));
        await NotificationService.showProgress(
            title: title, body: 'Saving…', progress: 98);
        await _persistToLibrary(
          id: id,
          title: title,
          filePath: outputPath,
          isDash: p.isDash,
          durationMs: p.durationMs,
          existingThumbnailPath: thumbnailPath,
        );
        await NotificationService.showComplete(title, filePath: outputPath);
      } else {
        await NotificationService.showError(title, muxError ?? 'Download failed');
      }
    } on _CancelException {
      try {
        await NotificationService.cancel();
      } catch (_) {}
    } catch (e) {
      await NotificationService.showError(title, e.toString());
    } finally {
      for (final f in stagedFiles) {
        try {
          if (await f.exists()) await f.delete();
        } catch (_) {}
      }
      _onJobDone(id);
    }
  }
  static Future<String> _publicPlyRoot() async {
    if (Platform.isAndroid) {
      final ext = await getExternalStorageDirectory();
      if (ext != null) {
        final root = ext.path.split('/Android/').first;
        return root;
      }
    }
    final docs = await getApplicationDocumentsDirectory();
    return docs.path;
  }
  static Future<Directory> _downloadsDirectory() async {
    if (Platform.isAndroid) {
      try {
        final ext = await getExternalStorageDirectory();
        if (ext != null) {
          final root = ext.path.split('/Android/').first;
          final pubDir = Directory('$root/Ply/Ply Videos');
          try {
            if (!await pubDir.exists()) await pubDir.create(recursive: true);
            final probe = File('${pubDir.path}/.probe');
            await probe.writeAsString('1');
            await probe.delete();
            return pubDir;
          } catch (_) {}
          final appExtDir = Directory('${ext.path}/Ply Videos');
          if (!await appExtDir.exists()) await appExtDir.create(recursive: true);
          return appExtDir;
        }
      } catch (_) {}
    }
    final docs = await getApplicationDocumentsDirectory();
    final dir = Directory('${docs.path}/Ply Videos');
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }
  static Future<Directory> _persistentTempDirectory() async {
    try {
      final temp = await getTemporaryDirectory();
      final dir = Directory('${temp.path}/.incomplete');
      if (!await dir.exists()) await dir.create(recursive: true);
      return dir;
    } catch (_) {
      final docs = await getApplicationDocumentsDirectory();
      final dir = Directory('${docs.path}/.incomplete');
      if (!await dir.exists()) await dir.create(recursive: true);
      return dir;
    }
  }
  static Future<Directory> _thumbnailsDirectory() async {
    try {
      final docs = await getApplicationDocumentsDirectory();
      final dir = Directory('${docs.path}/thumbnails');
      if (!await dir.exists()) await dir.create(recursive: true);
      return dir;
    } catch (_) {
      final temp = await getTemporaryDirectory();
      final dir = Directory('${temp.path}/thumbnails');
      if (!await dir.exists()) await dir.create(recursive: true);
      return dir;
    }
  }
  static Future<void> syncWithPublicFolder() async {
    try {
      final dirsToScan = <Directory>[];
      final currentDir = await _downloadsDirectory();
      dirsToScan.add(currentDir);
      if (Platform.isAndroid) {
        try {
          final ext = await getExternalStorageDirectory();
          if (ext != null) {
            final root = ext.path.split('/Android/').first;
            final pubDir = Directory('$root/Ply/Ply Videos');
            if (await pubDir.exists() && pubDir.path != currentDir.path) {
              dirsToScan.add(pubDir);
            }
            final appExtDir = Directory('${ext.path}/Ply Videos');
            if (await appExtDir.exists() && appExtDir.path != currentDir.path) {
              dirsToScan.add(appExtDir);
            }
          }
        } catch (_) {}
      }
      final existing = await DownloadLibraryService.instance.loadAll();
      final existingPaths = existing.map((e) => File(e.filePath).path).toSet();
      final existingNames = existing.map((e) => e.filePath.split('/').last.toLowerCase()).toSet();
      final activePaths = <String>{..._reservedOutputPaths};
      for (final p in _allParams.values) {
        if (p.resolvedOutputPath != null) activePaths.add(p.resolvedOutputPath!);
      }

      for (final dir in dirsToScan) {
        if (!await dir.exists()) continue;
        final files = dir
            .listSync()
            .whereType<File>()
            .where((f) {
              final p = f.path.toLowerCase();
              return (p.endsWith('.mp4') ||
                      p.endsWith('.mkv') ||
                      p.endsWith('.webm')) &&
                  !p.contains('.incomplete') &&
                  f.lengthSync() > 0;
            })
            .toList();
        for (final file in files) {
          final name = file.uri.pathSegments.last;
          if (existingPaths.contains(file.path) ||
              existingNames.contains(name.toLowerCase()) ||
              activePaths.contains(file.path)) {
            continue;
          }
          final title =
              name.contains('.') ? name.substring(0, name.lastIndexOf('.')) : name;
          final id = 'scan_${file.path.hashCode.abs()}';
          int durationMs = 0;
          try {
            final ctrl = VideoPlayerController.file(file);
            await ctrl.initialize();
            durationMs = ctrl.value.duration.inMilliseconds;
            await ctrl.dispose();
          } catch (_) {}
          String? thumbnailPath;
          try {
            final thumbsDir = await _thumbnailsDirectory();
            final candidatePath = '${thumbsDir.path}/$id.jpg';
            final ok = await _mediaChannel.invokeMethod<bool>('thumbnail', {
                  'videoPath': file.path,
                  'outputPath': candidatePath,
                  'timeUs': 1000000,
                }) ??
                false;
            if (ok && await File(candidatePath).exists()) {
              thumbnailPath = candidatePath;
            }
          } catch (_) {}
          await DownloadLibraryService.instance.add(DownloadRecord(
            id: id,
            title: title,
            filePath: file.path,
            durationMs: durationMs,
            thumbnailPath: thumbnailPath,
            isDash: false,
            downloadedAtMs: file.statSync().modified.millisecondsSinceEpoch,
          ));
        }
      }
    } catch (_) {}
  }
  static Future<void> _persistToLibrary({
    required String id,
    required String title,
    required String filePath,
    required bool isDash,
    int? durationMs,
    String? existingThumbnailPath,
  }) async {
    var resolvedDuration = durationMs ?? 0;
    if (resolvedDuration <= 0) {
      try {
        final controller = VideoPlayerController.file(File(filePath));
        await controller.initialize();
        resolvedDuration = controller.value.duration.inMilliseconds;
        await controller.dispose();
      } catch (_) {}
    }
    String? thumbnailPath;
    try {
      final thumbsDir = await _thumbnailsDirectory();
      final candidatePath = '${thumbsDir.path}/$id.jpg';
      final ok = await _mediaChannel.invokeMethod<bool>('thumbnail', {
            'videoPath': filePath,
            'outputPath': candidatePath,
            'timeUs': 1000000,
          }) ??
          false;
      if (ok && await File(candidatePath).exists()) {
        thumbnailPath = candidatePath;
      }
    } catch (_) {}

    if (thumbnailPath == null &&
        existingThumbnailPath != null &&
        File(existingThumbnailPath).existsSync()) {
      try {
        final thumbsDir = await _thumbnailsDirectory();
        final candidatePath = '${thumbsDir.path}/$id.jpg';
        await File(existingThumbnailPath).copy(candidatePath);
        thumbnailPath = candidatePath;
      } catch (_) {
        thumbnailPath = existingThumbnailPath;
      }
    }

    await DownloadLibraryService.instance.add(DownloadRecord(
      id: id,
      title: title,
      filePath: filePath,
      durationMs: resolvedDuration,
      thumbnailPath: thumbnailPath,
      isDash: isDash,
      downloadedAtMs: DateTime.now().millisecondsSinceEpoch,
    ));
  }

  static Future<void> _downloadToFile(
    String initialUrl,
    String path, {
    required _JobState job,
    required void Function(int received, int? total, int speedBps) onProgress,
    Future<String?> Function()? onRefreshExpiredUrl,
  }) async {
    final file = File(path);
    String currentUrl = initialUrl;
    int retryCount = 0;
    const maxRetries = 10;
    int? expectedTotalBytes;
    dynamic lastError;

    while (retryCount < maxRetries) {
      if (job.cancelRequested) throw const _CancelException();

      int existingBytes = 0;
      if (await file.exists()) {
        existingBytes = await file.length();
      }

      if (expectedTotalBytes != null && existingBytes >= expectedTotalBytes) {
        return;
      }

      final headers = CdnService.headersFor(currentUrl);
      if (existingBytes > 0) {
        headers['Range'] = 'bytes=$existingBytes-';
      }

      http.Client? client;
      IOSink? sink;
      try {
        client = http.Client();
        final request = http.Request('GET', Uri.parse(currentUrl));
        request.headers.addAll(headers);

        final streamedResponse =
            await client.send(request).timeout(const Duration(seconds: 25));
        final statusCode = streamedResponse.statusCode;

        // If URL expired (403/410), try refreshing it using the Facebook video ID or original URL
        if ((statusCode == 403 || statusCode == 410) && onRefreshExpiredUrl != null) {
          final freshUrl = await onRefreshExpiredUrl();
          if (freshUrl != null && freshUrl.isNotEmpty && freshUrl != currentUrl) {
            currentUrl = freshUrl;
            retryCount++;
            await Future.delayed(const Duration(milliseconds: 500));
            continue;
          }
        }

        final isPartial = statusCode == 206;
        if (statusCode != 200 && statusCode != 206) {
          throw Exception('HTTP $statusCode for $currentUrl');
        }

        final appendMode = isPartial && existingBytes > 0;
        var received = appendMode ? existingBytes : 0;

        if (streamedResponse.contentLength != null) {
          expectedTotalBytes =
              streamedResponse.contentLength! + (appendMode ? existingBytes : 0);
        }
        final total = expectedTotalBytes;

        sink = file.openWrite(
          mode: appendMode ? FileMode.append : FileMode.write,
        );

        var speedBps = 0;
        var lastSpeedReceived = received;
        var lastSpeedTime = DateTime.now();

        await for (final chunk in streamedResponse.stream.timeout(const Duration(seconds: 20))) {
          if (job.cancelRequested) throw const _CancelException();
          if (job.pauseRequested) {
            job.pauseCompleter = Completer<void>();
            await job.pauseCompleter!.future;
            if (job.cancelRequested) throw const _CancelException();
          }
          sink.add(chunk);
          received += chunk.length;
          final now = DateTime.now();
          final elapsed = now.difference(lastSpeedTime).inMilliseconds;
          if (elapsed >= 500) {
            speedBps =
                ((received - lastSpeedReceived) * 1000 / elapsed).round();
            lastSpeedReceived = received;
            lastSpeedTime = now;
          }
          onProgress(received, total, speedBps);
        }

        await sink.flush();
        await sink.close();
        sink = null;

        if (await file.exists()) {
          final finalLength = await file.length();
          if (expectedTotalBytes == null || finalLength >= expectedTotalBytes) {
            return;
          }
        }
      } catch (e) {
        lastError = e;
        if (job.cancelRequested) throw const _CancelException();
        retryCount++;
        if (retryCount >= maxRetries) {
          break;
        }
        await Future.delayed(
            Duration(milliseconds: (1000 * retryCount).clamp(1000, 3500)));
      } finally {
        if (sink != null) {
          try {
            await sink.flush();
            await sink.close();
          } catch (_) {}
        }
        client?.close();
      }
    }
    if (lastError != null) throw lastError;
  }
}

class _CancelException implements Exception {
  const _CancelException();
}
