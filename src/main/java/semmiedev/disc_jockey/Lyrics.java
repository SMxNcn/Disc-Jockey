package semmiedev.disc_jockey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed .lrc file: timestamped lines sorted by time.
 * <p>
 * Times are in milliseconds and are compared against the song position, which runs on the song's
 * own clock (see {@link SongPlayer#getSongElapsedSeconds()}). Because that clock advances at the
 * playback speed, lyrics automatically follow the speed setting without any extra scaling.
 * <p>
 * The raw file is kept as well - its lines, its charset and whether it started with a byte order
 * mark - because the offset buttons of the song selection screen rewrite it. A shift is always
 * applied to the timestamps <em>as they were read</em> plus the accumulated offset and never to the
 * already rewritten file, so pressing the buttons repeatedly accumulates the offset instead of
 * compounding it, and the file always matches {@link #offsetMillis()}.
 */
public final class Lyrics {
    public record Line(long timeMs, String text) {
    }

    /** One line of the file: its text without the terminator, plus the terminator that followed. */
    private record RawLine(String content, String terminator) {
    }

    /** The decoded file together with what is needed to encode it again. */
    private record Source(String text, Charset charset, boolean byteOrderMark) {
    }

    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:([.:])(\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TIMESTAMP = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:([.:])(\\d{1,3}))?>");
    private static final Pattern METADATA = Pattern.compile("\\[(ti|ar|al|by|au|re|ve|length):[^]]*]", Pattern.CASE_INSENSITIVE);
    /** Files in the wild are either UTF-8 or GBK; everything else is decoded as GB18030. */
    private static final Charset GB18030 = Charset.forName("GB18030");

    private final Path file;
    private final Charset charset;
    private final boolean byteOrderMark;
    /** The lines of the file exactly as they were read, so a rewrite starts from the original times. */
    private final List<RawLine> rawLines;
    /** Value of the file's own {@code [offset:...]} tag, which lrc players apply on top. */
    private final long tagOffsetMillis;
    /** Offset the user added with the offset buttons, on top of the tag. */
    private long offsetMillis;

    private List<Line> lines;

    private Lyrics(Path file, Charset charset, boolean byteOrderMark, List<RawLine> rawLines, long tagOffsetMillis) {
        this.file = file;
        this.charset = charset;
        this.byteOrderMark = byteOrderMark;
        this.rawLines = rawLines;
        this.tagOffsetMillis = tagOffsetMillis;
        this.lines = List.of();
    }

    /** @return the parsed lyrics, or null when the file holds no usable lines. */
    public static Lyrics parse(Path file) throws IOException {
        Source source = read(file);

        long tagOffset = 0;
        Matcher offsetMatcher = OFFSET.matcher(source.text());
        if (offsetMatcher.find()) {
            try {
                tagOffset = Long.parseLong(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // Malformed offset, just ignore it.
            }
        }

        Lyrics lyrics = new Lyrics(file, source.charset(), source.byteOrderMark(), splitLines(source.text()), tagOffset);
        lyrics.rebuild();
        return lyrics.lines.isEmpty() ? null : lyrics;
    }

    /**
     * Turns the raw lines into the sorted playback times, applying the tag offset and everything the
     * user added with the buttons. Always recomputed from the raw text, which is what keeps repeated
     * shifts idempotent.
     */
    private void rebuild() {
        List<Line> parsed = new ArrayList<>();
        for (RawLine rawLine : rawLines) {
            String line = METADATA.matcher(rawLine.content()).replaceAll("");
            Matcher matcher = TIMESTAMP.matcher(line);
            // A line may carry several timestamps for the same text, so strip them all first. Word
            // timestamps of an enhanced lrc are part of the text and never timed.
            String text = WORD_TIMESTAMP.matcher(matcher.replaceAll("")).replaceAll("").trim();
            if (text.isEmpty()) continue;
            matcher.reset();
            while (matcher.find()) {
                parsed.add(new Line(Math.max(0, timestamp(matcher) + tagOffsetMillis + offsetMillis), text));
            }
        }
        parsed.sort(Comparator.comparingLong(Line::timeMs));
        this.lines = parsed;
    }

    /**
     * Moves every timestamp of the file by {@code deltaMillis} and writes the result back, so the
     * change survives a reload of the song list. Repeated calls accumulate; a failed write leaves
     * both the file and this object untouched.
     *
     * @return the offset that is applied to the file now
     */
    public long shiftBy(long deltaMillis) throws IOException {
        long previous = offsetMillis;
        offsetMillis += deltaMillis;
        rebuild();
        try {
            write();
        } catch (IOException exception) {
            // Put the preview back in step with the file that is still on disk.
            offsetMillis = previous;
            rebuild();
            throw exception;
        }
        return offsetMillis;
    }

    /** Offset in milliseconds this file has been moved by the offset buttons, tag not included. */
    public long offsetMillis() {
        return offsetMillis;
    }

    /** The .lrc file these lyrics were read from. */
    public Path file() {
        return file;
    }

    private static long timestamp(Matcher matcher) {
        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        String fraction = matcher.group(4);
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            millis = switch (fraction.length()) {
                case 1 -> Long.parseLong(fraction) * 100;   // [mm:ss.f] tenths
                case 2 -> Long.parseLong(fraction) * 10;    // [mm:ss.ff] hundredths
                default -> Long.parseLong(fraction.substring(0, 3)); // [mm:ss.fff] milliseconds
            };
        }
        return minutes * 60_000L + seconds * 1_000L + millis;
    }

    private static Source read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        boolean byteOrderMark = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        int start = byteOrderMark ? 3 : 0;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start))
                    .toString();
            return new Source(text, StandardCharsets.UTF_8, byteOrderMark);
        } catch (CharacterCodingException exception) {
            // A lot of lyrics files in the wild are still GBK encoded. The byte order mark is part
            // of the decoded text here, exactly as the parser has always treated it.
            return new Source(GB18030.decode(ByteBuffer.wrap(bytes)).toString(), GB18030, false);
        }
    }

    /**
     * Splits into lines that keep their own terminator, so writing the file back reproduces the line
     * endings and the presence or absence of a final newline.
     */
    private static List<RawLine> splitLines(String content) {
        List<RawLine> result = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < content.length()) {
            char character = content.charAt(index);
            if (character == '\n') {
                result.add(new RawLine(content.substring(start, index), "\n"));
            } else if (character == '\r') {
                String terminator = index + 1 < content.length() && content.charAt(index + 1) == '\n' ? "\r\n" : "\r";
                result.add(new RawLine(content.substring(start, index), terminator));
                index += terminator.length();
                start = index;
                continue;
            } else {
                index++;
                continue;
            }
            index++;
            start = index;
        }
        if (start < content.length() || result.isEmpty()) result.add(new RawLine(content.substring(start), ""));
        return result;
    }

    /** Writes the file back with every timestamp moved by the accumulated offset. */
    private void write() throws IOException {
        byte[] bytes = encode(shiftedContent());
        Path target = file.toAbsolutePath();
        // Write next to the original and move it over it, so a failure can never leave a truncated
        // lyrics file behind.
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] encode(String content) {
        byte[] encoded = content.getBytes(charset);
        if (!byteOrderMark) return encoded;
        byte[] bytes = new byte[encoded.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(encoded, 0, bytes, 3, encoded.length);
        return bytes;
    }

    private String shiftedContent() {
        StringBuilder content = new StringBuilder();
        for (RawLine rawLine : rawLines) {
            String line = shiftedTimestamps(rawLine.content(), TIMESTAMP, '[', ']');
            // Word timestamps of an enhanced lrc are absolute times as well and have to follow the
            // line timestamps, otherwise the file would drift apart. The text around them and the
            // line terminator are copied verbatim.
            content.append(shiftedTimestamps(line, WORD_TIMESTAMP, '<', '>')).append(rawLine.terminator());
        }
        return content.toString();
    }

    /**
     * Moves every match of one timestamp pattern. The {@code [offset:...]} tag cannot match the
     * timestamp pattern - it has no digits before the colon - so it is always left untouched, and so
     * is everything else on the line.
     */
    private String shiftedTimestamps(String line, Pattern pattern, char open, char close) {
        Matcher matcher = pattern.matcher(line);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String shifted = formatTimestamp(matcher, timestamp(matcher) + offsetMillis, open, close);
            matcher.appendReplacement(result, Matcher.quoteReplacement(shifted));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Writes one timestamp back in the shape it was read in: the same number of digits for minutes
     * and seconds and the same fractional precision, so a file that only uses whole seconds does not
     * suddenly grow milliseconds. Rounding happens before the value is split up, so a carry moves
     * into the seconds correctly, and negative results are clamped to zero.
     */
    private static String formatTimestamp(Matcher matcher, long shiftedMillis, char open, char close) {
        long millis = Math.max(0, shiftedMillis);
        String fraction = matcher.group(4);
        long step = switch (fraction == null ? 0 : fraction.length()) {
            case 0 -> 1000; // [mm:ss] only: the nearest full second is all the file can express
            case 1 -> 100;
            case 2 -> 10;
            default -> 1;
        };
        millis = Math.round(millis / (double) step) * step;
        long totalSeconds = millis / 1000;
        StringBuilder result = new StringBuilder().append(open)
                .append(pad(totalSeconds / 60, matcher.group(1).length()))
                .append(':')
                .append(pad(totalSeconds % 60, matcher.group(2).length()));
        if (fraction != null) {
            // The separator is kept as it was, '.' in normal files and ':' in a few older ones.
            result.append(matcher.group(3)).append(pad(millis % 1000 / step, fraction.length()));
        }
        return result.append(close).toString();
    }

    private static String pad(long value, int digits) {
        // Locale.ROOT keeps the digits ascii whatever language the game runs in.
        return String.format(Locale.ROOT, "%0" + digits + "d", value);
    }

    public int size() {
        return lines.size();
    }

    public Line line(int index) {
        return lines.get(index);
    }

    /** Index of the last line at or before the given song time, or -1 before the first line. */
    public int indexAt(long songMs) {
        int low = 0;
        int high = lines.size() - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs() <= songMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }
}
