class DashXmlSanitizer {
  static final RegExp _bareAmpersand =
      RegExp(r'&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)');
  static String sanitize(String xml) {
    return xml.replaceAll(_bareAmpersand, '&amp;');
  }
}
