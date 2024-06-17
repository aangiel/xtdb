package xtdb.util

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

object StringUtil {
    /**
     * Returns the 1-based position of the given (needle) bytes in the haystack. 0 if not found.
     * This is equivalent to sql POSITION on binary.
     */
    @JvmStatic
    fun SqlBinPosition(needle: ByteBuffer, haystack: ByteBuffer): Int {
        if (needle.remaining() == 0) return 1
        var i = 0
        var j = 0
        while (true) {
            if (j == needle.remaining()) return i + 1
            if (i + j == haystack.remaining()) return 0
            if (haystack[i + j] == needle[j]) {
                j++
            } else {
                i++
                j = 0
            }
        }
    }

    @JvmStatic
    fun isUtf8Char(b: Byte): Boolean {
        return (b.toInt() and 0xc0) != 0x80
    }

    /**
     * In our SQL dialect &amp; arrow we operate always on utf8.
     * Java strings are utf-16 backed, and java chars cannot represent all utf-8 characters in one char - therefore
     * we do not use string length or anything like it, so that one character == one code point.
     */
    @JvmStatic
    @JvmOverloads
    fun utf8Length(buf: ByteBuffer, start: Int = 0, end: Int = buf.remaining()): Int {
        var len = 0
        for (i in start until end) {
            if (isUtf8Char(buf[i])) {
                len++
            }
        }
        return len
    }

    /**
     * Returns the 1-based codepoint position of the needle in the haystack. 0 if not found.
     * This is equivalent to sql POSITION on characters.
     */
    @JvmStatic
    fun sqlUtf8Position(needle: ByteBuffer, haystack: ByteBuffer): Int {
        val bpos = SqlBinPosition(needle, haystack)
        if (bpos == 0) return 0
        return utf8Length(haystack, 0, bpos - 1) + 1
    }

    @JvmStatic
    fun sqlUtf8Substring(target: ByteBuffer, pos: Int, len: Int, useLen: Boolean): ByteBuffer {
        var len = len
        require(!(useLen && len < 0)) { "Negative substring length" }

        val zeroPos = pos - 1
        len = if (zeroPos < 0) max(0.0, (len + zeroPos).toDouble()).toInt() else len

        if (useLen && len == 0) {
            return ByteBuffer.allocate(0)
        }

        val startCodepoint = max(zeroPos.toDouble(), 0.0).toInt()
        var startIndex = 0

        var cp = -1
        run {
            var i = target.position()
            while (i < target.remaining() && cp < startCodepoint) {
                if (isUtf8Char(target[i])) {
                    cp++
                }
                startIndex = i
                i++
            }
        }

        if (cp < startCodepoint) {
            return ByteBuffer.allocate(0)
        }

        if (!useLen) {
            val ret = target.duplicate()
            ret.position(startIndex)
            return ret
        }

        var charsConsumed = 0
        for (i in startIndex until target.remaining()) {
            if (charsConsumed == len && isUtf8Char(target[i])) {
                val ret = target.duplicate()
                ret.position(startIndex)
                ret.limit(i)
                return ret
            }

            if (isUtf8Char(target[i])) {
                charsConsumed++
            }
        }

        val ret = target.duplicate()
        ret.position(startIndex)
        return ret
    }

    @JvmStatic
    fun sqlBinSubstring(target: ByteBuffer, pos: Int, len: Int, useLen: Boolean): ByteBuffer {
        var len = len
        require(!(useLen && len < 0)) { "Negative substring length" }

        val zeroPos = pos - 1
        len = if (zeroPos < 0) max(0.0, (len + zeroPos).toDouble()).toInt() else len
        val startIndex = max(0.0, zeroPos.toDouble()).toInt()

        if (useLen && len == 0) {
            return ByteBuffer.allocate(0)
        }

        if (startIndex >= target.remaining()) {
            return ByteBuffer.allocate(0)
        }

        if (!useLen) {
            val ret = target.slice()
            ret.position(startIndex)
            return ret
        }

        val limit = min((startIndex + len).toDouble(), target.remaining().toDouble()).toInt()

        val ret = target.slice()
        ret.position(startIndex)
        ret.limit(limit)
        return ret
    }

    @JvmStatic
    fun sqlUtf8Overlay(target: ByteBuffer, placing: ByteBuffer, start: Int, replaceLength: Int): ByteBuffer {
        val s1 = sqlUtf8Substring(target, 1, start - 1, true)
        val s2 = sqlUtf8Substring(target, start + replaceLength, -1, false)

        val newBuf = ByteBuffer.allocate(s1.remaining() + s2.remaining() + placing.remaining())
        newBuf.put(s1)
        newBuf.put(placing.duplicate())
        newBuf.put(s2)
        newBuf.position(0)
        return newBuf
    }

    @JvmStatic
    fun sqlBinOverlay(target: ByteBuffer, placing: ByteBuffer, start: Int, replaceLength: Int): ByteBuffer {
        val s1 = sqlBinSubstring(target, 1, start - 1, true)
        val s2 = sqlBinSubstring(target, start + replaceLength, -1, false)

        val newBuf = ByteBuffer.allocate(s1.remaining() + s2.remaining() + placing.remaining())
        newBuf.put(s1)
        newBuf.put(placing.duplicate())
        newBuf.put(s2)
        newBuf.position(0)
        return newBuf
    }
}
