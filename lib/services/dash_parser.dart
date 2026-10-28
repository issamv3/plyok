import 'package:xml/xml.dart';
import '../models/dash_representation.dart';
import 'dash_xml_sanitizer.dart';

class DashParser {
  static final RegExp _rootTitlePattern =
      RegExp(r'<Title\b[^>]*>([\s\S]*?)<\/Title>', caseSensitive: false);

  static String sanitizeMpd(String xmlContent) {
    var text = xmlContent.replaceAll(_rootTitlePattern, '').trim();
    final mpdStart = text.indexOf(RegExp(r'<MPD\b', caseSensitive: false));
    final mpdEnd = text.lastIndexOf(RegExp(r'<\/MPD>', caseSensitive: false));
    if (mpdStart != -1 && mpdEnd != -1 && mpdEnd > mpdStart) {
      text = text.substring(mpdStart, mpdEnd + 6);
    }
    return DashXmlSanitizer.sanitize(text);
  }

  static List<DashRepresentation> parse(String xmlContent) {
    return _parseByType(xmlContent, 'video');
  }

  static List<DashRepresentation> parseAudio(String xmlContent) {
    return _parseByType(xmlContent, 'audio');
  }

  static DashRepresentation? bestAudio(String xmlContent) {
    final reps = parseAudio(xmlContent);
    if (reps.isNotEmpty) return reps.first;
    final fallbackUrl = extractAudioUrl(xmlContent);
    if (fallbackUrl != null && fallbackUrl.isNotEmpty) {
      return DashRepresentation(
        id: 'audio_fallback',
        baseUrl: fallbackUrl,
        bandwidth: 128000,
        width: 0,
        height: 0,
        contentType: 'audio',
        qualityLabel: 'Audio',
      );
    }
    return null;
  }

  static String? extractAudioUrl(String xmlContent) {
    if (xmlContent.isEmpty) return null;

    // 1. Check audio AdaptationSet block
    final audioAdaptationPattern = RegExp(
      r'<AdaptationSet[^>]*?(?:contentType=["\x27]audio["\x27]|mimeType=["\x27]audio\/[^"\x27]+["\x27]|codecs=["\x27](?:mp4a|aac)[^"\x27]*["\x27]|audio)[^>]*>([\s\S]*?)<\/AdaptationSet>',
      caseSensitive: false,
    );
    for (final match in audioAdaptationPattern.allMatches(xmlContent)) {
      final block = match.group(1) ?? '';
      final urlMatch = RegExp(r'<(?:BaseURL|baseURL|baseurl)[^>]*>([\s\S]*?)<\/(?:BaseURL|baseURL|baseurl)>', caseSensitive: false).firstMatch(block);
      if (urlMatch != null) {
        final url = urlMatch.group(1)?.trim().replaceAll('&amp;', '&');
        if (url != null && url.isNotEmpty) return url;
      }
    }

    // 2. Check Representation with audio attributes
    final audioRepPattern = RegExp(
      r'<Representation[^>]*?(?:contentType=["\x27]audio["\x27]|mimeType=["\x27]audio\/[^"\x27]+["\x27]|codecs=["\x27](?:mp4a|aac)[^"\x27]*["\x27]|audioSamplingRate)[^>]*>([\s\S]*?)<\/Representation>',
      caseSensitive: false,
    );
    for (final match in audioRepPattern.allMatches(xmlContent)) {
      final block = match.group(1) ?? '';
      final urlMatch = RegExp(r'<(?:BaseURL|baseURL|baseurl)[^>]*>([\s\S]*?)<\/(?:BaseURL|baseURL|baseurl)>', caseSensitive: false).firstMatch(block);
      if (urlMatch != null) {
        final url = urlMatch.group(1)?.trim().replaceAll('&amp;', '&');
        if (url != null && url.isNotEmpty) return url;
      }
    }

    // 3. Search all BaseURL tags for audio hints
    final allBaseUrls = RegExp(
      r'<(?:BaseURL|baseURL|baseurl)[^>]*>([\s\S]*?)<\/(?:BaseURL|baseURL|baseurl)>',
      caseSensitive: false,
    );
    for (final m in allBaseUrls.allMatches(xmlContent)) {
      final raw = m.group(1)?.trim().replaceAll('&amp;', '&') ?? '';
      if (raw.isNotEmpty) {
        final lower = raw.toLowerCase();
        if (lower.contains('.m4a') ||
            lower.contains('audio') ||
            lower.contains('audiocodecs') ||
            lower.contains('_audio_') ||
            lower.contains('codecs=mp4a')) {
          return raw;
        }
      }
    }

    return null;
  }

  static List<DashRepresentation> _parseByType(
      String xmlContent, String wantedType) {
    final representations = <DashRepresentation>[];
    if (xmlContent.isEmpty) return representations;

    final cleanedXml = sanitizeMpd(xmlContent);

    try {
      final document = XmlDocument.parse(cleanedXml);
      final mpdBaseUrl = _extractBaseUrl(document.rootElement);

      for (final period in document.findAllElements('Period')) {
        final periodBaseUrl = _extractBaseUrl(period).isNotEmpty
            ? _extractBaseUrl(period)
            : mpdBaseUrl;

        for (final adaptationSet in period.findElements('AdaptationSet')) {
          final contentType = _resolveContentType(adaptationSet);
          if (contentType != wantedType) continue;

          final setBaseUrl = _extractBaseUrl(adaptationSet).isNotEmpty
              ? _extractBaseUrl(adaptationSet)
              : periodBaseUrl;

          final repElements = adaptationSet.findElements('Representation').toList();
          if (repElements.isEmpty && wantedType == 'audio' && setBaseUrl.isNotEmpty) {
            representations.add(DashRepresentation(
              id: adaptationSet.getAttribute('id') ?? 'audio_0',
              baseUrl: setBaseUrl.replaceAll('&amp;', '&'),
              bandwidth: int.tryParse(adaptationSet.getAttribute('bandwidth') ?? '128000') ?? 128000,
              width: 0,
              height: 0,
              contentType: 'audio',
              qualityLabel: 'Audio',
            ));
            continue;
          }

          for (final rep in repElements) {
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
            final baseUrl = repBaseUrl.isNotEmpty
                ? repBaseUrl
                : setBaseUrl;

            if (baseUrl.isNotEmpty) {
              representations.add(DashRepresentation(
                id: id,
                baseUrl: baseUrl.replaceAll('&amp;', '&'),
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

    // Fallback: If XML parsing threw or found nothing, try regex recovery
    if (representations.isEmpty) {
      if (wantedType == 'audio') {
        final fallbackAudio = extractAudioUrl(cleanedXml);
        if (fallbackAudio != null && fallbackAudio.isNotEmpty) {
          representations.add(DashRepresentation(
            id: 'audio_fallback',
            baseUrl: fallbackAudio,
            bandwidth: 128000,
            width: 0,
            height: 0,
            contentType: 'audio',
            qualityLabel: 'Audio',
          ));
        }
      } else {
        // Video regex fallback
        final videoRepPattern = RegExp(
          r'<Representation[^>]*?(?:width=["\x27](\d+)["\x27]|height=["\x27](\d+)["\x27]|contentType=["\x27]video["\x27]|video)[^>]*>([\s\S]*?)<\/Representation>',
          caseSensitive: false,
        );
        for (final match in videoRepPattern.allMatches(cleanedXml)) {
          final h = int.tryParse(match.group(2) ?? '0') ?? 0;
          final w = int.tryParse(match.group(1) ?? '0') ?? 0;
          final block = match.group(3) ?? '';
          final urlMatch = RegExp(
            r'<(?:BaseURL|baseURL|baseurl)[^>]*>([\s\S]*?)<\/(?:BaseURL|baseURL|baseurl)>',
            caseSensitive: false,
          ).firstMatch(block);
          if (urlMatch != null) {
            final url = urlMatch.group(1)?.trim().replaceAll('&amp;', '&');
            if (url != null && url.isNotEmpty) {
              representations.add(DashRepresentation(
                id: 'video_${representations.length}',
                baseUrl: url,
                bandwidth: 1000000,
                width: w,
                height: h,
                contentType: 'video',
                qualityLabel: h > 0 ? '${h}p' : 'Video',
              ));
            }
          }
        }
      }
    }

    return representations;
  }

  static String? extractTitle(String xmlContent) {
    final regexMatch = _rootTitlePattern.firstMatch(xmlContent);
    if (regexMatch != null) {
      final text = regexMatch.group(1)?.trim() ?? '';
      if (text.isNotEmpty) return text;
    }
    try {
      final cleaned = sanitizeMpd(xmlContent);
      final document = XmlDocument.parse(cleaned);
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
      final cleaned = sanitizeMpd(preStripped);
      final document = XmlDocument.parse(cleaned);
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
      final cleaned = sanitizeMpd(originalXml);
      final doc = XmlDocument.parse(cleaned);
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
    final codecs = el.getAttribute('codecs')?.toLowerCase() ?? '';
    if (codecs.startsWith('mp4a') || codecs.startsWith('aac') || codecs.startsWith('opus')) return 'audio';
    if (codecs.startsWith('avc') || codecs.startsWith('hev') || codecs.startsWith('hvc') || codecs.startsWith('vp')) return 'video';
    if (el.findElements('AudioChannelConfiguration').isNotEmpty) return 'audio';

    for (final rep in el.findElements('Representation')) {
      final repDirect = rep.getAttribute('contentType')?.toLowerCase();
      if (repDirect == 'video' || repDirect == 'audio') return repDirect!;
      final mime = rep.getAttribute('mimeType')?.toLowerCase() ?? '';
      if (mime.startsWith('video')) return 'video';
      if (mime.startsWith('audio')) return 'audio';
      final repCodecs = rep.getAttribute('codecs')?.toLowerCase() ?? '';
      if (repCodecs.startsWith('avc') ||
          repCodecs.startsWith('hev') ||
          repCodecs.startsWith('hvc') ||
          repCodecs.startsWith('vp') ||
          repCodecs.startsWith('mp4v')) {
        return 'video';
      }
      if (repCodecs.startsWith('mp4a') ||
          repCodecs.startsWith('aac') ||
          repCodecs.startsWith('opus') ||
          repCodecs.startsWith('vorbis') ||
          repCodecs.startsWith('ac-3') ||
          repCodecs.startsWith('ec-3') ||
          repCodecs.startsWith('flac')) {
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
    for (final child in el.children.whereType<XmlElement>()) {
      if (child.name.local.toLowerCase() == 'baseurl') {
        final text = child.innerText.trim();
        if (text.isNotEmpty) return text;
      }
    }
    return '';
  }
}
