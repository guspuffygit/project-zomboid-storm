package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.characters.Role;
import zombie.characters.Roles;

class RolePositionPinTest implements UnitTest {

    private List<Role> savedRoles;

    @BeforeEach
    void seedRoles() {
        savedRoles = new ArrayList<>(Roles.getRoles());
        Roles.getRoles().clear();
        Roles.addStatic();
    }

    @AfterEach
    void restoreRoles() {
        Roles.getRoles().clear();
        Roles.getRoles().addAll(savedRoles);
    }

    private static Role custom(String name, int position) {
        Role r = new Role(name);
        r.setPosition(position);
        Roles.getRoles().add(r);
        return r;
    }

    @Test
    void pinsVanillaRenumberedCustomRolesToUserPosition() {
        int userPos = Roles.getRole("user").getPosition();
        Role a = custom("veteran", 0);
        Role b = custom("builder", 1);
        Role c = custom("explorer", 2);

        assertEquals(3, RolePositionPin.pin());
        assertEquals(userPos, a.getPosition());
        assertEquals(userPos, b.getPosition());
        assertEquals(userPos, c.getPosition());
    }

    @Test
    void pinsFreshlyAddedRoleAtMinusOne() {
        Role fresh = custom("fresh", -1);
        assertEquals(1, RolePositionPin.pin());
        assertEquals(Roles.getRole("user").getPosition(), fresh.getPosition());
    }

    @Test
    void leavesReadOnlyRolesAlone() {
        custom("veteran", 0);
        List<Integer> before = new ArrayList<>();
        for (Role r : Roles.getRoles()) {
            if (r.isReadOnly()) {
                before.add(r.getPosition());
            }
        }

        RolePositionPin.pin();

        List<Integer> after = new ArrayList<>();
        for (Role r : Roles.getRoles()) {
            if (r.isReadOnly()) {
                after.add(r.getPosition());
            }
        }
        assertEquals(before, after);
    }

    @Test
    void secondPinIsANoOp() {
        custom("veteran", 0);
        custom("builder", 1);
        assertEquals(2, RolePositionPin.pin());
        assertEquals(0, RolePositionPin.pin());
    }

    @Test
    void alreadyPinnedRoleIsNotCounted() {
        custom("pinned", Roles.getRole("user").getPosition());
        custom("veteran", 0);
        assertEquals(1, RolePositionPin.pin());
    }

    @Test
    void noCustomRolesIsANoOp() {
        assertEquals(0, RolePositionPin.pin());
    }

    @Test
    void missingUserRoleFailsSoft() {
        Roles.getRoles().clear();
        custom("orphan", 0);
        assertEquals(0, RolePositionPin.pin());
        assertEquals(0, Roles.getRole("orphan").getPosition());
    }
}
