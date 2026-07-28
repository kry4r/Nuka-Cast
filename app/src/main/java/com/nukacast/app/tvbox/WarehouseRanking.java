package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.ConfigSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class WarehouseRanking {
    private WarehouseRanking() {}

    static List<ConfigSource> rankLeaves(List<ConfigSource> sources) {
        List<ConfigSource> leaves = new ArrayList<ConfigSource>();
        for (ConfigSource source : sources) {
            if (source.enabled && !source.isWarehouse() && source.searchableSiteCount > 0) {
                leaves.add(source);
            }
        }
        Collections.sort(leaves, new Comparator<ConfigSource>() {
            @Override public int compare(ConfigSource left, ConfigSource right) {
                int health = health(left) - health(right);
                if (health != 0) return health;
                long leftLatency = left.latencyMs > 0 ? left.latencyMs : Long.MAX_VALUE;
                long rightLatency = right.latencyMs > 0 ? right.latencyMs : Long.MAX_VALUE;
                if (leftLatency != rightLatency) return leftLatency < rightLatency ? -1 : 1;
                return safe(left.name).compareToIgnoreCase(safe(right.name));
            }
        });
        return leaves;
    }

    private static int health(ConfigSource source) {
        if ((source.error != null && !source.error.isEmpty())
                || (source.searchError != null && !source.searchError.isEmpty())) return 2;
        return source.searchableSiteCount > 0 ? 0 : 1;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
