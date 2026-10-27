import 'package:xml/xml.dart';
import '../models/dash_representation.dart';
class DashParser {
  static final RegExp _rootTitlePattern =
      RegExp(r'<Title\b[^>]*>([\s\S]*?)<\/Title>', caseSensitive: false);
  static List<DashRepresentation> parse(String xmlContent) {
    return _parseByType(xmlContent, 'video');
  }
  static List<DashRepresentation> parseAudio(String xmlContent) {
    return _parseByType(xmlContent, 'audio');
  }
  static DashRepresentation? bestAudio(String xmlContent) {
    final reps = parseAudio(xmlContent);
    return reps.isEmpty ? null : reps.first;
  }
  static List<DashRepresentation> _parseByType(
      String xmlContent, String wantedType) {
    final representations = <DashRepresentation>[];
    try {
      final document = XmlDocument.parse(xmlContent);
      for (final period in document.findAllElements('Period')) {
        for (final adaptationSet in period.findElements('AdaptationSet')) {
          final contentType = _resolveContentType(adaptationSet);
          if (contentType != wantedType) continue;
          final setBaseUrl = _extractBaseUrl(adaptationSet);
          for (final rep in adaptationSet.findElements('Representation')) {
            final id = rep.getAttribute('id') ?? '';
            final bandwidth =
                int.tryParse(rep.getAttribute('bandwidth') ?? '0') ?? 0;
            final width =
                int.tryParse(rep.getAttribute('width') ?? '0') ?? 0;
            final height =
                int.tryParse(rep.getAttribute('height') ?? '0') ?? 0;
            final rawLabel = wantedType == 'video'
                ? (rep.getAttribute('FBQualityLabel') ??
                    rep.getAttribute('qualityLabel') ??
                    (height > 0 ? '${height}p' : '${bandwidth ~/ 1000}kbps'))
                : (rep.getAttribute('audioSamplingRate') != null
                    ? '${rep.getAttribute('audioSamplingRate')} Hz'
                    : 'Audio');
            final repBaseUrl = _extractBaseUrl(rep);
            final baseUrl =
                repBaseUrl.isNotEmpty ? repBaseUrl : setBaseUrl;
            if (baseUrl.isNotEmpty) {
              representations.add(DashRepresentation(
                id: id,
                baseUrl: baseUrl,
                bandwidth: bandwidth,
                width: width,
                height: height,
                contentType: wantedType,
                qualityLabel: rawLabel,
              ));
            }
          }
        }
      }
      representations.sort((a, b) => b.bandwidth.compareTo(a.bandwidth));
    } catch (_) {}
    return representations;
  }
  static String? extractTitle(String xmlContent) {
    final regexMatch = _rootTitlePattern.firstMatch(xmlContent);
    if (regexMatch != null) {
      final text = regexMatch.group(1)?.trim() ?? '';
      if (text.isNotEmpty) return text;
    }
    try {
      final document = XmlDocument.parse(xmlContent);
      final titles = document.findAllElements('Title');
      for (final title in titles) {
        final text = title.innerText.trim();
        if (text.isNotEmpty) return text;
      }
    } catch (_) {}
    return null;
  }
  static String stripTitle(String xmlContent) {
    final preStripped = xmlContent.replaceAll(_rootTitlePattern, '');
    try {
      final document = XmlDocument.parse(preStripped);
      final titles = document.findAllElements('Title').toList();
      for (final title in titles) {
        title.parent?.children.remove(title);
      }
      return document.toXmlString(pretty: false);
    } catch (_) {
      return preStripped;
    }
  }
  static String generateSubMpd(String originalXml, String videoRepId) {
    try {
      final doc = XmlDocument.parse(originalXml);
      for (final adaptationSet in doc.findAllElements('AdaptationSet')) {
        final contentType = _resolveContentType(adaptationSet);
        if (contentType != 'video') continue;
        final toRemove = adaptationSet
            .findElements('Representation')
            .where((rep) => rep.getAttribute('id') != videoRepId)
            .toList();
        for (final rep in toRemove) {
          rep.parent?.children.remove(rep);
        }
      }
      return doc.toXmlString(pretty: false);
    } catch (_) {
      return originalXml;
    }
  }
  static String _resolveContentType(XmlElement el) {
    final direct = el.getAttribute('contentType')?.toLowerCase();
    if (direct == 'video' || direct == 'audio') return direct!;
    final mimeType = el.getAttribute('mimeType')?.toLowerCase() ?? '';
    if (mimeType.startsWith('video')) return 'video';
    if (mimeType.startsWith('audio')) return 'audio';
    if (el.findElements('AudioChannelConfiguration').isNotEmpty) return 'audio';
    for (final rep in el.findElements('Representation')) {
      final repDirect = rep.getAttribute('contentType')?.toLowerCase();
      if (repDirect == 'video' || repDirect == 'audio') return repDirect!;
      final mime = rep.getAttribute('mimeType')?.toLowerCase() ?? '';
      if (mime.startsWith('video')) return 'video';
      if (mime.startsWith('audio')) return 'audio';
      final codecs = rep.getAttribute('codecs')?.toLowerCase() ?? '';
      if (codecs.startsWith('avc') ||
          codecs.startsWith('hev') ||
          codecs.startsWith('hvc') ||
          codecs.startsWith('vp') ||
          codecs.startsWith('mp4v')) {
        return 'video';
      }
      if (codecs.startsWith('mp4a') ||
          codecs.startsWith('aac') ||
          codecs.startsWith('opus') ||
          codecs.startsWith('vorbis') ||
          codecs.startsWith('ac-3') ||
          codecs.startsWith('ec-3') ||
          codecs.startsWith('flac')) {
        return 'audio';
      }
      final enc = rep.getAttribute('FBEncodingTag')?.toLowerCase() ?? '';
      if (enc.contains('video') || enc.contains('h264') || enc.contains('h265')) return 'video';
      if (enc.contains('audio') || enc.contains('aac')) return 'audio';
      if (rep.getAttribute('audioSamplingRate') != null) return 'audio';
      if (rep.findElements('AudioChannelConfiguration').isNotEmpty) return 'audio';
      if ((rep.getAttribute('width') != null && rep.getAttribute('width') != '0') ||
          (rep.getAttribute('height') != null && rep.getAttribute('height') != '0')) {
        return 'video';
      }
    }
    return '';
  }
  static String _extractBaseUrl(XmlElement el) {
    final nodes = el.findElements('BaseURL');
    if (nodes.isEmpty) return '';
    return nodes.first.innerText.trim();
  }
}
