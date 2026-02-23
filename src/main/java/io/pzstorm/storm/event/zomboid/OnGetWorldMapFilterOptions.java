package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import zombie.config.ConfigOption;

public class OnGetWorldMapFilterOptions implements ZomboidEvent {

    @Getter private final List<ConfigOption> extraConfigOptions = new ArrayList<>();

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    public void addOption(ConfigOption option) {
        extraConfigOptions.add(option);
    }
}
