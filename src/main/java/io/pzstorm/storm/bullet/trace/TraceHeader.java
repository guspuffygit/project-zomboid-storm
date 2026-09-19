package io.pzstorm.storm.bullet.trace;

import java.util.List;
import java.util.Map;

/** Trace file header: format version, free-form metadata and the signature table. */
public record TraceHeader(int version, Map<String, String> metadata, List<Sig> table) {}
