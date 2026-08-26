package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline delete-then-reinstall: removes a workshop item's install record from Steam's {@code
 * appworkshop_108600.acf}. With the item's content directory already deleted, a surviving record
 * makes the next Steam start reconcile the mismatch by verifying the ENTIRE workshop depot —
 * hashing every installed item, which for a large mod set takes ages. With the record gone, Steam
 * just sees a subscribed item that is not installed and cleanly downloads that one item.
 *
 * <p>Steam rewrites this file from memory on exit, so an edit only sticks while Steam is closed.
 * Callers must hold positive evidence of that (the Steamworks child's {@code
 * EXIT_STEAM_UNAVAILABLE} — SteamAPI_Init fails exactly when no Steam client is running) before
 * calling.
 */
final class WorkshopAcfRepair {

    static final String BACKUP_SUFFIX = ".storm-backup";

    /**
     * Both blocks that record an item install: {@code WorkshopItemsInstalled} carries the record
     * the reconciliation compares against disk; {@code WorkshopItemDetails} carries Steam's cached
     * published metadata for it. Stripping only the first leaves a dangling details entry Steam
     * tolerates, but removing both matches what a real unsubscribe leaves behind.
     */
    private static final Set<String> ITEM_BLOCKS =
            Set.of("WorkshopItemsInstalled", "WorkshopItemDetails");

    private WorkshopAcfRepair() {}

    /**
     * Strips each item's blocks from the acf on disk, keeping a {@code .storm-backup} copy of the
     * original. Returns the ids whose record was actually present and removed; an untouched file is
     * never rewritten.
     */
    static List<String> stripInstallRecords(Path acf, Collection<String> itemIds)
            throws IOException {
        if (acf == null || !Files.isRegularFile(acf)) {
            return List.of();
        }
        String original = Files.readString(acf, StandardCharsets.UTF_8);
        List<String> removed = new ArrayList<>();
        for (String id : itemIds) {
            if (!stripped(original, List.of(id)).equals(original)) {
                removed.add(id);
            }
        }
        if (removed.isEmpty()) {
            return removed;
        }
        Files.copy(
                acf,
                acf.resolveSibling(acf.getFileName() + BACKUP_SUFFIX),
                StandardCopyOption.REPLACE_EXISTING);
        Path temp = acf.resolveSibling(acf.getFileName() + ".storm-tmp");
        Files.writeString(temp, stripped(original, removed), StandardCharsets.UTF_8);
        try {
            Files.move(
                    temp, acf, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, acf, StandardCopyOption.REPLACE_EXISTING);
        }
        return removed;
    }

    /**
     * Pure half: the acf text with each item's {@code "<id>" { … }} blocks removed wherever they
     * sit directly under one of {@link #ITEM_BLOCKS}. Same tokenizer discipline as {@link
     * WorkshopStaleScan}'s parser (quoted tokens with backslash escapes, {@code //} comments), plus
     * offset tracking so whole lines are cut without disturbing anything around them.
     */
    static String stripped(String acfText, Collection<String> itemIds) {
        Set<String> ids = new HashSet<>(itemIds);
        StringBuilder out = new StringBuilder(acfText.length());
        Deque<String> blocks = new ArrayDeque<>();
        String pendingKey = null;
        int pendingKeyStart = -1;
        int copied = 0;
        int i = 0;
        int n = acfText.length();
        while (i < n) {
            char c = acfText.charAt(i);
            if (c == '"') {
                int tokenStart = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char d = acfText.charAt(i++);
                    if (d == '\\' && i < n) {
                        sb.append(acfText.charAt(i++));
                    } else if (d == '"') {
                        break;
                    } else {
                        sb.append(d);
                    }
                }
                if (pendingKey == null) {
                    pendingKey = sb.toString();
                    pendingKeyStart = tokenStart;
                } else {
                    pendingKey = null;
                }
            } else if (c == '{') {
                if (pendingKey != null
                        && ids.contains(pendingKey)
                        && blocks.size() == 2
                        && ITEM_BLOCKS.contains(blocks.peek())) {
                    int close = matchingClose(acfText, i);
                    int from = lineStartBefore(acfText, pendingKeyStart);
                    int to = lineEndAfter(acfText, close);
                    out.append(acfText, copied, from);
                    copied = to;
                    i = to;
                    pendingKey = null;
                    continue;
                }
                blocks.push(pendingKey == null ? "" : pendingKey);
                pendingKey = null;
                i++;
            } else if (c == '}') {
                if (!blocks.isEmpty()) {
                    blocks.pop();
                }
                pendingKey = null;
                i++;
            } else if (c == '/' && i + 1 < n && acfText.charAt(i + 1) == '/') {
                while (i < n && acfText.charAt(i) != '\n') {
                    i++;
                }
            } else {
                i++;
            }
        }
        out.append(acfText, copied, n);
        return out.toString();
    }

    /**
     * Index of the brace closing the one at {@code openIndex}, quote-aware; end of text if none.
     */
    private static int matchingClose(String text, int openIndex) {
        int depth = 0;
        int i = openIndex;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                i++;
                while (i < n) {
                    char d = text.charAt(i++);
                    if (d == '\\' && i < n) {
                        i++;
                    } else if (d == '"') {
                        break;
                    }
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return n - 1;
    }

    /** Start of {@code index}'s line when only indentation precedes it, else {@code index}. */
    private static int lineStartBefore(String text, int index) {
        int lineStart = text.lastIndexOf('\n', index - 1) + 1;
        for (int i = lineStart; i < index; i++) {
            char c = text.charAt(i);
            if (c != '\t' && c != ' ') {
                return index;
            }
        }
        return lineStart;
    }

    /** Just past {@code index}'s line when only whitespace follows it, else {@code index + 1}. */
    private static int lineEndAfter(String text, int index) {
        int newline = text.indexOf('\n', index);
        if (newline < 0) {
            return text.length();
        }
        for (int i = index + 1; i < newline; i++) {
            char c = text.charAt(i);
            if (c != '\t' && c != ' ' && c != '\r') {
                return index + 1;
            }
        }
        return newline + 1;
    }
}
