package io.pzstorm.storm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort diagnosis of WHY workshop items cannot be downloaded by the dedicated server's
 * anonymous Steam login. The steamclient error the server sees ("Failed to get manifest request
 * code, 'Access Denied'", surfaced to PZ as a bare {@code onItemNotDownloaded result=3}) is the
 * same for every root cause, so this probe asks the anonymous {@code
 * ISteamRemoteStorage/GetPublishedFileDetails} Web API instead - one batched call for all items.
 * Per-item {@code result=1} means the item is publicly visible and the failure is most likely
 * transient (Steam's manifest deny-window right after an item update, or an outage); {@code
 * result=9} (FileNotFound) means the item is not visible to anonymous accounts - hidden, unlisted,
 * friends-only, or removed, indistinguishable without the author's login.
 *
 * <p>Deliberately does NOT scrape the item's community page: Steam embeds the "This item has been
 * removed from the community because it violates Steam Community &amp; Content Guidelines" banner
 * as a hidden {@code display: none} template div in EVERY item page, healthy ones included, so
 * matching that text in fetched HTML misdiagnoses live items as moderation-removed. steamcommunity
 * also rate-limits (HTTP 429) after a few dozen page fetches, which a server with many mods would
 * exceed.
 *
 * <p>Every network failure degrades to an "unknown" verdict - the probe is called during server
 * startup and must never make things worse than the download failure it is explaining.
 */
public final class StormWorkshopItemProbe {

    private static final String DETAILS_API_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StormWorkshopItemProbe() {}

    /** One diagnosed workshop item. */
    public static final class ProbeResult {

        /** Item title from the Web API, or null when the item is not anonymously visible. */
        public final String title;

        /** One-line human explanation, ready for the startup banner. */
        public final String verdict;

        ProbeResult(String title, String verdict) {
            this.title = title;
            this.verdict = verdict;
        }
    }

    /**
     * Diagnoses all items in a single anonymous Web API call. The returned map has an entry for
     * every requested id. Never throws.
     */
    public static Map<Long, ProbeResult> probeAll(List<Long> itemIds) {
        Map<Long, ProbeResult> results = new HashMap<>();
        if (itemIds.isEmpty()) {
            return results;
        }
        String json = fetchDetailsJson(itemIds);
        if (json != null) {
            results.putAll(parseDetails(json));
        }
        for (int i = 0; i < itemIds.size(); i++) {
            results.putIfAbsent(
                    itemIds.get(i),
                    new ProbeResult(
                            null,
                            "could not determine item status (Steam Web API unreachable or"
                                    + " returned no entry for this item)"));
        }
        return results;
    }

    /**
     * Parses a {@code GetPublishedFileDetails} response body into per-item results. Malformed input
     * yields an empty map. Package-private for tests.
     */
    static Map<Long, ProbeResult> parseDetails(String json) {
        Map<Long, ProbeResult> results = new HashMap<>();
        try {
            JsonNode details = MAPPER.readTree(json).path("response").path("publishedfiledetails");
            for (int i = 0; i < details.size(); i++) {
                JsonNode item = details.get(i);
                long id = item.path("publishedfileid").asLong(0L);
                if (id != 0L) {
                    results.put(id, toResult(item));
                }
            }
        } catch (Exception e) {
            // degrade to "unknown" verdicts via probeAll's putIfAbsent
        }
        return results;
    }

    private static ProbeResult toResult(JsonNode item) {
        JsonNode resultNode = item.path("result");
        String title = item.path("title").isTextual() ? item.path("title").asText() : null;
        if (!resultNode.isInt()) {
            return new ProbeResult(
                    title, "could not determine item status (no result code from the Web API)");
        }
        int result = resultNode.asInt();
        if (result == 1) {
            return new ProbeResult(
                    title,
                    "publicly visible per the Steam Web API - the download failure is most likely"
                            + " Steam's temporary manifest deny-window right after an item update,"
                            + " or a transient Steam outage; Storm will retry");
        }
        if (result == 9) {
            return new ProbeResult(
                    title,
                    "not visible to anonymous accounts (Web API result 9: FileNotFound) - the item"
                            + " is hidden, unlisted, friends-only, or was removed; these cases"
                            + " cannot be told apart without the author's login, so check the item"
                            + " page and its visibility setting while logged in as its author");
        }
        return new ProbeResult(title, "Steam Web API reports result " + result + " for this item");
    }

    private static String fetchDetailsJson(List<Long> itemIds) {
        try {
            StringBuilder body = new StringBuilder("itemcount=").append(itemIds.size());
            for (int i = 0; i < itemIds.size(); i++) {
                body.append("&publishedfileids[").append(i).append("]=").append(itemIds.get(i));
            }
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(DETAILS_API_URL))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
