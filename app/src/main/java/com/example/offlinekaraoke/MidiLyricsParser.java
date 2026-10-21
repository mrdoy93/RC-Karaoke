package com.example.offlinekaraoke;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Lightweight Standard MIDI File parser for embedded karaoke lyric events. */
public final class MidiLyricsParser {
    private static final int DEFAULT_TEMPO_MICROS = 500_000;

    private MidiLyricsParser() {}

    public static final class TimedLine {
        private final long timeMs;
        private final String text;
        private final List<TimedSegment> segments;

        TimedLine(long timeMs, String text, List<TimedSegment> segments) {
            this.timeMs = timeMs;
            this.text = text;
            this.segments = segments;
        }

        public long getTimeMs() {
            return timeMs;
        }

        public String getText() {
            return text;
        }

        public List<TimedSegment> getSegments() {
            return segments;
        }
    }

    public static final class TimedSegment {
        private final long timeMs;
        private final int endCharacter;

        TimedSegment(long timeMs, int endCharacter) {
            this.timeMs = timeMs;
            this.endCharacter = endCharacter;
        }

        public long getTimeMs() {
            return timeMs;
        }

        public int getEndCharacter() {
            return endCharacter;
        }
    }

    private static final class RawLyric {
        final long tick;
        final String text;

        RawLyric(long tick, String text) {
            this.tick = tick;
            this.text = text;
        }
    }

    private static final class RawLine {
        final long tick;
        final String text;
        final List<RawSegment> segments;

        RawLine(long tick, String text, List<RawSegment> segments) {
            this.tick = tick;
            this.text = text;
            this.segments = segments;
        }
    }

    private static final class RawSegment {
        final long tick;
        final int endCharacter;

        RawSegment(long tick, int endCharacter) {
            this.tick = tick;
            this.endCharacter = endCharacter;
        }
    }

    private static final class TempoEvent {
        final long tick;
        final int microsPerQuarter;
        final int order;

        TempoEvent(long tick, int microsPerQuarter, int order) {
            this.tick = tick;
            this.microsPerQuarter = microsPerQuarter;
            this.order = order;
        }
    }

    private static final class TempoPoint {
        final long tick;
        final double elapsedMicros;
        final int microsPerQuarter;

        TempoPoint(long tick, double elapsedMicros, int microsPerQuarter) {
            this.tick = tick;
            this.elapsedMicros = elapsedMicros;
            this.microsPerQuarter = microsPerQuarter;
        }
    }

    public static List<TimedLine> parse(File file) throws IOException {
        byte[] data = readFile(file);
        Cursor cursor = new Cursor(data);
        cursor.expect("MThd");
        int headerLength = cursor.readLength();
        if (headerLength < 6) throw new IOException("Invalid MIDI header length");
        cursor.readUnsignedShort(); // Format is not needed for lyric extraction.
        int trackCount = cursor.readUnsignedShort();
        int division = cursor.readUnsignedShort();
        cursor.skip(headerLength - 6);

        List<TempoEvent> tempos = new ArrayList<>();
        List<List<RawLyric>> lyricTracks = new ArrayList<>();
        List<List<RawLyric>> textTracks = new ArrayList<>();
        int tempoOrder = 0;

        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            cursor.expect("MTrk");
            int trackLength = cursor.readLength();
            int trackEnd = cursor.position + trackLength;
            if (trackEnd < cursor.position || trackEnd > data.length) {
                throw new IOException("Invalid MIDI track length");
            }

            List<RawLyric> trackLyrics = new ArrayList<>();
            List<RawLyric> trackText = new ArrayList<>();
            long tick = 0;
            int runningStatus = 0;

            while (cursor.position < trackEnd) {
                tick += cursor.readVariableLength();
                int first = cursor.readUnsignedByte();
                int status;
                boolean firstDataByteConsumed = false;

                if (first < 0x80) {
                    if (runningStatus == 0) throw new IOException("Missing MIDI running status");
                    status = runningStatus;
                    firstDataByteConsumed = true;
                } else {
                    status = first;
                    if (status >= 0x80 && status <= 0xEF) runningStatus = status;
                }

                if (status == 0xFF) {
                    int type = cursor.readUnsignedByte();
                    int length = cursor.readVariableLengthInt();
                    byte[] value = cursor.readBytes(length);
                    if (type == 0x05) {
                        String lyric = decodeText(value);
                        if (!lyric.isEmpty()) trackLyrics.add(new RawLyric(tick, lyric));
                    } else if (type == 0x01) {
                        // Some legacy karaoke files store their words in generic
                        // text events instead of the official lyric event type.
                        String text = decodeText(value);
                        if (!text.isEmpty()) trackText.add(new RawLyric(tick, text));
                    } else if (type == 0x51 && value.length == 3) {
                        int tempo = ((value[0] & 0xFF) << 16)
                            | ((value[1] & 0xFF) << 8)
                            | (value[2] & 0xFF);
                        if (tempo > 0) tempos.add(new TempoEvent(tick, tempo, tempoOrder++));
                    }
                } else if (status == 0xF0 || status == 0xF7) {
                    cursor.skip(cursor.readVariableLengthInt());
                    runningStatus = 0;
                } else if (status >= 0x80 && status <= 0xEF) {
                    int command = status & 0xF0;
                    int dataLength = (command == 0xC0 || command == 0xD0) ? 1 : 2;
                    cursor.skip(dataLength - (firstDataByteConsumed ? 1 : 0));
                } else {
                    throw new IOException("Unsupported MIDI event status");
                }
            }

            cursor.position = trackEnd;
            lyricTracks.add(trackLyrics);
            textTracks.add(trackText);
        }

        List<RawLyric> lyrics = Collections.emptyList();
        for (List<RawLyric> candidate : lyricTracks) {
            if (candidate.size() > lyrics.size()) lyrics = candidate;
        }
        if (lyrics.isEmpty()) {
            for (List<RawLyric> candidate : textTracks) {
                if (candidate.size() > lyrics.size()) lyrics = candidate;
            }
        }
        if (lyrics.isEmpty()) return Collections.emptyList();

        List<RawLine> lines = groupIntoLines(lyrics);
        if (lines.isEmpty()) return Collections.emptyList();
        return applyTiming(lines, tempos, division);
    }

    private static List<RawLine> groupIntoLines(List<RawLyric> lyrics) {
        List<RawLine> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<RawSegment> currentSegments = new ArrayList<>();
        long lineStartTick = -1;

        for (RawLyric lyric : lyrics) {
            String value = sanitizeLyricText(lyric.text);
            while (value.startsWith("/") || value.startsWith("\\")) {
                lineStartTick = flushLine(lines, current, currentSegments, lineStartTick);
                value = value.substring(1);
            }

            int segmentStart = 0;
            for (int index = 0; index <= value.length(); index++) {
                boolean atEnd = index == value.length();
                boolean lineBreak = !atEnd && (value.charAt(index) == '\r' || value.charAt(index) == '\n');
                if (!atEnd && !lineBreak) continue;

                String fragment = value.substring(segmentStart, index);
                if (!fragment.isEmpty() && (current.length() > 0 || !fragment.trim().isEmpty())) {
                    if (current.length() == 0) lineStartTick = lyric.tick;
                    current.append(fragment);
                    if (!fragment.trim().isEmpty()) {
                        currentSegments.add(new RawSegment(lyric.tick, current.length()));
                    }
                }

                if (lineBreak) {
                    lineStartTick = flushLine(lines, current, currentSegments, lineStartTick);
                    while (index + 1 < value.length()
                        && (value.charAt(index + 1) == '\r' || value.charAt(index + 1) == '\n')) {
                        index++;
                    }
                    segmentStart = index + 1;
                }
            }
        }

        flushLine(lines, current, currentSegments, lineStartTick);
        return lines;
    }

    private static String sanitizeLyricText(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\r' || character == '\n') {
                cleaned.append(character);
            } else if (character == '\t' || character == '\u00A0') {
                cleaned.append(' ');
            } else if (character != '\uFEFF' && !Character.isISOControl(character)) {
                cleaned.append(character);
            }
        }
        return cleaned.toString();
    }

    private static long flushLine(
        List<RawLine> lines,
        StringBuilder current,
        List<RawSegment> currentSegments,
        long startTick
    ) {
        String raw = current.toString();
        current.setLength(0);
        int leading = 0;
        while (leading < raw.length() && Character.isWhitespace(raw.charAt(leading))) leading++;
        int trailing = raw.length();
        while (trailing > leading && Character.isWhitespace(raw.charAt(trailing - 1))) trailing--;
        String cleaned = raw.substring(leading, trailing);
        if (!cleaned.isEmpty() && !isMetadata(cleaned)) {
            List<RawSegment> adjustedSegments = new ArrayList<>();
            int previousEnd = 0;
            for (RawSegment segment : currentSegments) {
                int adjustedEnd = Math.max(0, Math.min(cleaned.length(), segment.endCharacter - leading));
                if (adjustedEnd > previousEnd) {
                    adjustedSegments.add(new RawSegment(segment.tick, adjustedEnd));
                    previousEnd = adjustedEnd;
                }
            }
            if (previousEnd < cleaned.length()) {
                long finalTick = currentSegments.isEmpty() ? Math.max(0, startTick)
                    : currentSegments.get(currentSegments.size() - 1).tick;
                adjustedSegments.add(new RawSegment(finalTick, cleaned.length()));
            }
            lines.add(new RawLine(Math.max(0, startTick), cleaned, adjustedSegments));
        }
        currentSegments.clear();
        return -1;
    }

    private static boolean isMetadata(String text) {
        return text.length() >= 2 && text.charAt(0) == '@'
            && "TKLIV".indexOf(Character.toUpperCase(text.charAt(1))) >= 0;
    }

    private static List<TimedLine> applyTiming(
        List<RawLine> lines,
        List<TempoEvent> tempos,
        int division
    ) throws IOException {
        List<TimedLine> result = new ArrayList<>(lines.size());

        if ((division & 0x8000) != 0) {
            int signedFrames = (byte) ((division >> 8) & 0xFF);
            int ticksPerFrame = division & 0xFF;
            double framesPerSecond = signedFrames == -29 ? 29.97 : -signedFrames;
            if (framesPerSecond <= 0 || ticksPerFrame <= 0) {
                throw new IOException("Invalid SMPTE MIDI division");
            }
            double ticksPerSecond = framesPerSecond * ticksPerFrame;
            for (RawLine line : lines) {
                List<TimedSegment> segments = new ArrayList<>(line.segments.size());
                for (RawSegment segment : line.segments) {
                    segments.add(new TimedSegment(
                        Math.round((segment.tick * 1000.0) / ticksPerSecond),
                        segment.endCharacter
                    ));
                }
                result.add(new TimedLine(
                    Math.round((line.tick * 1000.0) / ticksPerSecond),
                    line.text,
                    Collections.unmodifiableList(segments)
                ));
            }
            return result;
        }

        int ticksPerQuarter = division & 0x7FFF;
        if (ticksPerQuarter <= 0) throw new IOException("Invalid MIDI PPQ division");
        tempos.sort(Comparator.comparingLong((TempoEvent event) -> event.tick).thenComparingInt(event -> event.order));

        List<TempoPoint> tempoPoints = new ArrayList<>();
        long previousTick = 0;
        int currentTempo = DEFAULT_TEMPO_MICROS;
        double elapsedMicros = 0;
        tempoPoints.add(new TempoPoint(0, 0, currentTempo));
        for (TempoEvent event : tempos) {
            elapsedMicros += ((event.tick - previousTick) * (double) currentTempo) / ticksPerQuarter;
            previousTick = event.tick;
            currentTempo = event.microsPerQuarter;
            if (!tempoPoints.isEmpty() && tempoPoints.get(tempoPoints.size() - 1).tick == event.tick) {
                tempoPoints.set(tempoPoints.size() - 1, new TempoPoint(event.tick, elapsedMicros, currentTempo));
            } else {
                tempoPoints.add(new TempoPoint(event.tick, elapsedMicros, currentTempo));
            }
        }

        for (RawLine line : lines) {
            List<TimedSegment> segments = new ArrayList<>(line.segments.size());
            for (RawSegment segment : line.segments) {
                segments.add(new TimedSegment(
                    tickToMilliseconds(segment.tick, tempoPoints, ticksPerQuarter),
                    segment.endCharacter
                ));
            }
            result.add(new TimedLine(
                tickToMilliseconds(line.tick, tempoPoints, ticksPerQuarter),
                line.text,
                Collections.unmodifiableList(segments)
            ));
        }
        return result;
    }

    private static long tickToMilliseconds(long tick, List<TempoPoint> points, int ticksPerQuarter) {
        int low = 0;
        int high = points.size() - 1;
        int selected = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (points.get(middle).tick <= tick) {
                selected = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        TempoPoint point = points.get(selected);
        double micros = point.elapsedMicros
            + ((tick - point.tick) * (double) point.microsPerQuarter) / ticksPerQuarter;
        return Math.round(micros / 1000.0);
    }

    private static String decodeText(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException ignored) {
            return new String(value, Charset.forName("windows-1252"));
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(file.length(), 1_048_576))) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static final class Cursor {
        private final byte[] data;
        int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        void expect(String marker) throws IOException {
            byte[] expected = marker.getBytes(StandardCharsets.US_ASCII);
            byte[] actual = readBytes(expected.length);
            for (int index = 0; index < expected.length; index++) {
                if (actual[index] != expected[index]) throw new IOException("Invalid MIDI chunk marker");
            }
        }

        int readUnsignedByte() throws IOException {
            if (position >= data.length) throw new IOException("Unexpected end of MIDI file");
            return data[position++] & 0xFF;
        }

        int readUnsignedShort() throws IOException {
            return (readUnsignedByte() << 8) | readUnsignedByte();
        }

        int readLength() throws IOException {
            long value = ((long) readUnsignedByte() << 24)
                | ((long) readUnsignedByte() << 16)
                | ((long) readUnsignedByte() << 8)
                | readUnsignedByte();
            if (value > Integer.MAX_VALUE) throw new IOException("MIDI chunk is too large");
            return (int) value;
        }

        long readVariableLength() throws IOException {
            long value = 0;
            // The SMF specification caps this at four bytes, but a few legacy
            // karaoke files contain harmless leading continuation bytes.
            for (int count = 0; count < 8; count++) {
                int next = readUnsignedByte();
                if (value > (Long.MAX_VALUE >> 7)) {
                    throw new IOException("MIDI variable-length value is too large");
                }
                value = (value << 7) | (next & 0x7F);
                if ((next & 0x80) == 0) return value;
            }
            throw new IOException("Invalid MIDI variable-length value");
        }

        int readVariableLengthInt() throws IOException {
            long value = readVariableLength();
            if (value > Integer.MAX_VALUE) throw new IOException("MIDI event is too large");
            return (int) value;
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || position + length < position || position + length > data.length) {
                throw new IOException("Unexpected end of MIDI file");
            }
            byte[] result = new byte[length];
            System.arraycopy(data, position, result, 0, length);
            position += length;
            return result;
        }

        void skip(int length) throws IOException {
            if (length < 0 || position + length < position || position + length > data.length) {
                throw new IOException("Unexpected end of MIDI file");
            }
            position += length;
        }
    }
}
