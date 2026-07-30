package javax.lang.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal Android-compatible stand-in for the JDK's javax.lang.model.SourceVersion.
 *
 * Android's runtime does not ship javax.lang.model.*, but graphhopper-core uses
 * SourceVersion to validate encoded-value names. Only the static identifier/name
 * helpers are actually exercised; the enum constants exist to keep the public API
 * shape close enough that any overload resolution still works.
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11,
    RELEASE_17,
    RELEASE_21;

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            // literals are also rejected as identifiers
            "true", "false", "null",
            // contextual / newer reserved words
            "_", "var", "record", "sealed", "permits", "yield", "non-sealed"
    ));

    public static SourceVersion latest() {
        return RELEASE_21;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_21;
    }

    public static boolean isKeyword(CharSequence s) {
        return KEYWORDS.contains(s.toString());
    }

    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        return isKeyword(s);
    }

    public static boolean isIdentifier(CharSequence name) {
        String id = name.toString();
        if (id.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(id.codePointAt(0))) {
            return false;
        }
        int i = Character.charCount(id.codePointAt(0));
        while (i < id.length()) {
            int cp = id.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        String id = name.toString();
        for (String segment : id.split("\\.", -1)) {
            if (!isIdentifier(segment) || isKeyword(segment)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isName(CharSequence name, SourceVersion version) {
        return isName(name);
    }
}
