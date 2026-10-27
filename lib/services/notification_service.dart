import 'package:flutter_local_notifications/flutter_local_notifications.dart';
class NotificationService {
  static final _plugin = FlutterLocalNotificationsPlugin();
  static const _channelId = 'ply_downloads';
  static const _channelName = 'Downloads';
  static const _notifId = 1;
  static void Function(String filePath)? onOpenDownload;
  static String? _pendingFilePath;
  static String? takePendingFilePath() {
    final path = _pendingFilePath;
    _pendingFilePath = null;
    return path;
  }
  static Future<void> init() async {
    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    await _plugin.initialize(
      const InitializationSettings(android: android),
      onDidReceiveNotificationResponse: _handleTap,
    );
    final launchDetails = await _plugin.getNotificationAppLaunchDetails();
    if (launchDetails?.didNotificationLaunchApp == true) {
      final payload = launchDetails?.notificationResponse?.payload;
      if (payload != null && payload.isNotEmpty) {
        _pendingFilePath = payload;
      }
    }
    const channel = AndroidNotificationChannel(
      _channelId,
      _channelName,
      description: 'Video download progress',
      importance: Importance.low,
      playSound: false,
      enableVibration: false,
    );
    await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel);
  }
  static void _handleTap(NotificationResponse response) {
    final payload = response.payload;
    if (payload != null && payload.isNotEmpty) {
      onOpenDownload?.call(payload);
    }
  }
  static Future<void> requestPermission() async {
    await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
  }
  static Future<void> showProgress({
    required String title,
    required String body,
    required int progress,
    bool indeterminate = false,
  }) async {
    try {
      await _plugin.show(
        _notifId,
        title,
        body,
        NotificationDetails(
          android: AndroidNotificationDetails(
            _channelId,
            _channelName,
            channelDescription: 'Video download progress',
            importance: Importance.low,
            priority: Priority.low,
            showProgress: true,
            maxProgress: 100,
            progress: progress,
            indeterminate: indeterminate,
            onlyAlertOnce: true,
            ongoing: true,
            playSound: false,
            enableVibration: false,
            autoCancel: false,
          ),
        ),
      );
    } catch (_) {}
  }
  static Future<void> showComplete(String title, {String? filePath}) async {
    try {
      await _plugin.show(
        _notifId,
        'Download complete',
        title,
        const NotificationDetails(
          android: AndroidNotificationDetails(
            _channelId,
            _channelName,
            channelDescription: 'Video download progress',
            importance: Importance.defaultImportance,
            priority: Priority.defaultPriority,
            ongoing: false,
            autoCancel: true,
          ),
        ),
        payload: filePath,
      );
    } catch (_) {}
  }
  static Future<void> showError(String title, String detail) async {
    try {
      await _plugin.show(
        _notifId,
        'Download failed',
        '$title — $detail',
        const NotificationDetails(
          android: AndroidNotificationDetails(
            _channelId,
            _channelName,
            channelDescription: 'Video download progress',
            importance: Importance.defaultImportance,
            priority: Priority.defaultPriority,
            ongoing: false,
            autoCancel: true,
          ),
        ),
      );
    } catch (_) {}
  }
  static Future<void> cancel() async {
    try {
      await _plugin.cancel(_notifId);
    } catch (_) {}
  }
}
