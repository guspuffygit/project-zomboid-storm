package io.pzstorm.storm.wrappers.ui;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.core.StormPaths;
import java.io.File;
import java.io.IOException;
import zombie.config.BooleanConfigOption;

public class PersistedBooleanConfigOption extends BooleanConfigOption {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final File persistedConfigOptionsDirectory =
            new File(StormPaths.getStormDataDirectory(), "persisted-map-config-options");
    private final File persistedConfigOption;

    public PersistedBooleanConfigOption(String name, Boolean defaultValue) {
        super(name, defaultValue);

        if (!persistedConfigOptionsDirectory.exists()) {
            persistedConfigOptionsDirectory.mkdirs();
        }

        persistedConfigOption = new File(persistedConfigOptionsDirectory, name + ".json");
        this.load();
    }

    private void load() {
        if (this.persistedConfigOption.exists()) {
            try {
                this.value = MAPPER.readValue(this.persistedConfigOption, Boolean.class);
            } catch (IOException e) {
                LOGGER.error("Error loading persisted config for: {}", this.name, e);
            }
        }
    }

    private void save() {
        try {
            MAPPER.writeValue(this.persistedConfigOption, this.value);
        } catch (IOException e) {
            LOGGER.error("Error saving persisted config for: {}", this.name, e);
        }
    }

    @Override
    public void setValue(boolean val) {
        super.setValue(val);
        save();
    }
}
