package io.pzstorm.storm.mod;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
@Setter
public class StormMod {
    private final ModInfo modInfo;
    private final List<ModJar> modJars;
}
