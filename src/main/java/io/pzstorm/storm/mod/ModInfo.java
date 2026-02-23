package io.pzstorm.storm.mod;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ModInfo {
    private final Map<String, String> values;

    public ModInfo(String modInfoString) {
        values =
                modInfoString
                        .lines()
                        .map(line -> line.split("=", 2))
                        .filter(parts -> parts.length == 2)
                        .collect(
                                Collectors.toMap(
                                        parts -> parts[0].trim(),
                                        parts -> parts[1].trim(),
                                        (existing, replacement) -> existing));
    }

    /** Get an arbitrary value from the mod.info file */
    public Optional<String> getValue(String key) {
        if (values.containsKey(key)) {
            return Optional.of(values.get(key));
        }

        return Optional.empty();
    }

    public Optional<String> getName() {
        return getValue("name");
    }

    public Optional<String> getId() {
        return getValue("id");
    }
}
