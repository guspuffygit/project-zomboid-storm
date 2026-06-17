package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import zombie.iso.IsoChunk;

/**
 * Dispatched server-side per {@link IsoChunk} when Storm rewarms a {@code ServerCell} that was
 * previously held in the warm map (see {@code
 * io.pzstorm.storm.patch.performance.StormCellWarmingConfig} / {@code
 * io.pzstorm.storm.patch.performance.StormCellWarmer}).
 *
 * <p>Rewarm short-circuits the normal {@code loadOrKeepRelevent} → chunk load pipeline, so vanilla
 * {@code OnLoadChunk} / {@code OnLoadGridsquare} events do NOT fire on this path. Mods that need to
 * react every time a chunk re-enters the active set on the server should subscribe to this event in
 * addition to (or instead of) {@code OnLoadChunk}.
 *
 * <p>Only fires on the dedicated server. The chunk's squares, vehicles, room data, and other
 * in-memory state are unchanged from the moment the cell was warmed.
 */
@RequiredArgsConstructor
public class OnChunkRewarmedEvent implements ZomboidEvent {

    @Getter private final IsoChunk chunk;

    @Override
    public String getName() {
        return "OnChunkRewarmed";
    }
}
