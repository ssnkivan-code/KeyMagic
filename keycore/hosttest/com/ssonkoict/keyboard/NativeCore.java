package com.ssonkoict.keyboard;

/**
 * Desktop test of the JNI bridge: same native method signatures as the Kotlin
 * `object NativeCore` (all static). Run with keycore/hosttest/run.sh
 */
public final class NativeCore {
    static native long create();
    static native void destroy(long h);
    static native int press(long h, int row, int col, long now);
    static native int keyLabel(long h, int row, int col);
    static native void setNumberRow(long h, boolean on);
    static native int rowCount(long h);
    static native int loadDictionary(long h, String data);
    static native void loadEmoji(long h, String data);
    static native String suggest(long h, String before, int max);
    static native String suggestEmoji(long h, String before, int max);
    static native void learn(long h, String before);
    static native void recordEmoji(long h, String e);
    static native String frequentEmojis(long h, int max);
    static native String exportUser(long h);
    static native String clipAdd(long h, int kind, String content, long time);
    static native int clipCount(long h);
    static native String clipContent(long h, int i);
    static native int clipKind(long h, int i);
    static native String clipExport(long h);
    static native void clipImport(long h, String data);
    static native String findOtp(String text);

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok  " + what);
    }

    public static void main(String[] args) {
        System.loadLibrary("keycore");
        long h = create();

        setNumberRow(h, true);
        check(rowCount(h) == 5, "number row adds a row");
        check(press(h, 0, 0, 0) == '1', "number row types 1");

        int n = loadDictionary(h, "hello\t100\nhelp\t200\nkampala\t5\n");
        check(n == 3, "dictionary loads (" + n + " words)");
        check(suggest(h, "Hel", 3).equals("Help\nHello"), "suggest via JNI: " + suggest(h, "Hel", 3).replace("\n", "|"));

        loadEmoji(h, "smileys\t😂\tface with tears of joy\tlaugh\npeople\t❤️\tred heart\tlove\n");
        check(suggestEmoji(h, "I love", 2).equals("❤️"), "emoji survives UTF-16 round trip");
        recordEmoji(h, "👋🏾");
        check(frequentEmojis(h, 3).equals("👋🏾"), "skin-tone emoji round trip");

        learn(h, "webale nnyo ");
        check(exportUser(h).contains("nnyo"), "learning via JNI");

        for (int i = 0; i < 10; i++) clipAdd(h, 0, "item " + i, i);
        clipAdd(h, 1, "/data/clip/img.png", 10);
        check(clipCount(h) == 10, "clipboard keeps 10");
        check(clipKind(h, 0) == 1 && clipContent(h, 0).equals("/data/clip/img.png"), "newest is the image");
        String freed = clipAdd(h, 0, "Ssonko 😀\nline two", 11);
        check(freed.isEmpty() && clipContent(h, 0).equals("Ssonko 😀\nline two"), "multi-line text with emoji");
        String data = clipExport(h);
        long h2 = create();
        clipImport(h2, data);
        check(clipCount(h2) == 10 && clipContent(h2, 0).equals("Ssonko 😀\nline two"), "clipboard export/import");
        destroy(h2);

        check(findOtp("G-482913 is your Google verification code.").equals("482913"), "OTP found");
        check(findOtp("Received UGX 50,000. Balance UGX 120,500").isEmpty(), "money message ignored");
        check(findOtp(null).isEmpty(), "null string is safe");

        destroy(h);
        System.out.println("All JNI checks passed.");
    }
}
