package io.pzstorm.storm.bullet.trace;

/**
 * Binary layout of a {@code .pzbt} trace (big-endian {@code DataOutputStream}; the whole file is
 * gzip-compressed when its name ends in {@code .gz}).
 *
 * <pre>
 * header  : magic "PZBT" | u16 version | u16 metaCount | (str key, str value)*
 *           | u16 sigCount | (u8 isUpcall, str name, str descriptor)*
 * events  : u8 tag | i64 seq | i64 threadId | payload
 *   CALL_BEGIN   : u16 sig | value(param_i) for every parameter
 *   CALL_END     : u16 sig | value(param_i) for every mutable (array/buffer) parameter, after the call
 *                  | u8 outcome (0 returned, 1 threw) | returned: value(ret) unless void
 *                                                    | threw   : str class, str message
 *   UPCALL_BEGIN : u16 sig | value(param_i)*
 *   UPCALL_END   : u16 sig | u8 outcome | value(ret) unless void | or str class, str message
 *   THREAD       : str threadName   (first event of a thread)
 *   NOTE         : str text         (recorder diagnostics)
 *   END          : (no payload)     (clean close; absent when the process died)
 * str     : i32 byteLength (-1 = null) | UTF-8 bytes
 * value   : BOOLEAN u8 | INT i32 | FLOAT i32 raw bits | STRING str
 *           | FLOAT_ARRAY / INT_ARRAY : i32 length (-1 = null) | i32 raw elements
 *           | BYTE_BUFFER : u8 flags (1 present, 2 direct, 4 little-endian) | i32 position | i32 limit
 *                           | i32 capacity | i32 n | n bytes (buffer content from index 0 to limit)
 * </pre>
 *
 * {@code seq} is a single global counter over all events, assigned under the writer lock, so the
 * file order is the order events happened. Nesting is per thread: a CALL_BEGIN on a thread whose
 * innermost open frame is an upcall is a call issued from inside that upcall, and vice versa.
 */
public final class TraceFormat {

    public static final int MAGIC = 0x505A4254; // "PZBT"
    public static final int VERSION = 1;

    public static final int CALL_BEGIN = 1;
    public static final int CALL_END = 2;
    public static final int UPCALL_BEGIN = 3;
    public static final int UPCALL_END = 4;
    public static final int THREAD = 5;
    public static final int NOTE = 6;
    public static final int END = 0x7F;

    public static final int OUTCOME_RETURNED = 0;
    public static final int OUTCOME_THREW = 1;

    private TraceFormat() {}
}
