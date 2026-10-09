package dev.sora.protohax.relay.netty.log;

import android.util.Log;

import dev.sora.protohax.BuildConfig;
import dev.sora.protohax.util.CircularBuffer;
import io.netty.util.internal.logging.AbstractInternalLogger;

public class NettyLogger extends AbstractInternalLogger {

    public static final String TAG = "ProtoHax";

    private static final CircularBuffer logs = new CircularBuffer(250);

    /**
     * Loggers whose debug messages are worth having in a release build.
     *
     * The Bedrock library reports its most important failures at debug level only, and a release build
     * of this app used to throw all of them away:
     *
     *   BedrockPacketCodec  "Error encoding packet {}" / "Failed to decode packet"
     *   BedrockCodec        "<Serializer> still has <n> bytes to read!" - a serializer that did not
     *                       read the whole packet, which is what a codec that does not match the
     *                       server's protocol version looks like from the inside
     *   BedrockPeer         "Encryption enabled for {}"
     *   BedrockSession      "Unhandled packet for {}:{}: {}"
     *
     * "Error encoding packet" is the message of a packet the relay never sends: both
     * {@code BedrockPeer.sendPacketImmediately} and {@code BedrockPeer.flushPacketQueue} ignore the
     * write promise, so such a packet disappears without anybody being told. A session that died
     * right after its Xbox login ("login success", then nothing until the game gave up waiting for
     * the answer to its login) cannot be diagnosed while that message is hidden.
     *
     * Deliberately narrow. {@code BuildConfig.DEBUG} for every logger is what netty itself would
     * produce - allocator and pipeline chatter, hundreds of lines per second - and the whole
     * namespace is not much better: the per-version codec helpers debug-log every item they cannot
     * resolve ("No ItemDefinition for runtimeId {}"), which during gameplay is one line per item per
     * inventory packet and would push the messages above out of the 250 line buffer again. These four
     * classes log a handful of lines per session instead, and only when something is wrong.
     *
     * The RakNet layer needs no entry here: what matters in it ("Tried to write packet from wrong
     * thread", "Exception thrown in RakNet pipeline", the packet limit warnings) is already logged at
     * warn or error level, which this logger always records.
     */
    private static final String[] DEBUG_IN_RELEASE = {
            "org.cloudburstmc.protocol.bedrock.netty.",
            "org.cloudburstmc.protocol.bedrock.BedrockPeer",
            "org.cloudburstmc.protocol.bedrock.BedrockSession",
            "org.cloudburstmc.protocol.bedrock.codec.BedrockCodec",
    };

    public static String getLogs() {
        final StringBuilder sb = new StringBuilder();

        for (String log : logs.getArray()) {
            if (log != null) {
                sb.append(log);
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    public static void clearLogs() {
        logs.wipe();
    }

    private static void log(final String log) {
        if (log.contains("\n")) {
            for (String s : log.split("\n")) {
                logs.add(s);
            }
        } else {
            logs.add(log);
        }
    }

    protected NettyLogger(String name) {
        super(name);
    }

    /** Whether the debug messages of this logger survive a release build (see {@link #DEBUG_IN_RELEASE}). */
    private boolean debugInRelease() {
        for (String prefix : DEBUG_IN_RELEASE) {
            if (name().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public void trace(String s) {

    }

    @Override
    public void trace(String s, Object o) {

    }

    @Override
    public void trace(String s, Object o, Object o1) {

    }

    @Override
    public void trace(String s, Object... objects) {

    }

    @Override
    public void trace(String s, Throwable throwable) {

    }

    @Override
    public boolean isDebugEnabled() {
        return BuildConfig.DEBUG || debugInRelease();
    }

    @Override
    public void debug(String s) {
        log(s);
        Log.d(TAG, s);
    }

    @Override
    public void debug(String s, Object o) {
        s = s + ", " + o;
        log(s);
        Log.d(TAG, s);
    }

    @Override
    public void debug(String s, Object o, Object o1) {
        s = s + ", " + o + ", " + o1;
        log(s);
        Log.d(TAG, s);
    }

    @Override
    public void debug(String s, Object... objects) {
        final StringBuilder sb = new StringBuilder();
        sb.append(s);
        sb.append(", ");
        for (Object o : objects) {
            sb.append(o);
            sb.append(", ");
        }
        sb.setLength(sb.length()-2);
        s = sb.toString();
        log(s);
        Log.d(TAG, s);
    }

    @Override
    public void debug(String s, Throwable throwable) {
        log(s + ": " + throwable);
        Log.d(TAG, s, throwable);
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public void info(String s) {
        log(s);
        Log.i(TAG, s);
    }

    @Override
    public void info(String s, Object o) {
        s = s + ", " + o;
        log(s);
        Log.i(TAG, s);
    }

    @Override
    public void info(String s, Object o, Object o1) {
        s = s + ", " + o + ", " + o1;
        log(s);
        Log.i(TAG, s);
    }

    @Override
    public void info(String s, Object... objects) {
        final StringBuilder sb = new StringBuilder();
        sb.append(s);
        sb.append(", ");
        for (Object o : objects) {
            sb.append(o);
            sb.append(", ");
        }
        sb.setLength(sb.length()-2);
        s = sb.toString();
        log(s);
        Log.i(TAG, s);
    }

    @Override
    public void info(String s, Throwable throwable) {
        log(s + ": " + throwable);
        Log.i(TAG, s, throwable);
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void warn(String s) {
        log(s);
        Log.w(TAG, s);
    }

    @Override
    public void warn(String s, Object o) {
        s = s + ", " + o;
        log(s);
        Log.w(TAG, s);
    }

    @Override
    public void warn(String s, Object o, Object o1) {
        s = s + ", " + o + ", " + o1;
        log(s);
        Log.w(TAG, s);
    }

    @Override
    public void warn(String s, Object... objects) {
        final StringBuilder sb = new StringBuilder();
        sb.append(s);
        sb.append(", ");
        for (Object o : objects) {
            sb.append(o);
            sb.append(", ");
        }
        sb.setLength(sb.length()-2);
        s = sb.toString();
        log(s);
        Log.w(TAG, s);
    }

    @Override
    public void warn(String s, Throwable throwable) {
        log(s + ": " + throwable);
        Log.w(TAG, s, throwable);
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public void error(String s) {
        log(s);
        Log.e(TAG, s);
    }

    @Override
    public void error(String s, Object o) {
        s = s + ", " + o;
        log(s);
        Log.e(TAG, s);
    }

    @Override
    public void error(String s, Object o, Object o1) {
        s = s + ", " + o + ", " + o1;
        log(s);
        Log.e(TAG, s);
    }

    @Override
    public void error(String s, Object... objects) {
        final StringBuilder sb = new StringBuilder();
        sb.append(s);
        sb.append(", ");
        for (Object o : objects) {
            sb.append(o);
            sb.append(", ");
        }
        sb.setLength(sb.length()-2);
        s = sb.toString();
        log(s);
        Log.e(TAG, s);
    }

    @Override
    public void error(String s, Throwable throwable) {
        log(s + ": " + throwable);
        Log.e(TAG, s, throwable);
    }
}
