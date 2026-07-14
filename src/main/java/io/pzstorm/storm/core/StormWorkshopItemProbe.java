package io.pzstorm.storm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort diagnosis of WHY a workshop item cannot be downloaded by the dedicated server's
 * anonymous Steam login. The steamclient error the server sees ("Failed to get manifest request
 * code, 'Access Denied'", surfaced to PZ as a bare {@code onItemNotDownloaded result=3}) is the
 * same for every root cause, so this probe asks Steam's public surfaces instead:
 *
 * <ol>
 *   <li>The anonymous {@code ISteamRemoteStorage/GetPublishedFileDetails} Web API - {@code
 *       result=1} means the item is publicly fine and the failure is most likely the temporary
 *       manifest deny-window after an item update; {@code result=9} (FileNotFound) means the item
 *       is deleted, hidden, or moderation-removed.
 *   <li>The item's community page - Steam still renders pages for moderation-removed items (title,
 *       description and all) with a "violates Steam Community &amp; Content Guidelines" banner, so
 *       a page that "loads fine in the browser" can still be undownloadable. The banner, or the
 *       "That item does not exist" error page, disambiguates result=9.
 * </ol>
 *
 * <p>Every network failure degrades to an "unknown" verdict - the probe is called during server
 * startup and must never make things worse than the download failure it is explaining.
 */
public final class StormWorkshopItemProbe {

    private static final String DETAILS_API_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";
    private static final String ITEM_PAGE_URL =
            "https://steamcommunity.com/sharedfiles/filedetails/?id=";

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<div class=\"workshopItemTitle\">([^<]+)</div>");

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StormWorkshopItemProbe() {}

    /** One diagnosed workshop item. */
    public static final class ProbeResult {

        /** Item title scraped from the community page, or null if unavailable. */
        public final String title;

        /** One-line human explanation, ready for the startup banner. */
        public final String verdict;

        /**
         * True when the item is confirmed gone from Steam's side (moderation-removed or deleted)
         * and download retries cannot possibly succeed.
         */
        public final boolean permanentlyGone;

        ProbeResult(String title, String verdict, boolean permanentlyGone) {
            this.title = title;
            this.verdict = verdict;
            this.permanentlyGone = permanentlyGone;
        }
    }

    /** Diagnoses one item. Never throws; network failures produce an "unknown" verdict. */
    public static ProbeResult probe(long itemId) {
        Integer apiResult = fetchApiResult(itemId);
        if (apiResult != null && apiResult == 1) {
            return new ProbeResult(
                    null,
                    "publicly available per the Steam Web API - the download failure is most"
                            + " likely Steam's temporary manifest deny-window after an item"
                            + " update; a later restart should pick it up",
                    false);
        }

        String page = fetchItemPage(itemId);
        String title = page == null ? null : extractTitle(page);
        if (page != null) {
            String pageVerdict = classifyPage(page);
            if (pageVerdict != null) {
                return new ProbeResult(title, pageVerdict, true);
            }
        }

        if (apiResult != null && apiResult == 9) {
            return new ProbeResult(
                    title,
                    "not visible to anonymous accounts (Web API result 9: FileNotFound) - the"
                            + " item is hidden/friends-only or was removed; check the item page"
                            + " while logged in as the author",
                    false);
        }
        return new ProbeResult(
                title,
                "could not determine item status (Steam Web API"
                        + (apiResult == null ? " unreachable" : " result " + apiResult)
                        + (page == null ? ", item page unreachable" : "")
                        + ")",
                false);
    }

    /**
     * Maps known community-page banners to a verdict, or null when the page carries no removal
     * marker. Package-private for tests.
     */
    static String classifyPage(String pageHtml) {
        if (pageHtml.contains("violates Steam Community")) {
            return "REMOVED BY STEAM MODERATION - the item page shows the 'violates Steam"
                    + " Community & Content Guidelines' banner (the page still renders, so it"
                    + " looks alive in a browser). Appeal via Steam Support or republish under a"
                    + " new workshop ID";
        }
        if (pageHtml.contains("That item does not exist")) {
            return "DELETED - the workshop page no longer exists";
        }
        return null;
    }

    /** Extracts the item title from the community page, or null. Package-private for tests. */
    static String extractTitle(String pageHtml) {
        Matcher m = TITLE_PATTERN.matcher(pageHtml);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Returns the per-item {@code result} code from the anonymous details API (1 = OK, 9 = file not
     * found), or null when the API could not be reached or parsed.
     */
    private static Integer fetchApiResult(long itemId) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(DETAILS_API_URL))
                            .timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "itemcount=1&publishedfileids[0]=" + itemId))
                            .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode details =
                    MAPPER.readTree(response.body())
                            .path("response")
                            .path("publishedfiledetails")
                            .path(0)
                            .path("result");
            return details.isInt() ? details.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fetchItemPage(long itemId) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(ITEM_PAGE_URL + itemId))
                            .timeout(Duration.ofSeconds(8))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
