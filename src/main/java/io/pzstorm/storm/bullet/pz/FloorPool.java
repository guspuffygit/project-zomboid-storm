// Port of PZ glue FloorPool (value type of WorldSimulation's std::map<int, FloorPool>; node
// layout from _Rb_tree<int, pair<int const, FloorPool>>::_M_emplace_hint_unique @00174e60:
// a single std::deque<btRigidBody*> at node+0x28).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import java.util.ArrayDeque;

public class FloorPool {

    public final ArrayDeque<btRigidBody> m_pool = new ArrayDeque<>();
}
