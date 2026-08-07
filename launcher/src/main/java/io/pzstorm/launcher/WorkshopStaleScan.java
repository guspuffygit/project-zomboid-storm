package io.pzstorm.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Finds installed workshop items whose published version no longer matches the local install — the
 * exact condition that makes the game's {@code ConnectToServerState.WorkshopConfirm} force
 * NeedsUpdate and interrupt the join with its "install workshop updates" dialog ({@code
 * details.getTimeUpdated() != GetItemInstallTimeStamp(id)}). Pre-updating these before launch is
 * the launcher's whole job; most servers expose no channel that lists their required items, so
 * scanning everything installed is the only superset we can compute client-side.
 *
 * <p>Both sides of the comparison come without new Steam API bindings: the local install timestamp
 * is what Steam records per item in {@code steamapps/workshop/appworkshop_108600.acf} (the same
 * store {@code GetItemInstallTimeStamp} reads), and the published timestamp comes from the
 * anonymous {@code GetPublishedFileDetails} Web API. Hidden or deleted items return no published
 * timestamp and are skipped — the game skips them too (no completed details query, no dialog).
 *
 * <p>Items the server requires but the player never installed are out of reach here: nothing local
 * records them, so the in-game subscribe flow still covers that first-join case.
 */
public final class WorkshopStaleScan {

    static final String PUBLISHED_FILE_DETAILS_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";

    private static final String APP_WORKSHOP_ACF = "appworkshop_108600.acf";

    private WorkshopStaleScan() {}

    /**
     * Both sides of the freshness comparison, for the installed superset plus any extra candidate
     * ids — still a single Web API request. Beyond the stale-installed list, this lets the join
     * flow prove specific items current and skip their per-item Steam confirm round-trip.
     */
    public static final class Scan {
        private final Map<String, Long> installed;
        private final Map<String, Long> published;

        Scan(Map<String, Long> installed, Map<String, Long> published) {
            this.installed = installed;
            this.published = published;
        }

        /** Stale installed item ids, sorted. Empty when no acf exists or everything matches. */
        public List<String> staleInstalled() {
            return staleItems(installed, published);
        }

        /**
         * Provably current: installed locally with the published timestamp equal to the install
         * timestamp — the exact comparison the game's join gate makes. An installed item with no
         * published details (hidden or deleted) also counts: the game skips those too. Anything not
         * installed here is never current.
         */
        public boolean isCurrent(String itemId) {
            Long local = installed.get(itemId);
            if (local == null) {
                return false;
            }
            Long publishedTime = published.get(itemId);
            return publishedTime == null || publishedTime.equals(local);
        }
    }

    /**
     * One acf read plus one batched Web API request covering every installed item and every
     * candidate id. Without an acf nothing can be proven current, so the fetch is skipped and the
     * scan reports everything as needing the full update path.
     */
    public static Scan run(LauncherConfig config, Collection<String> candidateIds)
            throws IOException, InterruptedException {
        Path acf = findAppWorkshopAcf(config);
        Map<String, Long> installed =
                acf == null
                        ? Collections.emptyMap()
                        : parseInstalledTimestamps(Files.readString(acf, StandardCharsets.UTF_8));
        if (installed.isEmpty()) {
            return new Scan(Collections.emptyMap(), Collections.emptyMap());
        }
        Collection<String> query = new LinkedHashSet<>(installed.keySet());
        query.addAll(candidateIds);
        return new Scan(installed, fetchPublishedTimestamps(query));
    }

    static Path findAppWorkshopAcf(LauncherConfig config) {
        for (Path steamapps : config.steamappsCandidates(config.resolveGameDir())) {
            Path acf = steamapps.resolve("workshop").resolve(APP_WORKSHOP_ACF);
            if (Files.isRegularFile(acf)) {
                return acf;
            }
        }
        return null;
    }

    /**
     * Item id -> local {@code timeupdated} from the acf's {@code WorkshopItemsInstalled} block
     * only. The sibling {@code WorkshopItemDetails} block carries Steam's cached copy of the
     * published timestamp under the same key name — reading that would compare published against
     * published and never find anything stale.
     */
    static Map<String, Long> parseInstalledTimestamps(String acfText) {
        Map<String, Long> installed = new LinkedHashMap<>();
        Deque<String> blocks = new ArrayDeque<>();
        String pendingKey = null;
        int i = 0;
        int n = acfText.length();
        while (i < n) {
            char c = acfText.charAt(i);
            if (c == '"') {
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
                String token = sb.toString();
                if (pendingKey == null) {
                    pendingKey = token;
                } else {
                    recordLeaf(installed, blocks, pendingKey, token);
                    pendingKey = null;
                }
            } else if (c == '{') {
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
        return installed;
    }

    private static void recordLeaf(
            Map<String, Long> installed, Deque<String> blocks, String key, String value) {
        if (!"timeupdated".equals(key) || blocks.size() < 2) {
            return;
        }
        Iterator<String> innermostFirst = blocks.iterator();
        String itemId = innermostFirst.next();
        if (!"WorkshopItemsInstalled".equals(innermostFirst.next()) || !isDigits(itemId)) {
            return;
        }
        try {
            installed.put(itemId, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // a malformed timestamp can't be compared, so it can't be called stale
        }
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Published {@code time_updated} per item, anonymously; hidden/deleted items are absent. */
    static Map<String, Long> fetchPublishedTimestamps(Collection<String> itemIds)
            throws IOException, InterruptedException {
        StringBuilder form = new StringBuilder("itemcount=").append(itemIds.size());
        int index = 0;
        for (String id : itemIds) {
            form.append('&')
                    .append(
                            URLEncoder.encode(
                                    "publishedfileids[" + index++ + "]", StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(id, StandardCharsets.UTF_8));
        }
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(PUBLISHED_FILE_DETAILS_URL))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                        .build();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Steam Web API returned HTTP " + response.statusCode());
        }
        return parsePublishedTimestamps(response.body());
    }

    static Map<String, Long> parsePublishedTimestamps(String json) {
        Map<String, Long> published = new LinkedHashMap<>();
        Object root = Json.parse(json);
        Object response = root instanceof Map ? ((Map<?, ?>) root).get("response") : null;
        Object details =
                response instanceof Map ? ((Map<?, ?>) response).get("publishedfiledetails") : null;
        if (!(details instanceof List)) {
            return published;
        }
        for (Object entry : (List<?>) details) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> detail = (Map<?, ?>) entry;
            Object id = detail.get("publishedfileid");
            Object result = detail.get("result");
            Object timeUpdated = detail.get("time_updated");
            if (id instanceof String
                    && result instanceof Number
                    && ((Number) result).longValue() == 1
                    && timeUpdated instanceof Number) {
                published.put((String) id, ((Number) timeUpdated).longValue());
            }
        }
        return published;
    }

    /**
     * Exact inequality, not less-than — mirrors the game's check, which flags any divergence (Steam
     * can install a manifest whose timestamp differs in either direction).
     */
    static List<String> staleItems(Map<String, Long> installed, Map<String, Long> published) {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> entry : installed.entrySet()) {
            Long publishedTime = published.get(entry.getKey());
            if (publishedTime != null && !publishedTime.equals(entry.getValue())) {
                stale.add(entry.getKey());
            }
        }
        Collections.sort(stale);
        return stale;
    }
}
