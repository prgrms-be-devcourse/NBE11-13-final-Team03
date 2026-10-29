package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.model.SnapshotComparison;
import com.team3.backend.tracking.domain.model.SnapshotDifference;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static com.team3.backend.tracking.domain.model.SnapshotComparison.Outcome.*;

/** Pure JSON comparison. No global ObjectMapper, DB, EligibilityResult writes or event publication. */
@Component
public class OpportunitySnapshotComparator {
    public SnapshotComparison compare(Map<String, Object> before, String beforeNormalizationVersion,
                                      Map<String, Object> after, String afterNormalizationVersion) {
        requireVersion(beforeNormalizationVersion);
        requireVersion(afterNormalizationVersion);
        var changes = new ArrayList<SnapshotDifference>();
        walk("", true, freeze(Objects.requireNonNull(before, "before")),
                true, freeze(Objects.requireNonNull(after, "after")), changes);
        var outcome = !beforeNormalizationVersion.equals(afterNormalizationVersion) ? NORMALIZATION_VERSION_CHANGED
                : changes.isEmpty() ? NO_CHANGE : CHANGES_DETECTED;
        return new SnapshotComparison(outcome, changes);
    }

    private void walk(String path, boolean beforePresent, Object before, boolean afterPresent, Object after,
                      List<SnapshotDifference> changes) {
        if (beforePresent == afterPresent && Objects.equals(before, after)) return;
        if (beforePresent && afterPresent && before instanceof Map<?, ?> left && after instanceof Map<?, ?> right) {
            var keys = new TreeSet<String>();
            left.keySet().forEach(key -> keys.add((String) key));
            right.keySet().forEach(key -> keys.add((String) key));
            for (String key : keys) {
                walk(path + "/" + key.replace("~", "~0").replace("/", "~1"), left.containsKey(key), left.get(key),
                        right.containsKey(key), right.get(key), changes);
            }
        } else {
            // Arrays are kept as whole values, including order. Meaningful domain changes are assessed separately.
            changes.add(new SnapshotDifference(path, beforePresent, before, afterPresent, after));
        }
    }

    private Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            try { return new BigDecimal(number.toString()).stripTrailingZeros(); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("Snapshot number must be finite"); }
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new TreeMap<String, Object>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Snapshot keys must be strings");
                copy.put(name, freeze(item));
            });
            return Collections.unmodifiableMap(copy); // Map.copyOf would reject valid explicit JSON null values.
        }
        if (value instanceof List<?> items) return items.stream().map(this::freeze).toList();
        throw new IllegalArgumentException("Snapshot must contain JSON-compatible values only");
    }

    private void requireVersion(String version) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Normalization version is required");
    }
}
