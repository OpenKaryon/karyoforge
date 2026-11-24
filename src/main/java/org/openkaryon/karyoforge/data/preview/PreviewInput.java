package org.openkaryon.karyoforge.data.preview;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A bounded byte cursor. Page cursors refer to record boundaries in the original file. */
public final class PreviewInput implements Closeable {
    private static final long MAX_REQUEST_BYTES = 64L * 1024 * 1024;
    private final InputStream source;
    private final long start;
    private final Runnable cancellation;
    private long offset;
    private int lookahead = -2;
    private int utf8Remaining;
    private int utf8Min = 0x80;
    private int utf8Max = 0xbf;

    PreviewInput(Path path, long offset, Runnable cancellation) throws IOException {
        this.start = offset;
        this.offset = offset;
        this.cancellation = cancellation;
        var channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            channel.position(offset);
            source = new BufferedInputStream(Channels.newInputStream(channel), 16 * 1024);
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    public long offset() { return offset; }

    public int peek() throws IOException {
        if (lookahead == -2) {
            if ((offset - start) % 8192 == 0) cancellation.run();
            lookahead = source.read();
            if (lookahead == -1 && utf8Remaining != 0) throw error("Incomplete UTF-8 character");
        }
        return lookahead;
    }

    public int take() throws IOException {
        int value = peek();
        if (value == -1) return -1;
        if (offset - start >= MAX_REQUEST_BYTES) throw error("Preview reached its 64 MiB reading limit");
        validate(value);
        lookahead = -2;
        offset++;
        return value;
    }

    void skipBom() throws IOException {
        if (offset == 0 && peek() == 0xef) {
            // A leading 0xef also occurs in ordinary UTF-8 text. Read ahead without consuming it.
            source.mark(2);
            int second = source.read();
            int third = source.read();
            source.reset();
            if (second == 0xbb && third == 0xbf) {
                take();
                take();
                take();
            }
        }
    }

    public void endLine() throws IOException {
        int end = take();
        if (end == '\r' && peek() == '\n') take();
    }

    public IOException error(String message) {
        return new IOException(message + " at byte " + offset + ".");
    }

    private void validate(int value) throws IOException {
        if (utf8Remaining > 0) {
            if (value < utf8Min || value > utf8Max) throw error("Invalid UTF-8 continuation byte");
            utf8Remaining--;
            utf8Min = 0x80;
            utf8Max = 0xbf;
        } else if (value == 0) {
            throw error("NUL is not supported in a text preview");
        } else if (value < 0x80) {
            return;
        } else if (value >= 0xc2 && value <= 0xdf) {
            utf8Remaining = 1;
        } else if (value >= 0xe0 && value <= 0xef) {
            utf8Remaining = 2;
            if (value == 0xe0) utf8Min = 0xa0;
            if (value == 0xed) utf8Max = 0x9f;
        } else if (value >= 0xf0 && value <= 0xf4) {
            utf8Remaining = 3;
            if (value == 0xf0) utf8Min = 0x90;
            if (value == 0xf4) utf8Max = 0x8f;
        } else {
            throw error("Invalid UTF-8 byte");
        }
    }

    @Override public void close() throws IOException { source.close(); }
}
