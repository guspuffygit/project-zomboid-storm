package io.pzstorm.storm.advice.entityindex;

import io.pzstorm.storm.entity.StormEntityIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Constructor-exit hook on {@code EntityBucket}: hands each freshly-constructed bucket to {@link
 * StormEntityIndex#onBucketCreated(Object)} so its per-bucket entity array gets a removal index
 * ({@code EntityBucket.updateMembership} otherwise re-scans that array linearly on every entity
 * removal, once per bucket). Buckets are created lazily by {@code EntityBucketManager} plus one
 * renderer bucket inside the {@code EngineEntityManager} constructor, so this covers both.
 */
public class EntityBucketCreatedAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object bucket) {
        if (!GameServer.server) {
            return;
        }
        StormEntityIndex.onBucketCreated(bucket);
    }
}
